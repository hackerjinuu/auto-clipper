package com.jare.autoclipper.asr

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import com.jare.autoclipper.model.ModelManager
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

/**
 * NVIDIA Parakeet TDT 0.6B v2 (INT8 ONNX, the same files your website loads from R2),
 * running natively with ONNX Runtime from the copy stored inside the app.
 * The session is created once and kept in memory, so later transcriptions start instantly.
 */
class ParakeetEngine private constructor(ctx: Context, dir: File) : Closeable {

    data class Word(val text: String, val s: Double, val e: Double)

    companion object {
        private const val TAG = "Parakeet"
        private const val SR = 16000
        private const val FRAME_SEC = 0.08 // 10 ms hop x 8 subsampling
        @Volatile private var inst: ParakeetEngine? = null

        val isLoaded get() = inst != null

        /** Loads (once) from the downloaded model folder. Heavy: call off the main thread. */
        @Synchronized fun get(ctx: Context, note: (String) -> Unit = {}): ParakeetEngine {
            inst?.let { return it }
            val mm = ModelManager(ctx)
            if (!mm.isReady()) throw IOException("The Parakeet model is not downloaded yet. Open Settings → Model and download it once.")
            note("Loading Parakeet from phone storage…")
            val t0 = System.currentTimeMillis()
            val e = ParakeetEngine(ctx.applicationContext, mm.dir)
            Log.i(TAG, "loaded in ${System.currentTimeMillis() - t0} ms")
            inst = e
            return e
        }

        private val useLock = java.util.concurrent.locks.ReentrantLock()

        /** Frees the model (about 1 GB of RAM). Skipped while a transcription is running. Returns true if freed. */
        fun unload(): Boolean {
            if (!useLock.tryLock()) return false
            try { val e = inst ?: return false; inst = null; e.close(); return true } finally { useLock.unlock() }
        }

        /** load if needed + transcribe, safe against unload() */
        fun transcribeSafe(ctx: Context, pcm: FloatArray, progress: (Float) -> Unit, cancel: () -> Boolean = { false }): List<Word> {
            useLock.lock()
            try { return get(ctx).transcribe(pcm, progress, cancel) } finally { useLock.unlock() }
        }
    }

    private val env = OrtEnvironment.getEnvironment()
    private val encoder: OrtSession
    private val decoder: OrtSession
    private val vocab: Array<String>
    private val blank: Int
    private val vocabSize: Int
    private val mel: NemoMel
    private val stateShape1: LongArray
    private val stateShape2: LongArray
    private val targetsInt64: Boolean
    private val targetLenInt64: Boolean

    init {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        // Low-memory settings: phones kill the app when RAM runs out while the 650 MB model loads.
        //  - no "pre-packed" second copy of the weights
        //  - basic graph optimisation only (full optimisation makes extra copies while loading)
        //  - no memory arena / pattern planning (they keep big buffers around between runs)
        fun opts() = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            runCatching { addConfigEntry("session.disable_prepacking", "1") }
            runCatching { setCPUArenaAllocator(false) }
            runCatching { setMemoryPatternOptimization(false) }
        }
        // the old optimised copy (made by earlier versions) needed a lot of RAM to create: remove it
        File(dir, "encoder.optimized.onnx").delete()
        encoder = env.createSession(File(dir, ModelManager.ENCODER).absolutePath, opts())
        decoder = env.createSession(File(dir, ModelManager.DECODER).absolutePath, opts())

        val lines = File(dir, ModelManager.VOCAB).readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        val map = HashMap<Int, String>()
        for (l in lines) { val i = l.lastIndexOf(' '); if (i > 0) map[l.substring(i + 1).trim().toInt()] = l.substring(0, i) }
        vocabSize = map.size
        vocab = Array(vocabSize) { map[it] ?: "" }
        blank = map.entries.firstOrNull { it.value == "<blk>" }?.key ?: (vocabSize - 1)

