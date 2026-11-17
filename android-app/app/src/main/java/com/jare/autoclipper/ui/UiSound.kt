package com.jare.autoclipper.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.jare.autoclipper.App
import java.io.DataOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

enum class Snd { TAP, TOGGLE, OPEN, CLOSE, START, SUCCESS, ERROR }

/**
 * Short, soft interface sounds, synthesised on the phone the first time (no audio files to ship,
 * nothing copyrighted). Played with SoundPool (low latency), quiet "sonification" volume,
 * plus a light haptic tick. Can be switched off in Settings.
 */
object UiSound {
    private const val SR = 44100
    private var pool: SoundPool? = null
    private val ids = java.util.concurrent.ConcurrentHashMap<Snd, Int>()
    private val ready = HashSet<Int>()

    fun init(ctx: Context) {
        if (pool != null) return
        val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()
        p.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(ready) { ready.add(id) } }
        pool = p
        val dir = File(ctx.cacheDir, "uisnd1").apply { mkdirs() }
        Thread({
            for (k in Snd.values()) runCatching {
                val f = File(dir, k.name.lowercase() + ".wav")
                if (!f.exists() || f.length() < 100) writeWav(f, synth(k))
                ids[k] = p.load(f.absolutePath, 1)
            }
        }, "ui-sounds").start()
    }

    fun play(k: Snd) {
        if (!App.prefs.uiSounds) return
        val p = pool ?: return
        val id = ids[k] ?: return
        if (synchronized(ready) { id !in ready }) return
        val vol = when (k) { Snd.TAP, Snd.TOGGLE -> 0.45f; Snd.OPEN, Snd.CLOSE -> 0.4f; else -> 0.6f }
        p.play(id, vol, vol, 1, 0, 1f)
    }

    /* ---------------- synthesis ---------------- */
    private fun env(t: Double, attack: Double, decay: Double) = min(1.0, t / attack) * exp(-t / decay)

    private fun synth(k: Snd): FloatArray {
        fun buf(sec: Double) = FloatArray((sec * SR).toInt())
        val rnd = Random(7)
        return when (k) {
            // soft glassy click
            Snd.TAP -> buf(0.05).also { b -> for (i in b.indices) { val t = i.toDouble() / SR
                b[i] = (0.5 * sin(2 * PI * 2300 * t) * env(t, 0.0008, 0.006) + 0.25 * sin(2 * PI * 4600 * t) * env(t, 0.0005, 0.003)).toFloat() } }
            // two quick ticks, second higher
            Snd.TOGGLE -> buf(0.09).also { b -> for (i in b.indices) { val t = i.toDouble() / SR
                val a = sin(2 * PI * 1700 * t) * env(t, 0.0008, 0.007)
                val t2 = t - 0.035; val c = if (t2 > 0) sin(2 * PI * 2600 * t2) * env(t2, 0.0008, 0.008) else 0.0
                b[i] = (0.45 * a + 0.45 * c).toFloat() } }
            // airy upward whoosh (sheet opens)
            Snd.OPEN, Snd.CLOSE -> buf(0.16).also { b ->
                var lp = 0.0; var ph = 0.0
                val up = k == Snd.OPEN
                for (i in b.indices) { val t = i.toDouble() / SR; val x = t / 0.16
                    val f = if (up) 380 + 700 * x else 950 - 600 * x
                    ph += 2 * PI * f / SR
                    lp += (rnd.nextDouble(-1.0, 1.0) - lp) * (0.08 + 0.25 * (if (up) x else 1 - x))
                    val e = sin(PI * x).let { it * it }
                    b[i] = ((0.55 * lp + 0.22 * sin(ph)) * e).toFloat() } }
            // shimmering rising sweep (AI starts working)
            Snd.START -> buf(0.32).also { b -> var ph = 0.0
                for (i in b.indices) { val t = i.toDouble() / SR; val x = t / 0.32
                    ph += 2 * PI * (520 + 780 * x * x) / SR
                    val e = sin(PI * x)
                    b[i] = ((sin(ph) + 0.35 * sin(2 * ph) + 0.15 * sin(3 * ph + 6 * sin(2 * PI * 9 * t))) * 0.28 * e).toFloat() } }
            // two-note glass chime (done)
            Snd.SUCCESS -> buf(0.6).also { b -> for (i in b.indices) { val t = i.toDouble() / SR
                fun note(f: Double, at: Double): Double { val u = t - at; return if (u < 0) 0.0 else (sin(2 * PI * f * u) + 0.3 * sin(4 * PI * f * u)) * env(u, 0.002, 0.16) }
                b[i] = (0.32 * note(1318.5, 0.0) + 0.32 * note(1975.5, 0.09)).toFloat() } }
            // soft low double pulse (problem)
            Snd.ERROR -> buf(0.3).also { b -> for (i in b.indices) { val t = i.toDouble() / SR
                fun pulse(at: Double): Double { val u = t - at; return if (u < 0 || u > 0.09) 0.0 else (sin(2 * PI * 233 * u) + 0.3 * sin(2 * PI * 699 * u)) * sin(PI * u / 0.09) }
                b[i] = (0.4 * (pulse(0.0) + pulse(0.13))).toFloat() } }
        }
    }

    private fun writeWav(f: File, x: FloatArray) {
        val n = x.size
        DataOutputStream(f.outputStream().buffered()).use { o ->
            fun i32(v: Int) { o.write(v and 0xff); o.write((v shr 8) and 0xff); o.write((v shr 16) and 0xff); o.write((v shr 24) and 0xff) }
            fun i16(v: Int) { o.write(v and 0xff); o.write((v shr 8) and 0xff) }
            o.writeBytes("RIFF"); i32(36 + n * 2); o.writeBytes("WAVE")
            o.writeBytes("fmt "); i32(16); i16(1); i16(1); i32(SR); i32(SR * 2); i16(2); i16(16)
            o.writeBytes("data"); i32(n * 2)
            for (v in x) i16((v.coerceIn(-1f, 1f) * 32767).toInt())
        }
    }
}

/** sound + light haptic tick, for buttons */
@Composable
fun rememberFeedback(): (Snd) -> Unit {
    val view: View = LocalView.current
    return remember(view) {
        { s: Snd ->
            UiSound.play(s)
            if (App.prefs.uiSounds) runCatching {
                view.performHapticFeedback(if (s == Snd.ERROR && android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
    }
}
