package com.jare.autoclipper.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

object MediaUtil {
    fun setSource(ex: MediaExtractor, ctx: Context, uri: Uri) {
        if (uri.scheme == "file") ex.setDataSource(uri.path!!) else ex.setDataSource(ctx, uri, null)
    }
    fun setSource(r: MediaMetadataRetriever, ctx: Context, uri: Uri) {
        if (uri.scheme == "file") r.setDataSource(uri.path!!) else r.setDataSource(ctx, uri)
    }
}

/** Growable float buffer. */
class FloatList(cap: Int = 1 shl 16) {
    var a = FloatArray(cap); var n = 0
    fun add(v: Float) { if (n == a.size) a = a.copyOf(a.size * 2); a[n++] = v }
    fun toArray(): FloatArray = a.copyOf(n)
}

/**
 * Streaming windowed-sinc resampler (anti-aliased), for one channel.
 * DC gain is normalised so quiet passages stay quiet.
 */
/** receives one sample; a fun interface so floats are passed without creating objects (much faster) */
fun interface FloatSink { fun put(v: Float) }

class Resampler(private val inRate: Int, private val outRate: Int, private val sink: FloatSink) {
    private val same = inRate == outRate
    private val ratio = inRate.toDouble() / outRate
    private val cutoff = min(1.0, outRate.toDouble() / inRate) * 0.92
    private val half = if (same) 0 else ceil(8 / cutoff).toInt()
    private var buf = FloatArray(1 shl 15)
    private var bufStart = 0L
    private var bufLen = 0
    private var nextOut = 0L

    fun push(x: Float) {
        if (same) { sink.put(x); return }
        if (bufLen == buf.size) compact()
        buf[bufLen++] = x
        if ((bufLen and 255) == 0) produce(false)
    }

    fun finish() { if (!same) produce(true) }

    private fun compact() {
        val keepFrom = floor(nextOut * ratio).toLong() - half - 1
        val drop = (keepFrom - bufStart).coerceIn(0L, bufLen.toLong()).toInt()
        if (drop > 0) { System.arraycopy(buf, drop, buf, 0, bufLen - drop); bufLen -= drop; bufStart += drop }
        if (bufLen == buf.size) buf = buf.copyOf(buf.size * 2)
    }

    // kernel table: KT entries per input sample of distance d in [0, half]
    private val KT = 256
    private val table: FloatArray = if (same) FloatArray(1) else FloatArray(half * KT + 2) { k ->
        val d = k.toDouble() / KT
        if (d >= half) 0f else {
            val x = PI * d * cutoff
            val sinc = if (abs(x) < 1e-9) 1.0 else sin(x) / x
            (sinc * (0.5 + 0.5 * cos(PI * d / half))).toFloat()
        }
    }

    private fun produce(final: Boolean) {
        val end = bufStart + bufLen
        while (true) {
            val p = nextOut * ratio
            val c = floor(p).toLong()
            if (!final && c + half + 1 >= end) break
            if (final && c >= end) break
            var s = 0f; var ws = 0f
            var i = c - half + 1
            val last = c + half
            while (i <= last) {
                val idx = (i - bufStart).toInt()
                if (idx in 0 until bufLen) {
                    val ti = (abs(p - i) * KT).toInt()
                    if (ti < table.size) { val w = table[ti]; s += buf[idx] * w; ws += w }
                }
                i++
            }
            sink.put(if (ws > 1e-6f) s / ws else 0f)
            nextOut++
        }
    }
}

/**
 * Decodes the first audio track of any media file into float PCM with MediaCodec.
 * Works for mp4/mov/mkv/webm/mp3/m4a/wav/ogg... (whatever the phone can decode).
 */
object AudioDecoder {
    class Pcm(val rate: Int, val ch: Int, val data: FloatArray) {
        val frames get() = data.size / ch
        val dur get() = frames.toDouble() / rate
    }

    fun decode(ctx: Context, uri: Uri, startSec: Double = 0.0, endSec: Double = 1e9, outRate: Int = 0, channels: Int = 1,
               cancel: () -> Boolean = { false }): Pcm {
        val out = FloatList()
        var rate = outRate
        decodeStream(ctx, uri, startSec, endSec, outRate, channels, cancel, { r -> if (rate == 0) rate = r }) { v -> out.add(v) }
        return Pcm(if (rate == 0) 44100 else rate, channels, out.toArray())
    }

