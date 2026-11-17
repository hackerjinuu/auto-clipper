package com.jare.autoclipper.editor

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.view.SurfaceHolder
import com.jare.autoclipper.App
import com.jare.autoclipper.CrashLog
import com.jare.autoclipper.gl.EglCore
import com.jare.autoclipper.gl.VideoTexture
import com.jare.autoclipper.media.Exporter
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * GPU render engine (OpenGL ES 3) shared by the live preview and the export.
 * Everything GL happens on one thread. Preview: audio is the master clock, the video follows it;
 * settings are read every frame, so sliders update live while playing.
 * Nothing in here is allowed to take the app down: every entry point reports errors instead.
 */
class PreviewEngine(private val vm: EditorViewModel) : SurfaceHolder.Callback {
    private val ht = HandlerThread("gl-engine").apply { start() }
    private val h = Handler(ht.looper)
    private var egl: EglCore? = null
    private var comp: Compositor? = null
    private var win: EGLSurface? = null
    private var surfW = 0; private var surfH = 0
    private var main: VideoTexture? = null
    private var mainUri: Uri? = null
    private var mainFailed: Uri? = null
    @Volatile var playing = false; private set
    @Volatile var exporting = false; private set

    init {
        onGl("init") { egl = EglCore(); comp = Compositor(vm, vm.ctx) }
    }

    private fun fail(where: String, t: Throwable) {
        CrashLog.warn(where, t)
        if (t is OutOfMemoryError) App.freeMemory("out of memory in $where")
        vm.post { vm.stat = "$where failed: " + (t.message ?: t.javaClass.simpleName) }
    }

    private fun onGl(where: String, block: () -> Unit) { h.post { try { block() } catch (t: Throwable) { fail(where, t) } } }
    private fun onGlWait(where: String, ms: Long, block: () -> Unit) {
        val l = CountDownLatch(1)
        h.post { try { block() } catch (t: Throwable) { fail(where, t) } finally { l.countDown() } }
        l.await(ms, TimeUnit.MILLISECONDS)
    }

    /* ---------- surface ---------- */
    override fun surfaceCreated(holder: SurfaceHolder) {
        onGl("surface") { win?.let { egl?.destroy(it) }; win = null; win = egl?.windowSurface(holder.surface); requestStill() }
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        onGl("surface") { surfW = width; surfH = height; requestStill() }
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        playing = false
        // the window must not be used after this returns: wait for the GL thread to let go of it
        onGlWait("surface", 4000) { win?.let { egl?.destroy(it) }; win = null }
    }

    private fun mainTex(): VideoTexture? {
        val uri = vm.srcUri ?: return null
        if (mainUri == uri) return main
        if (mainFailed == uri) return null
        runCatching { main?.close() }; main = null; mainUri = null
        try { main = VideoTexture.open(vm.ctx, uri); mainUri = uri }
        catch (t: Throwable) { mainFailed = uri; fail("Video decoder", t) }
        return main
    }

    fun resetSource() = onGl("reset") { runCatching { main?.close() }; main = null; mainUri = null; mainFailed = null; comp?.closeVideos() }

    /* ---------- paused frame ---------- */
    private val stillRun = Runnable { try { drawStill() } catch (t: Throwable) { fail("Preview", t) } }
    fun requestStill() { h.removeCallbacks(stillRun); h.post(stillRun) }

    private fun drawStill() {
        if (playing || exporting) return
        val e = egl ?: return; val c = comp ?: return; val w = win ?: return
        if (surfW <= 0 || surfH <= 0) return
        e.makeCurrent(w)
        val ts = if (vm.hasVideo) vm.stime(vm.phRel) else 0.0
        val m = if (vm.hasVideo) mainTex() else null
        val ok = try { m?.frameAt(ts) ?: false } catch (t: Throwable) { dropMain(t); false }
        c.render(surfW, surfH, ts, if (ok) m else null)
        e.swap(w)
    }

    /** the decoder died (e.g. Android took it back): close it so the next frame opens a fresh one */
    private fun dropMain(t: Throwable) {
        CrashLog.warn("main decoder", t)
        runCatching { main?.close() }; main = null; mainUri = null
    }

    /* ---------- playback ---------- */
    fun play() {
        if (playing || exporting || !vm.hasVideo) return
        playing = true
        vm.onPlayState(true)
        // mix off the GL thread (usually already cached), then hand over to the GL thread
        Thread({
            try {
                val mix = vm.mixBlocking { !playing }
                if (playing) h.post { runPlayback(mix) } else vm.post { vm.onPlayState(false) }
            } catch (t: Throwable) {
                playing = false
                if (t !is InterruptedException) fail("Preview audio", t)
                vm.post { vm.onPlayState(false) }
            }
        }, "preview-mix").start()
    }
    fun stop() { playing = false }

