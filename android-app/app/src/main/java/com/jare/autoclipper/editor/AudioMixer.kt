package com.jare.autoclipper.editor

import android.content.Context
import android.net.Uri
import android.util.Log
import com.jare.autoclipper.media.AudioDecoder
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Offline audio mix of the finished clip (web: mixAudio):
 * speech with silence cuts + muted/bleeped words, background music that ducks under speech
 * and fades out, hook SFX and transition SFX. Output: interleaved stereo float, 48 kHz.
 */
object AudioMixer {
    const val SR = 48000

    fun mix(ctx: Context, src: Uri, R: List<Range>, L: Double, blIv: List<Iv>, otime: (Double) -> Double, stime: (Double) -> Double,
            env: Env?, bgm: Track?, bv: Double, sfx: List<SfxPlacement>, cancel: () -> Boolean = { false }): FloatArray {
        val frames = ceil(L * SR).toInt().coerceAtLeast(1)
        val out = FloatArray(frames * 2)

        // ---- speech ----
        try {
            val a0 = R.first().a; val b1 = R.last().b
            val pcm = AudioDecoder.decode(ctx, src, a0, b1 + 0.05, SR, 2, cancel).data
            val pf = pcm.size / 2
            for (iv in blIv) {
                val a = max(0, ((iv.s - a0) * SR).toInt()); val b = min(pf, ceil((iv.e - a0) * SR).toInt())
                for (i in a until b) { pcm[i * 2] = 0f; pcm[i * 2 + 1] = 0f }
            }
            for (r in R) {
                val from = ((r.a - a0) * SR).roundToInt(); val dst = (r.o * SR).roundToInt(); val n = ((r.b - r.a) * SR).roundToInt()
                for (i in 0 until n) {
                    val si = from + i; val di = dst + i
                    if (si < 0 || si >= pf || di >= frames) continue
                    out[di * 2] += pcm[si * 2]; out[di * 2 + 1] += pcm[si * 2 + 1]
                }
            }
        } catch (e: InterruptedException) { throw e } catch (e: Exception) { Log.w("AudioMixer", "speech audio skipped", e) }

        // ---- bleeps: 1 kHz sine at 0.3 with 4 ms ramps ----
        for (iv in blIv) {
            val o1 = otime(iv.s); val o2 = otime(iv.e)
            if (o2 - o1 < 0.02 || o1 >= L) continue
            val a = (o1 * SR).toInt(); val b = min(frames, ceil(o2 * SR).toInt()); val ramp = 0.004 * SR
            for (i in a until b) {
                val k = i - a; val left = b - i
                val g = 0.3 * min(1.0, min(k / ramp, left / ramp))
                val v = (g * sin(2 * PI * 1000 * k / SR)).toFloat()
                out[i * 2] += v; out[i * 2 + 1] += v
            }
        }

        // ---- background music (looped from its best part, ducked under speech, faded out) ----
        if (bgm != null) {
            Library.scoreBgm(bgm, L)
            // decode only the part of the track we use (much faster than the whole song)
            val segOk = true
            val from = if (bgm.dur - bgm.off >= 2.0) bgm.off else 0.0
            val buf = Library.segment48(ctx, bgm, from, minOf(L + 0.3, maxOf(1.0, bgm.dur - from)))
            val bf = buf.size / 2
            if (bf > 0) {
                val base = bgm.n * bv
                var g = 0.0; var ls = -9.0
                val k = 1 - exp(-1.0 / (0.12 * SR))
                val off = if (segOk) 0 else (bgm.off * SR).toInt()
                val block = (0.05 * SR).toInt()
                var target = 0.0
                for (i in 0 until frames) {
                    if (i % block == 0) {
                        val t = i.toDouble() / SR
                        if (env != null && env.loudAt(stime(t))) ls = t
                        target = base * (if (t - ls < 0.4) 0.45 else 1.0) * min(1.0, max(0.0, (L - t) / 1.2))
                        if (cancel()) throw InterruptedException("cancelled")
                    }
                    g += (target - g) * k
                    val si = (off + i) % bf
                    out[i * 2] += (buf[si * 2] * g).toFloat(); out[i * 2 + 1] += (buf[si * 2 + 1] * g).toFloat()
                }
            }
        }

        // ---- hook + transition SFX ----
        for (x in sfx) {
            val buf = try { Library.stereo48(ctx, x.s) } catch (e: Exception) { Log.w("AudioMixer", "sfx skipped", e); continue }
            val bf = buf.size / 2
            val st = (x.t * SR).roundToInt(); val from = (x.off * SR).roundToInt(); val n = (x.dur * SR).roundToInt()
            for (i in 0 until n) {
                val si = from + i; val di = st + i
                if (si >= bf || di >= frames) break
                if (di < 0) continue
                out[di * 2] += (buf[si * 2] * x.v).toFloat(); out[di * 2 + 1] += (buf[si * 2 + 1] * x.v).toFloat()
            }
        }
        return out
    }
}