    /**
     * Loudness curve for music / SFX analysis, read straight from the decoder at the file's own rate
     * (no resampling): RMS every [hopSec], using every 4th frame, channels averaged.
     * Returns duration (s) and the curve. Several times faster than decode().
     */
    fun envelope(ctx: Context, uri: Uri, hopSec: Double): Pair<Double, FloatArray> {
        val ex = MediaExtractor()
        MediaUtil.setSource(ex, ctx, uri)
        var codec: MediaCodec? = null
        try {
            val ti = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw IOException("This file has no audio track")
            ex.selectTrack(ti)
            val fmt = ex.getTrackFormat(ti)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            var hop = (rate * hopSec).toInt().coerceAtLeast(1)
            val out = FloatList(4096)
            var acc = 0.0; var cnt = 0; var inHop = 0; var frames = 0L
            val info = MediaCodec.BufferInfo()
            var inEos = false; var outEos = false
            while (!outEos) {
                if (!inEos) {
                    val ii = codec.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val b = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true }
                        else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 5000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    hop = (rate * hopSec).toInt().coerceAtLeast(1)
                } else if (oi >= 0) {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true
                    if (info.size > 0) {
                        val b = codec.getOutputBuffer(oi)!!.order(ByteOrder.nativeOrder())
                        b.position(info.offset); b.limit(info.offset + info.size)
                        if (isFloat) {
                            val fb = b.asFloatBuffer(); val n = fb.remaining() / ch
                            for (f in 0 until n) {
                                if ((inHop and 3) == 0) { var v = 0f; for (c in 0 until ch) v += fb.get(f * ch + c); v /= ch; acc += v * v; cnt++ }
                                if (++inHop >= hop) { out.add(kotlin.math.sqrt(acc / cnt.coerceAtLeast(1)).toFloat()); acc = 0.0; cnt = 0; inHop = 0 }
                            }
                            frames += n
                        } else {
                            val sb = b.asShortBuffer(); val n = sb.remaining() / ch
                            for (f in 0 until n) {
                                if ((inHop and 3) == 0) { var v = 0; for (c in 0 until ch) v += sb.get(f * ch + c); val x = v / (32768f * ch); acc += x * x; cnt++ }
                                if (++inHop >= hop) { out.add(kotlin.math.sqrt(acc / cnt.coerceAtLeast(1)).toFloat()); acc = 0.0; cnt = 0; inHop = 0 }
                            }
                            frames += n
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                }
            }
            return (frames.toDouble() / rate) to out.toArray()
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            ex.release()
        }
    }

    /**
     * Streams interleaved samples ([channels] per frame) to [sink].
     * outRate = 0 keeps the native rate (reported via onRate).
     */
    fun decodeStream(ctx: Context, uri: Uri, startSec: Double, endSec: Double, outRate: Int, channels: Int,
                     cancel: () -> Boolean, onRate: (Int) -> Unit, sink: FloatSink) {
        val ex = MediaExtractor()
        MediaUtil.setSource(ex, ctx, uri)
        var codec: MediaCodec? = null
        try {
            val ti = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw IOException("This file has no audio track")
            ex.selectTrack(ti)
            val fmt = ex.getTrackFormat(ti)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            if (startSec > 0) ex.seekTo((startSec * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()

            var srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var srcCh = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            var res: Array<Resampler>? = null
            // one queue per channel so the resamplers (which emit in bursts) can be re-interleaved safely
            val qs = Array(channels) { FloatList() }
            val qHead = IntArray(channels)
            fun drainQ() {
                while (true) {
                    for (c in 0 until channels) if (qHead[c] >= qs[c].n) {
                        // compact
                        for (k in 0 until channels) {
                            val q = qs[k]; val h = qHead[k]
                            if (h > 0) { System.arraycopy(q.a, h, q.a, 0, q.n - h); q.n -= h; qHead[k] = 0 }
                        }
                        return
                    }
                    for (c in 0 until channels) sink.put(qs[c].a[qHead[c]++])
                }
            }
            fun makeRes() {
                val target = if (outRate == 0) srcRate else outRate
                onRate(target)
                res = Array(channels) { c -> Resampler(srcRate, target) { v -> qs[c].add(v) } }
            }
            val info = MediaCodec.BufferInfo()
            var inEos = false; var outEos = false
            val startUs = (startSec * 1e6).toLong(); val endUs = if (endSec >= 1e8) Long.MAX_VALUE else (endSec * 1e6).toLong()
            val feedEndUs = if (endUs == Long.MAX_VALUE) Long.MAX_VALUE else endUs + 200_000
            val frameVals = FloatArray(8)
            while (!outEos) {
                if (cancel()) throw InterruptedException("cancelled")
                if (!inEos) {
                    val ii = codec.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val b = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(b, 0)
                        if (n < 0 || ex.sampleTime > feedEndUs) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true
                        } else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 5000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    srcCh = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    res = null
                } else if (oi >= 0) {
                    if (res == null) makeRes()
                    val rs = res!!
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true
                    if (info.size > 0) {
                        val b = codec.getOutputBuffer(oi)!!.order(ByteOrder.nativeOrder())
                        b.position(info.offset); b.limit(info.offset + info.size)
                        val bytesPer = if (isFloat) 4 else 2
                        val frames = info.size / (bytesPer * srcCh)
                        val dt = 1e6 / srcRate
                        for (f in 0 until frames) {
                            val us = (info.presentationTimeUs + f * dt).toLong()
                            for (c in 0 until srcCh) {
                                val v = if (isFloat) b.float else b.short / 32768f
                                if (c < 8) frameVals[c] = v
                            }
                            if (us < startUs) continue
                            if (us >= endUs) { outEos = true; break }
                            if (channels == 1) {
                                var s = 0f; val m = min(srcCh, 8); for (c in 0 until m) s += frameVals[c]
                                rs[0].push(s / m)
                            } else {
                                val l = frameVals[0]; val r = if (srcCh > 1) frameVals[1] else frameVals[0]
                                rs[0].push(l); rs[1].push(r)
                            }
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    drainQ()
                } else if (inEos && oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // keep draining
                }
            }
            res?.forEach { it.finish() }
            drainQ()
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            ex.release()
        }
    }
}
