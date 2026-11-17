package com.jare.autoclipper.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.jare.autoclipper.media.MediaUtil
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Hardware-decoded video straight into a GPU texture (decoder → SurfaceTexture → OES texture).
 * No CPU copies; rotation/crop handled by the GPU transform. Use on the GL thread only.
 *
 * frameAt(t) walks forward through the file; it only seeks when asked to jump back or far ahead.
 * Create with [open]: if anything fails half-way, everything already created is released.
 */
class VideoTexture private constructor(
    val tex: Int, private val st: SurfaceTexture, private val surface: Surface, private val ht: HandlerThread,
    private val frames: Semaphore, private val ex: MediaExtractor, private val codec: MediaCodec,
    val dispW: Int, val dispH: Int, val duration: Double,
) : Closeable {
    val texMatrix = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    private val info = MediaCodec.BufferInfo()
    private var inEos = false; private var outEos = false
    private var pendIdx = -1; private var pendPts = 0.0
    private var curPts = -1.0
    private var closed = false

    companion object {
        fun open(ctx: Context, uri: Uri): VideoTexture {
            var tex = 0; var st: SurfaceTexture? = null; var surface: Surface? = null
            var ht: HandlerThread? = null; var ex: MediaExtractor? = null; var codec: MediaCodec? = null
            try {
                ex = MediaExtractor()
                MediaUtil.setSource(ex, ctx, uri)
                val ti = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                    ?: throw IOException("No video track")
                ex.selectTrack(ti)
                val fmt = ex.getTrackFormat(ti)
                val r = MediaMetadataRetriever()
                val rot = try { MediaUtil.setSource(r, ctx, uri); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0 }
                    catch (e: Exception) { 0 } finally { runCatching { r.release() } }
                val w = fmt.getInteger(MediaFormat.KEY_WIDTH); val h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                val dw = if (rot % 180 != 0) h else w; val dh = if (rot % 180 != 0) w else h
                val dur = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1e6 else 0.0
                fmt.setInteger(MediaFormat.KEY_ROTATION, rot) // decoder sets the surface transform; the SurfaceTexture matrix applies it

                tex = Tex.createOes()
                val frames = Semaphore(0)
                ht = HandlerThread("vtex").apply { start() }
                st = SurfaceTexture(tex)
                st.setOnFrameAvailableListener({ frames.release() }, Handler(ht.looper))
                surface = Surface(st)
                codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
                codec.configure(fmt, surface, null, 0)
                codec.start()
                return VideoTexture(tex, st, surface, ht, frames, ex, codec, dw, dh, dur)
            } catch (t: Throwable) {
                runCatching { codec?.release() }
                runCatching { surface?.release() }; runCatching { st?.release() }
                runCatching { ex?.release() }
                ht?.quitSafely()
                if (tex != 0) GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
                throw IOException("Could not open video decoder: ${t.message}", t)
            }
        }
    }

    private fun seek(t: Double) {
        ex.seekTo((t * 1e6).toLong().coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        if (pendIdx >= 0) { runCatching { codec.releaseOutputBuffer(pendIdx, false) }; pendIdx = -1 }
        codec.flush()
        inEos = false; outEos = false; curPts = -1.0
    }

    /** Puts the frame for time t (seconds) into [tex]. Returns false if nothing could be decoded. */
    fun frameAt(t: Double): Boolean {
        if (closed) return false
        val ahead = if (pendIdx >= 0) pendPts else curPts
        if ((curPts < 0 && pendIdx < 0) || t < curPts - 0.25 || t > ahead + 1.0) seek(t)
        var guard = 0
        while (guard++ < 2000) {
            if (pendIdx < 0 && !dequeue()) break
            if (pendPts <= t + 0.0005 || curPts < 0) {
                // skip frames we would immediately replace (catching up after a seek), but always show the last one <= t
                val skip = curPts >= 0 && pendPts < t - 0.04
                val idx = pendIdx; pendIdx = -1
                if (skip) { codec.releaseOutputBuffer(idx, false); curPts = pendPts; continue }
                frames.drainPermits()
                codec.releaseOutputBuffer(idx, true)
                if (frames.tryAcquire(400, TimeUnit.MILLISECONDS)) { st.updateTexImage(); st.getTransformMatrix(texMatrix) }
                curPts = pendPts
                if (curPts > t) break
            } else break
        }
        return curPts >= 0
    }

    /** fills pendIdx/pendPts with the next decoded frame; false at end of stream or when the decoder stalls */
    private fun dequeue(): Boolean {
        var spins = 0
        while (!outEos) {
            if (!inEos) {
                val ii = codec.dequeueInputBuffer(1000)
                if (ii >= 0) {
                    val b = codec.getInputBuffer(ii)!!
                    val n = ex.readSampleData(b, 0)
                    if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true }
                    else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 3000)
            if (oi >= 0) {
                val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                if (eos) outEos = true
                if (!eos || info.size > 0) { pendIdx = oi; pendPts = info.presentationTimeUs / 1e6; return true }
                codec.releaseOutputBuffer(oi, false); return false
            }
            if (++spins > 600) { Log.w("VideoTexture", "decoder stalled"); return false } // ~2 s
        }
        return false
    }

    override fun close() {
        if (closed) return
        closed = true
        if (pendIdx >= 0) runCatching { codec.releaseOutputBuffer(pendIdx, false) }
        runCatching { codec.stop() }; runCatching { codec.release() }
        runCatching { ex.release() }
        runCatching { surface.release() }; runCatching { st.release() }
        runCatching { GLES30.glDeleteTextures(1, intArrayOf(tex), 0) }
        ht.quitSafely()
    }
}