        val fbBytes = ctx.assets.open("nemo128_fbanks.bin").use { it.readBytes() }
        val fb = FloatArray(fbBytes.size / 4)
        ByteBuffer.wrap(fbBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(fb)
        mel = NemoMel(fb)

        val inf = decoder.inputInfo
        fun shape(n: String): LongArray { val s = (inf[n]!!.info as TensorInfo).shape.copyOf(); for (i in s.indices) if (s[i] < 0) s[i] = 1; return s }
        stateShape1 = shape("input_states_1"); stateShape2 = shape("input_states_2")
        targetsInt64 = (inf["targets"]!!.info as TensorInfo).type == OnnxJavaType.INT64
        targetLenInt64 = (inf["target_length"]!!.info as TensorInfo).type == OnnxJavaType.INT64
    }

    /**
     * Transcribe 16 kHz mono PCM. Long audio is split at the quietest moment
     * (every 20-30 s) so memory stays low and speed stays high on phones.
     */
    fun transcribe(pcm: FloatArray, progress: (Float) -> Unit = {}, cancel: () -> Boolean = { false }): List<Word> {
        val chunks = ArrayList<IntArray>()
        val maxC = 30 * SR; val minC = 20 * SR
        var s = 0
        val n = pcm.size
        while (s < n) {
            if (n - s <= maxC) { chunks.add(intArrayOf(s, n)); break }
            var best = s + maxC; var bestE = Double.MAX_VALUE
            var i = s + minC
            while (i + 320 <= s + maxC) {
                var e = 0.0; for (j in i until i + 320) e += pcm[j] * pcm[j]
                if (e < bestE) { bestE = e; best = i + 160 }
                i += 160
            }
            chunks.add(intArrayOf(s, best)); s = best
        }
        val words = ArrayList<Word>()
        for ((ci, c) in chunks.withIndex()) {
            if (cancel()) throw InterruptedException("cancelled")
            if (c[1] - c[0] < SR / 10) continue
            words.addAll(chunk(pcm, c[0], c[1] - c[0], c[0].toDouble() / SR))
            progress((ci + 1f) / chunks.size)
        }
        return words
    }

