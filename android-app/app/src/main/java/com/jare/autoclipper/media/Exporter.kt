package com.jare.autoclipper.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** MP4 export helpers: AAC audio encoding + an H.264 encoder whose input surface the GPU draws into. */
object Exporter {
    class Cancelled : Exception("Cancelled.")
    class Sample(val data: ByteArray, val pts: Long, val flags: Int)
    class EncodedAudio(val format: MediaFormat, val samples: List<Sample>)

    fun videoFormat(w: Int, h: Int, fps: Int, br: Int): MediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, br)
        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
    }

    fun canEncode(w: Int, h: Int, fps: Int): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(videoFormat(w, h, fps, 8_000_000)) != null

    /** interleaved stereo float 48 kHz -> AAC-LC 192 kbps */
    fun encodeAudio(audio: FloatArray, cancel: () -> Boolean): EncodedAudio {
        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
        }
        val out = ArrayList<Sample>(); var fmt: MediaFormat? = null
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            enc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); enc.start()
            val total = audio.size / 2; var pos = 0; var inEos = false
            val info = MediaCodec.BufferInfo()
            while (true) {
                if (cancel()) throw Cancelled()
                if (!inEos) {
                    val ii = enc.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val b = enc.getInputBuffer(ii)!!.order(ByteOrder.nativeOrder()); b.clear()
                        val n = minOf(b.remaining() / 4, total - pos)
                        if (n <= 0) { enc.queueInputBuffer(ii, 0, 0, pos * 1_000_000L / 48000, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true }
                        else {
                            for (k in 0 until n * 2) {
                                val v = audio[pos * 2 + k].coerceIn(-1f, 1f)
                                b.putShort((v * 32767f).roundToInt().toShort())
                            }
                            enc.queueInputBuffer(ii, 0, n * 4, pos * 1_000_000L / 48000, 0); pos += n
                        }
                    }
                }
                val oi = enc.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) fmt = enc.outputFormat
                else if (oi >= 0) {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        val b = enc.getOutputBuffer(oi)!!; b.position(info.offset); b.limit(info.offset + info.size)
                        val d = ByteArray(info.size); b.get(d)
                        out.add(Sample(d, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()))
                    }
                    enc.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        } finally { runCatching { enc.stop() }; enc.release() }
        return EncodedAudio(fmt ?: throw IOException("Audio encoder gave no format"), out)
    }

    /**
     * H.264 encoder + MP4 muxer. Draw frames into [surface] with EGL (setting each frame's
     * presentation time), then call finish(). Output is drained on its own thread.
     */
    class VideoSink(private val out: File, w: Int, h: Int, fps: Int, bitrate: Int, private val audio: EncodedAudio) {
        private val enc: MediaCodec
        val surface: Surface
        private val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private val err = AtomicReference<Throwable?>(null)
        private val drain: Thread
        @Volatile var framesOut = 0; private set

        init {
            val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(videoFormat(w, h, fps, bitrate))
                ?: throw IOException("This phone cannot encode ${w}×$h at $fps fps. Try Full HD or 30 fps.")
            enc = MediaCodec.createByCodecName(name)
            val vf = videoFormat(w, h, fps, bitrate)
            runCatching { vf.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) }
            enc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = enc.createInputSurface()
            enc.start()
            drain = Thread({
                try {
                    val info = MediaCodec.BufferInfo(); var vTrack = -1; var started = false
                    while (true) {
                        val oi = enc.dequeueOutputBuffer(info, 10_000)
                        if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            vTrack = muxer.addTrack(enc.outputFormat)
                            val aTrack = muxer.addTrack(audio.format)
                            muxer.start(); started = true
                            val ai = MediaCodec.BufferInfo()
                            for (s in audio.samples) { ai.set(0, s.data.size, s.pts, s.flags); muxer.writeSampleData(aTrack, ByteBuffer.wrap(s.data), ai) }
                        } else if (oi >= 0) {
                            val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && started) {
                                val b = enc.getOutputBuffer(oi)!!; b.position(info.offset); b.limit(info.offset + info.size)
                                muxer.writeSampleData(vTrack, b, info); framesOut++
                            }
                            enc.releaseOutputBuffer(oi, false)
                            if (eos) break
                        }
                    }
                } catch (t: Throwable) { err.set(t); Log.e("VideoSink", "drain", t) }
            }, "encoder-drain")
            drain.start()
        }

        fun check() { err.get()?.let { throw IOException("Encoder error: " + it.message) } }

        /** call after the last frame was swapped */
        fun finish() {
            enc.signalEndOfInputStream()
            drain.join(30_000)
            check()
            close(true)
        }

        fun abort() { runCatching { enc.signalEndOfInputStream() }; drain.join(3000); close(false) }

        private fun close(ok: Boolean) {
            runCatching { enc.stop() }; runCatching { enc.release() }; runCatching { surface.release() }
            runCatching { muxer.stop() }; runCatching { muxer.release() }
            if (!ok) out.delete()
        }
    }
}
