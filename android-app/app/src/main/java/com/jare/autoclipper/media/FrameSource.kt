package com.jare.autoclipper.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** A decoded picture: draw [crop] of [bmp], rotated clockwise by [rotation] degrees. */
class VFrame(val bmp: Bitmap, val crop: Rect, val rotation: Int) {
    val dispW get() = if (rotation % 180 == 0) crop.width() else crop.height()
    val dispH get() = if (rotation % 180 == 0) crop.height() else crop.width()
}

/**
 * Fast sequential video frame reader (no per-frame seeking), the native version of the web
 * editor's WebCodecs "makeFrameSource". Frames are decoded by the hardware decoder.
 *
 * GPU mode: decoder -> ImageReader(PRIVATE) -> HardwareBuffer -> hardware Bitmap (zero copy).
 * CPU mode: decoder -> ImageReader(YUV_420_888) -> ARGB Bitmap (fallback for odd devices).
 */
class FrameSource private constructor(ctx: Context, uri: Uri, private val gpu: Boolean) : Closeable {
    companion object {
        @Volatile var gpuOk = true
        private const val TAG = "FrameSource"

        fun open(ctx: Context, uri: Uri): FrameSource {
            if (gpuOk) {
                val fs = try { FrameSource(ctx, uri, true) } catch (e: Exception) { Log.w(TAG, "gpu init failed", e); gpuOk = false; null }
                if (fs != null) {
                    // probe one frame; if wrapping hardware buffers fails, fall back for the rest of the session
                    try { fs.frameAt(0.0); fs.seek(0.0); return fs } catch (e: Exception) { Log.w(TAG, "gpu frames failed", e); gpuOk = false; fs.close() }
                }
            }
            return FrameSource(ctx, uri, false)
        }
    }

    private class Fr(val t: Double, val img: Image?, val vf: VFrame, val hw: Boolean) {
        fun close() { if (hw) runCatching { vf.bmp.recycle() }; runCatching { img?.close() } }
    }

    private val ex = MediaExtractor()
    private val codec: MediaCodec
    private val reader: ImageReader
    private val thread = HandlerThread("frames").apply { start() }
    private val queue = LinkedBlockingQueue<Image>()
    val width: Int; val height: Int; val rotation: Int; val duration: Double
    private var inEos = false; private var outEos = false
    private var cur: Fr? = null; private var nxt: Fr? = null
    private val retired = ArrayDeque<Fr>()
    private val info = MediaCodec.BufferInfo()
    private val pool = ArrayList<Bitmap>(); private var poolIdx = 0
    private var ints: IntArray? = null
    @Volatile private var closed = false