    private fun runPlayback(mix: FloatArray) {
        var track: AudioTrack? = null
        var writer: Thread? = null
        try {
            val e = egl ?: return; val c = comp ?: return
            if (!playing || mix.size < 4) return
            val L = vm.clipLen()
            val minBuf = AudioTrack.getMinBufferSize(AudioMixer.SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            val tr = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(AudioMixer.SR).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(max(minBuf, AudioMixer.SR * 2 * 4 / 8))
                .build()
            track = tr
            val startRel = if (vm.phRel >= L - 0.05) 0.0 else vm.phRel
            val startFrame = (startRel * AudioMixer.SR).toInt().coerceIn(0, mix.size / 2)
            val total = mix.size / 2 - startFrame
            var m = mainTex()
            runCatching { m?.frameAt(vm.stime(startRel)) }
            writer = Thread({
                try {
                    var pos = startFrame * 2
                    while (playing && pos < mix.size) {
                        val n = min(2400 * 2, mix.size - pos)
                        val w = tr.write(mix, pos, n, AudioTrack.WRITE_BLOCKING)
                        if (w <= 0) break
                        pos += w
                    }
                } catch (t: Throwable) { CrashLog.warn("audio writer", t) }
            }, "audio-writer")
            tr.play(); writer.start()
            vm.post { vm.stat = "Playing" }
            var lastUi = 0L; var badSwaps = 0; var stuckSince = 0L; var lastHead = -1
            while (playing) {
                val head = tr.playbackHeadPosition
                val now = System.currentTimeMillis()
                // audio device not moving (e.g. another app grabbed it): do not spin forever
                if (head == lastHead) { if (stuckSince == 0L) stuckSince = now else if (now - stuckSince > 3000) break } else { stuckSince = 0L; lastHead = head }
                val rel = startRel + head.toDouble() / AudioMixer.SR
                if (rel >= L - 0.01 || head >= total - 1) break
                val ws = win ?: break
                if (surfW <= 0 || surfH <= 0) break
                val ts = vm.stime(rel)
                e.makeCurrent(ws)
                val ok = try { m?.frameAt(ts) ?: false } catch (t: Throwable) { dropMain(t); m = mainTex(); false }
                c.render(surfW, surfH, ts, if (ok) m else null)
                if (!e.swap(ws)) { if (++badSwaps > 5) break } else badSwaps = 0 // vsync paced
                if (now - lastUi > 50) { lastUi = now; vm.post { if (playing) vm.phRel = rel } }
            }
            if (playing) vm.post { vm.phRel = 0.0 }
        } catch (t: Throwable) {
            fail("Preview", t)
        } finally {
            playing = false
            // stop the audio first, let the writer leave write(), and only then free the track
            runCatching { track?.pause(); track?.flush() }
            runCatching { writer?.join(800) }
            runCatching { track?.stop() }; runCatching { track?.release() }
            vm.post { vm.onPlayState(false); if (vm.stat == "Playing") vm.stat = "Paused." }
            requestStill()
        }
    }

    /* ---------- export (same compositor, drawn into the encoder) ---------- */
    /**
     * Blocking; call from a background thread. Renders every frame on the GL thread into the
     * hardware encoder with exact timestamps (no real-time pacing needed).
     */
    fun exportVideo(out: File, w: Int, hgt: Int, fps: Int, bitrate: Int, audio: Exporter.EncodedAudio,
                    cancel: () -> Boolean, progress: (Int, Int) -> Unit) {
        stop()
        var err: Throwable? = null
        val done = CountDownLatch(1)
        exporting = true
        h.post {
            var sink: Exporter.VideoSink? = null
            var es: EGLSurface? = null
            var mt: VideoTexture? = null
            var c2: Compositor? = null
            try {
                val e = egl ?: throw IllegalStateException("GPU not ready")
                // free the preview's decoders: the export needs them (phones allow only a few at once)
                runCatching { main?.close() }; main = null; mainUri = null
                comp?.closeVideos()
                val L = vm.clipLen(); val N = max(1, Math.round(L * fps).toInt())
                sink = Exporter.VideoSink(out, w, hgt, fps, bitrate, audio)
                es = e.windowSurface(sink.surface)
                e.makeCurrent(es)
                c2 = Compositor(vm, vm.ctx)
                mt = VideoTexture.open(vm.ctx, vm.srcUri!!)
                for (i in 0 until N) {
                    if (cancel()) throw Exporter.Cancelled()
                    sink.check()
                    val rel = min(i.toDouble() / fps, L - 0.001)
                    val ts = vm.stime(rel)
                    val ok = mt.frameAt(ts)
                    e.makeCurrent(es)
                    c2.render(w, hgt, ts, if (ok) mt else null)
                    e.setPresentationTime(es, i * 1_000_000_000L / fps)
                    e.swap(es)
                    if (i % 3 == 0) progress(i, N)
                }
                e.destroy(es); es = null
                sink.finish(); sink = null
            } catch (t: Throwable) {
                err = t
                if (t !is Exporter.Cancelled) CrashLog.warn("export", t)
                runCatching { es?.let { egl?.destroy(it) } }
                runCatching { sink?.abort() }
            } finally {
                runCatching { mt?.close() }
                runCatching { c2?.release() }
                runCatching { egl?.makeNothingCurrent() }
                exporting = false
                done.countDown()
            }
        }
        done.await()
        requestStill()
        err?.let { throw it }
    }

    fun release() {
        stop()
        h.post {
            runCatching { main?.close() }; runCatching { comp?.release() }
            win?.let { runCatching { egl?.destroy(it) } }
            runCatching { egl?.release() }
        }
        ht.quitSafely()
    }
}
