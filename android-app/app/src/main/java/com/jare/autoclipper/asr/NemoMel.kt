package com.jare.autoclipper.asr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * NeMo log-mel front end for Parakeet (128 mel bins), matching onnx-asr's NemoPreprocessor:
 * pre-emphasis 0.97, 512-pt FFT, 400-sample symmetric Hann window (centred), 160 hop,
 * zero "center" padding, power spectrum, slaney mel bank, log(x + 2^-24), per-feature normalisation.
 */
class NemoMel(private val fb: FloatArray /* [257][128] row-major */) {
    private val nFft = 512; private val win = 400; private val hop = 160; private val nMel = 128; private val nBin = 257
    private val window = FloatArray(nFft).also { w ->
        val off = (nFft - win) / 2
        for (n in 0 until win) w[off + n] = (0.5 - 0.5 * cos(2 * PI * n / (win - 1))).toFloat()
    }
    private val cosT = DoubleArray(nFft / 2) { cos(-2 * PI * it / nFft) }
    private val sinT = DoubleArray(nFft / 2) { sin(-2 * PI * it / nFft) }
    private val rev = IntArray(nFft).also { r ->
        val bits = 9
        for (i in 0 until nFft) { var x = i; var y = 0; repeat(bits) { y = (y shl 1) or (x and 1); x = x shr 1 }; r[i] = y }
    }

    class Features(val data: FloatArray /* [128][frames] */, val frames: Int, val validFrames: Int)

    fun compute(x: FloatArray, start: Int, len: Int): Features {
        // pre-emphasis
        val y = FloatArray(len)
        for (i in 0 until len) y[i] = x[start + i] - (if (i > 0) 0.97f * x[start + i - 1] else 0f)
        val frames = len / hop + 1
        val valid = len / hop
        val out = FloatArray(nMel * frames)
        val re = DoubleArray(nFft); val im = DoubleArray(nFft); val pw = DoubleArray(nBin)
        val guard = Math.pow(2.0, -24.0)
        for (f in 0 until frames) {
            val s0 = f * hop - nFft / 2
            for (j in 0 until nFft) {
                val k = s0 + j
                val v = if (k in 0 until len) y[k] * window[j] else 0f
                val r = rev[j]; re[r] = v.toDouble(); im[r] = 0.0
            }
            fft(re, im)
            for (k in 0 until nBin) pw[k] = re[k] * re[k] + im[k] * im[k]
            for (m in 0 until nMel) {
                var s = 0.0
                for (k in 0 until nBin) s += pw[k] * fb[k * nMel + m]
                out[m * frames + f] = ln(s + guard).toFloat()
            }
        }
        // per-feature normalisation over the valid frames
        val n = valid.coerceAtLeast(1)
        for (m in 0 until nMel) {
            val base = m * frames
            var mean = 0.0
            for (t in 0 until valid) mean += out[base + t]
            mean /= n
            var v = 0.0
            for (t in 0 until valid) { val d = out[base + t] - mean; v += d * d }
            v /= (n - 1).coerceAtLeast(1)
            val sd = sqrt(v) + 1e-5
            for (t in 0 until frames) out[base + t] = if (t < valid) ((out[base + t] - mean) / sd).toFloat() else 0f
        }
        return Features(out, frames, valid)
    }

    /** in-place iterative radix-2 FFT on bit-reversed input */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        var size = 2
        while (size <= nFft) {
            val half = size / 2; val step = nFft / size
            var i = 0
            while (i < nFft) {
                for (j in 0 until half) {
                    val wr = cosT[j * step]; val wi = sinT[j * step]
                    val a = i + j; val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
                i += size
            }
            size *= 2
        }
    }
}