    init {
        MediaUtil.setSource(ex, ctx, uri)
        val ti = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ?: throw IOException("No video track")
        ex.selectTrack(ti)
        val fmt = ex.getTrackFormat(ti)
        width = fmt.getInteger(MediaFormat.KEY_WIDTH); height = fmt.getInteger(MediaFormat.KEY_HEIGHT)
        duration = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1e6 else 0.0
        val r = MediaMetadataRetriever()
        rotation = try { MediaUtil.setSource(r, ctx, uri); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0 } catch (e: Exception) { 0 } finally { r.release() }
        fmt.setInteger(MediaFormat.KEY_ROTATION, 0) // we rotate ourselves when drawing
        reader = if (gpu) ImageReader.newInstance(width, height, ImageFormat.PRIVATE, 6, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        else ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 6)
        reader.setOnImageAvailableListener({ rd ->
            try { rd.acquireNextImage()?.let { if (closed) it.close() else queue.put(it) } } catch (e: Exception) { Log.w(TAG, "acquire", e) }
        }, Handler(thread.looper))
        codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, reader.surface, null, 0)
        codec.start()
    }

    fun seek(t: Double) {
        ex.seekTo((t * 1e6).toLong().coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        inEos = false; outEos = false
        cur?.let { retire(it) }; nxt?.let { retire(it) }; cur = null; nxt = null
        // drop images that were still on their way
        while (true) { val im = queue.poll(30, TimeUnit.MILLISECONDS) ?: break; im.close() }
    }

    /** Frame shown at time t (seconds). Calls should mostly move forward in time. */
    fun frameAt(t: Double): VFrame? {
        val c = cur; val n = nxt
        if ((c == null && n == null) || t < (c ?: n)!!.t - 0.25 || t > (n ?: c)!!.t + 1.5) seek(t)
        while (nxt == null || nxt!!.t <= t + 0.0005) {
            nxt?.let { x -> cur?.let { retire(it) }; cur = x; nxt = null }
            nxt = decodeOne() ?: break
        }
        return (cur ?: nxt)?.vf
    }

    private fun retire(f: Fr) {
        retired.addLast(f)
        while (retired.size > 2) retired.removeFirst().close()
    }

    private fun feed() {
        val ii = codec.dequeueInputBuffer(2000)
        if (ii < 0) return
        val b = codec.getInputBuffer(ii)!!
        val n = ex.readSampleData(b, 0)
        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true }
        else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
    }

    private fun decodeOne(): Fr? {
        var spins = 0
        while (true) {
            if (outEos) return null
            if (!inEos) feed()
            val oi = codec.dequeueOutputBuffer(info, 5000)
            if (oi >= 0) {
                val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                if (eos) outEos = true
                if (!eos || info.size > 0) {
                    val pts = info.presentationTimeUs
                    codec.releaseOutputBuffer(oi, true)
                    val img = awaitImage(pts) ?: continue
                    return makeFrame(img)
                } else { codec.releaseOutputBuffer(oi, false); return null }
            }
            if (++spins > 1500) { Log.w(TAG, "decoder stalled"); return null }
        }
    }

    private fun awaitImage(ptsUs: Long): Image? {
        while (true) {
            val im = queue.poll(1000, TimeUnit.MILLISECONDS) ?: return null
            val us = im.timestamp / 1000
            if (abs(us - ptsUs) <= 2 || us > ptsUs) return im
            im.close() // stale frame from before a seek
        }
    }

    private fun makeFrame(img: Image): Fr {
        val t = img.timestamp / 1e9
        val crop = Rect(img.cropRect)
        if (gpu) {
            val hb = img.hardwareBuffer ?: run { img.close(); throw IOException("no hardware buffer") }
            var bmp: Bitmap? = null
            try { bmp = Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB)) } finally { hb.close() }
            if (bmp == null) { img.close(); throw IOException("wrapHardwareBuffer unsupported") }
            return Fr(t, img, VFrame(bmp, crop, rotation), true)
        }
        // CPU path: YUV -> ARGB (downscale big sources so it stays fast)
        val sub = if (minOf(crop.width(), crop.height()) > 1300) 2 else 1
        val w = crop.width() / sub; val h = crop.height() / sub
        if (pool.size < 5 || pool[0].width != w || pool[0].height != h) {
            pool.clear(); repeat(5) { pool.add(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)) }
        }
        val bmp = pool[poolIdx]; poolIdx = (poolIdx + 1) % pool.size
        val px = ints?.takeIf { it.size == w * h } ?: IntArray(w * h).also { ints = it }
        val pY = img.planes[0]; val pU = img.planes[1]; val pV = img.planes[2]
        val yb = pY.buffer; val ub = pU.buffer; val vb = pV.buffer
        val yRS = pY.rowStride; val yPS = pY.pixelStride; val uvRS = pU.rowStride; val uvPS = pU.pixelStride; val vRS = pV.rowStride; val vPS = pV.pixelStride
        var o = 0
        for (y in 0 until h) {
            val sy = crop.top + y * sub
            val yRow = sy * yRS; val uRow = (sy shr 1) * uvRS; val vRow = (sy shr 1) * vRS
            for (x in 0 until w) {
                val sx = crop.left + x * sub
                val Y = (yb.get(yRow + sx * yPS).toInt() and 0xff) - 16
                val U = (ub.get(uRow + (sx shr 1) * uvPS).toInt() and 0xff) - 128
                val V = (vb.get(vRow + (sx shr 1) * vPS).toInt() and 0xff) - 128
                val c = 298 * (if (Y < 0) 0 else Y)
                var r = (c + 409 * V + 128) shr 8
                var g = (c - 100 * U - 208 * V + 128) shr 8
                var b = (c + 516 * U + 128) shr 8
                r = if (r < 0) 0 else if (r > 255) 255 else r
                g = if (g < 0) 0 else if (g > 255) 255 else g
                b = if (b < 0) 0 else if (b > 255) 255 else b
                px[o++] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        img.close()
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return Fr(t, null, VFrame(bmp, Rect(0, 0, w, h), rotation), false)
    }

    override fun close() {
        closed = true
        cur?.close(); nxt?.close(); retired.forEach { it.close() }; retired.clear(); cur = null; nxt = null
        runCatching { codec.stop() }; runCatching { codec.release() }
        while (true) { val im = queue.poll() ?: break; im.close() }
        runCatching { reader.close() }
        runCatching { ex.release() }
        thread.quitSafely()
    }
}