    private fun chunk(pcm: FloatArray, start: Int, len: Int, offset: Double): List<Word> {
        val feats = mel.compute(pcm, start, len)
        class EncOut(val enc: FloatArray, val d: Int, val t: Int, val len: Int)
        val eo: EncOut = OnnxTensor.createTensor(env, FloatBuffer.wrap(feats.data), longArrayOf(1, 128, feats.frames.toLong())).use { x ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(feats.validFrames.toLong())), longArrayOf(1)).use { l ->
                encoder.run(mapOf("audio_signal" to x, "length" to l)).use { r ->
                    val out = r.get("outputs").get() as OnnxTensor
                    val sh = out.info.shape // [1, D, T]
                    val fbuf = out.floatBuffer
                    val arr = FloatArray(fbuf.remaining()); fbuf.get(arr)
                    val lt = r.get("encoded_lengths").get() as OnnxTensor
                    EncOut(arr, sh[1].toInt(), sh[2].toInt(), lt.longBuffer.get(0).toInt().coerceAtMost(sh[2].toInt()))
                }
            }
        }
        val enc = eo.enc; val D = eo.d; val T = eo.t; val encLen = eo.len
        // ---- TDT greedy decoding (same as onnx-asr) ----
        var st1 = FloatArray(stateShape1.fold(1L) { a, b -> a * b }.toInt())
        var st2 = FloatArray(stateShape2.fold(1L) { a, b -> a * b }.toInt())
        val frame = FloatArray(D)
        val toks = ArrayList<Int>(); val tFrames = ArrayList<Int>(); val tSteps = ArrayList<Int>()
        var t = 0; var emitted = 0
        var lastFrame = -1
        while (t < encLen) {
            if (t != lastFrame) { for (d in 0 until D) frame[d] = enc[d * T + t]; lastFrame = t }
            val prev = if (toks.isEmpty()) blank else toks.last()
            val res = step(frame, D, prev, st1, st2)
            val logits = res.logits
            var tok = 0; var bv = Float.NEGATIVE_INFINITY
            for (k in 0 until vocabSize) if (logits[k] > bv) { bv = logits[k]; tok = k }
            var dur = 0; var dv = Float.NEGATIVE_INFINITY
            for (k in vocabSize until logits.size) if (logits[k] > dv) { dv = logits[k]; dur = k - vocabSize }
            if (tok != blank) {
                st1 = res.s1; st2 = res.s2
                toks.add(tok); tFrames.add(t); tSteps.add(dur); emitted++
            }
            if (dur > 0) { t += dur; emitted = 0 }
            else if (tok == blank || emitted == 10) { t += 1; emitted = 0 }
        }
        // ---- tokens -> words with timestamps ----
        class W(val sb: StringBuilder, val f0: Int, var f1: Int)
        val ws = ArrayList<W>()
        var newWord = true
        for (i in toks.indices) {
            val p = vocab[toks[i]]
            if (p.startsWith("<") && p.endsWith(">")) continue
            val starts = p.startsWith("▁")
            val txt = p.replace("▁", "")
            if (starts) newWord = true
            if (txt.isEmpty()) continue
            if (newWord || ws.isEmpty()) { ws.add(W(StringBuilder(txt), tFrames[i], tFrames[i] + maxOf(1, tSteps[i]))); newWord = false }
            else { val w = ws.last(); w.sb.append(txt); w.f1 = tFrames[i] + maxOf(1, tSteps[i]) }
        }
        val out = ArrayList<Word>()
        for (i in ws.indices) {
            val w = ws[i]
            val s = offset + w.f0 * FRAME_SEC
            var e = offset + w.f1 * FRAME_SEC
            if (i + 1 < ws.size) e = minOf(e, offset + ws[i + 1].f0 * FRAME_SEC)
            if (e <= s) e = s + FRAME_SEC
            out.add(Word(w.sb.toString(), s, e))
        }
        return out
    }

    private class StepOut(val logits: FloatArray, val s1: FloatArray, val s2: FloatArray)

    private fun step(frame: FloatArray, D: Int, prevTok: Int, s1: FloatArray, s2: FloatArray): StepOut {
        val tensors = ArrayList<OnnxTensor>()
        try {
            val eo = OnnxTensor.createTensor(env, FloatBuffer.wrap(frame), longArrayOf(1, D.toLong(), 1)).also { tensors.add(it) }
            val tg = (if (targetsInt64) OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(prevTok.toLong())), longArrayOf(1, 1))
                      else OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(prevTok)), longArrayOf(1, 1))).also { tensors.add(it) }
            val tl = (if (targetLenInt64) OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(1L)), longArrayOf(1))
                      else OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(1)), longArrayOf(1))).also { tensors.add(it) }
            val i1 = OnnxTensor.createTensor(env, FloatBuffer.wrap(s1), stateShape1).also { tensors.add(it) }
            val i2 = OnnxTensor.createTensor(env, FloatBuffer.wrap(s2), stateShape2).also { tensors.add(it) }
            decoder.run(mapOf("encoder_outputs" to eo, "targets" to tg, "target_length" to tl, "input_states_1" to i1, "input_states_2" to i2)).use { r ->
                fun arr(name: String): FloatArray { val b = (r.get(name).get() as OnnxTensor).floatBuffer; return FloatArray(b.remaining()).also { b.get(it) } }
                return StepOut(arr("outputs"), arr("output_states_1"), arr("output_states_2"))
            }
        } finally { tensors.forEach { it.close() } }
    }

    override fun close() { runCatching { encoder.close() }; runCatching { decoder.close() } }
}
