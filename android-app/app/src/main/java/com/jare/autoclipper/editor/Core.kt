package com.jare.autoclipper.editor

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Pure editing logic, ported 1:1 from the Auto Clipper web editor (index.html).
 * Times are in seconds. "Source time" = position in the original video,
 * "output time" (rel) = position in the finished clip after silence cuts.
 */

/** A kept piece of the source: source [a,b) placed at output time o. */
data class Range(val a: Double, val b: Double, val o: Double)

/** Transcript segment (mutable s/e because snap() tightens it, like the web version). */
class Seg(var s: Double, var e: Double, val text: String)

/** Word with timing; bl = blurred / bleeped. */
class WT(val t: String, var s: Double, var e: Double, var bl: Boolean = false)

/** Caption cue shown on screen. */
class Cue(var s: Double, var e: Double, val words: List<String>, val wt: List<WT>)

/** Simple interval. */
data class Iv(var s: Double, var e: Double)

/** Loudness envelope of the source audio, 20 ms per step, in dB. */
class Env(val db: FloatArray, val step: Double, val sorted90: Float) {
    @Volatile var thr: Float = sorted90 - 26f
    fun loudAt(t: Double): Boolean {
        val i = max(0, (t / step).roundToInt())
        return i < db.size && db[i] > thr
    }
    val lengthSec: Double get() = db.size * step

    companion object {
        /** Build from 16 kHz mono PCM blocks of 320 samples (20 ms). */
        fun fromDb(db: FloatArray, step: Double): Env {
            val so = db.copyOf(); so.sort()
            val p90 = if (so.isEmpty()) -60f else so[floor(so.size * 0.9).toInt().coerceAtMost(so.size - 1)]
            return Env(db, step, p90)
        }
        fun rmsDb(sumSq: Double, n: Int): Float = (20.0 * log10(sqrt(sumSq / max(1, n)) + 1e-6)).toFloat()
    }
}

object Core {

    /* ---------------- silence cut ---------------- */
    fun makeRanges(db: FloatArray, step: Double, st: Double, want: Double, sens: Double, minGap: Double, durEnd: Double): List<Range>? {
        val i0 = floor(st / step).toInt()
        val iE = min(db.size, floor(min(durEnd, st + want * 6) / step).toInt())
        if (iE - i0 < 5) return null
        val winEnd = min(iE, i0 + ceil(want * 3 / step).toInt())
        if (winEnd <= i0) return null
        val win = db.copyOfRange(i0, winEnd); win.sort()
        val thr = win[min(win.size - 1, floor(win.size * 0.9).toInt())] - sens
        val runs = ArrayList<DoubleArray>()
        var s = -1
        for (i in i0 until iE) {
            val v = db[i] > thr
            if (v && s < 0) s = i
            if (!v && s >= 0) { runs.add(doubleArrayOf(s * step, i * step)); s = -1 }
        }
        if (s >= 0) runs.add(doubleArrayOf(s * step, iE * step))
        val m = ArrayList<DoubleArray>()
        for (r in runs) {
            val l = m.lastOrNull()
            if (l != null && r[0] - l[1] < minGap) l[1] = r[1] else m.add(doubleArrayOf(r[0], r[1]))
        }
        val out = ArrayList<DoubleArray>() // [a,b]
        for (r in m) {
            if (r[1] - r[0] < 0.15) continue
            val a = max(st, r[0] - 0.08); val b = min(durEnd, r[1] + 0.12)
            val l = out.lastOrNull()
            if (l != null && a <= l[1] + 0.02) l[1] = max(l[1], b) else out.add(doubleArrayOf(a, b))
        }
        var tot = 0.0
        val R = ArrayList<Range>()
        for (r in out) {
            val len = r[1] - r[0]
            if (tot + len >= want) { R.add(Range(r[0], r[0] + (want - tot), tot)); tot = want; break }
            R.add(Range(r[0], r[1], tot)); tot += len
        }
        return R.ifEmpty { null }
    }

    fun otime(R: List<Range>, ts: Double): Double {
        for (r in R) { if (ts < r.a) return r.o; if (ts <= r.b) return r.o + ts - r.a }
        val l = R.last(); return l.o + l.b - l.a
    }

    fun stime(R: List<Range>, o: Double): Double {
        for (r in R) { if (o <= r.o + r.b - r.a) return r.a + max(0.0, o - r.o) }
        return R.last().b
    }

    /* ---------------- transcript parsing ---------------- */
    fun tsec(s: String): Double = s.replace(',', '.').split(':').fold(0.0) { a, v -> a * 60 + (v.toDoubleOrNull() ?: 0.0) }

    private val SRT = Regex("""(\d+:\d+(?::\d+)?[.,]\d+)\s*-->\s*(\d+:\d+(?::\d+)?[.,]\d+)[^\n]*\n([\s\S]*?)(?=\n\n|\n*$)""")
    private val TSLINE = Regex("""^\[(\d+:\d+(?::\d+)?)(?:\s*->\s*(\d+:\d+(?::\d+)?))?\]\s*(.*)$""")
    private val SENT = Regex("""[^.!?]+[.!?]*""")

    class Parsed(val segs: List<Seg>, val approx: Boolean)

    /** a = clip start (t0), d = source length covered by the clip. */
    fun parseTr(input: String, a: Double, d: Double): Parsed {
        var txt = input.replace("\r", "")
        txt = txt.replaceFirst(Regex("^WEBVTT.*\n"), "")
        val out = ArrayList<Seg>()
        for (m in SRT.findAll(txt)) {
            val text = m.groupValues[3].replace("\n", " ").replace(Regex("<[^>]+>"), "").trim()
            out.add(Seg(tsec(m.groupValues[1]), tsec(m.groupValues[2]), text))
        }
        if (out.isEmpty()) {
            val lines = txt.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            val ts = ArrayList<Seg>()
            val ends = ArrayList<Double?>()
            for (l in lines) {
                val mm = TSLINE.find(l) ?: continue
                ts.add(Seg(tsec(mm.groupValues[1]), 0.0, mm.groupValues[3]))
                ends.add(mm.groupValues[2].takeIf { it.isNotEmpty() }?.let { tsec(it) })
            }
            if (ts.isNotEmpty()) {
                ts.forEachIndexed { i, x -> x.e = ends[i] ?: if (i + 1 < ts.size) ts[i + 1].s else x.s + 3 }
                return Parsed(ts.filter { it.text.isNotEmpty() }, false)
            }
            val sents = SENT.findAll(txt.replace(Regex("\\s+"), " ")).map { it.value }.toList()
            val tot = sents.joinToString("").length.coerceAtLeast(1)
            var c = a
            for (s in sents) { val dd = d * s.length / tot; out.add(Seg(c, c + dd, s.trim())); c += dd }
            return Parsed(out.filter { it.text.isNotEmpty() }, true)
        }
        return Parsed(out.filter { it.text.isNotEmpty() }, false)
    }

    /** Pull segment edges onto real speech (uses the loudness envelope). */
    fun snap(sg: Seg, E: Env?) {
        if (E == null) return
        var a = sg.s - 0.3
        while (a < sg.s + 0.4 && !E.loudAt(a)) a += E.step
        if (a < sg.s + 0.4) sg.s = a
        var b = sg.e + 0.3
        while (b > sg.e - 0.4 && !E.loudAt(b)) b -= E.step
        if (b > sg.e - 0.4 && b > sg.s + 0.2) sg.e = b
    }

    fun wordTimes(sg: Seg, asrW: List<Iv>, E: Env?): List<WT> {
        val w = sg.text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val inn = asrW.filter { it.s >= sg.s - 0.05 && it.s < sg.e + 0.05 }
        if (inn.isNotEmpty() && inn.size == w.size) return w.mapIndexed { i, t -> WT(t, inn[i].s, inn[i].e) }
        snap(sg, E)
        val wt = w.map { it.length + 2 }
        val tot = wt.sum().coerceAtLeast(1)
        val D = sg.e - sg.s
        var c = sg.s
        return w.mapIndexed { i, t -> val d = D * wt[i] / tot; val r = WT(t, c, c + d); c += d; r }
    }

    /* ---------------- blur / bleep list ---------------- */
    val BL_DEFAULT = listOf("steroids", "hormone", "fuck", "shit", "bitch", "dick", "god damn", "damn", "tren", "trenbolone", "deca", "nandrolone", "anadrol", "dbol", "dianabol", "winstrol", "var", "anavar", "halo", "halotestin", "test", "testosterone", "inject", "injecting", "injection", "shot", "needle", "syringe", "vial", "sarms", "peptides", "growth hormone", "insulin", "death", "die", "killed", "overdose", "drug", "drugs", "injectables", "tits", "prolactin", "caber", "prami", "letro", "viagra", "cialis", "aromatase inhibitor", "serm", "nolva", "clomid", "hcg", "blood", "estradiol", "kidney function", "anxiety", "paranoia", "psychosis", "aggression", "violence", "depression", "suicidal", "suicide", "lethal", "diuretic", "lasix", "aldactone", "spironolactone", "dnp", "thermogenic", "clen", "clenbuterol", "t3", "cytomel", "sterile", "sperm", "testicle")

    fun blCompile(text: String): List<List<Regex>> {
        val raw = text.split(Regex("[\\n,]+")).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        return LinkedHashSet(raw).map { t -> t.split(Regex("\\s+")).map { it.replace(Regex("[^a-z0-9']"), "") }.filter { it.isNotEmpty() } }
            .filter { it.isNotEmpty() }
            .map { a -> a.map { w -> Regex("^" + Regex.escape(w) + "(?:s|es|ed|d|ing|er|ers|in')?$") } }
            .sortedByDescending { it.size }
    }

    private fun blNorm(t: String) = t.lowercase().replace(Regex("[^a-z0-9'\\-]"), "")
    private fun blWord(n: String, re: Regex) = re.matches(n) || n.split('-').any { it.isNotEmpty() && re.matches(it) }

    fun markBlur(wt: List<WT>, on: Boolean, blT: List<List<Regex>>): Int {
        wt.forEach { it.bl = false }
        if (!on || blT.isEmpty()) return 0
        val n = wt.map { blNorm(it.t) }
        var cnt = 0
        for (i in wt.indices) {
            for (t in blT) {
                if (i + t.size > wt.size) continue
                if (t.withIndex().all { (k, re) -> blWord(n[i + k], re) }) {
                    for (k in t.indices) if (!wt[i + k].bl) { wt[i + k].bl = true; cnt++ }
                    break
                }
            }
        }
        return cnt
    }

    /** Every cue lasts at least 0.2s, never overlaps the next one, small gaps are bridged. */
    fun fixCues(cs: MutableList<Cue>) {
        cs.sortBy { it.s }
        for (i in cs.indices) {
            val c = cs[i]; val nx = cs.getOrNull(i + 1)
            if (!(c.e >= c.s + 0.2)) c.e = c.s + 0.2
            if (nx != null) {
                if (nx.s < c.e) {
                    if (nx.s >= c.s + 0.1) c.e = nx.s else { nx.s = c.e; if (nx.e < nx.s + 0.2) nx.e = nx.s + 0.2 }
                } else if (nx.s - c.e < 0.4) c.e = nx.s
            }
        }
    }

    /* ---------------- ASR words -> segments (web: wseg) ---------------- */
    class WSeg(val segs: List<Seg>, val asrW: List<Iv>)

    fun wseg(words: List<Triple<String, Double, Double>>, off: Double): WSeg {
        val W = words.filter { it.first.isNotBlank() }.map { Triple(it.first.trim(), off + it.second, off + it.third) }
        if (W.any { Regex("\\s").containsMatchIn(it.first) }) return WSeg(W.map { Seg(it.second, it.third, it.first) }, emptyList())
        val asrW = W.map { Iv(it.second, it.third) }
        val S = ArrayList<Seg>()
        var curS = 0.0; var curE = 0.0; var text = StringBuilder(); var n = 0; var last = ""; var open = false
        fun flush() { if (open) S.add(Seg(curS, curE, text.toString())) }
        for (w in W) {
            if (!open || n >= 9 || w.second - curE > 0.6 || Regex("[.!?]$").containsMatchIn(last)) {
                flush(); curS = w.second; curE = w.third; text = StringBuilder(); n = 0; last = ""; open = true
            }
            if (text.isNotEmpty()) text.append(' ')
            text.append(w.first); curE = w.third; n++; last = w.first
        }
        flush()
        return WSeg(S, asrW)
    }

    fun fmtSrt(x: Double): String {
        val ms = (x * 1000).roundToInt().coerceAtLeast(0)
        return String.format(java.util.Locale.US, "%02d:%02d:%02d,%03d", ms / 3600000, (ms / 60000) % 60, (ms / 1000) % 60, ms % 1000)
    }

    fun toSrt(segs: List<Seg>) = segs.mapIndexed { i, c -> "${i + 1}\n${fmtSrt(c.s)} --> ${fmtSrt(c.e)}\n${c.text}" }.joinToString("\n\n")

    /* ---------------- hook finder ---------------- */
    private val FILLER = Regex("""\b(hey|hi|hello|what'?s up|welcome|guys|in this video|today|subscribe|like and|before we|let'?s get|let'?s jump|sponsor|check out|my name|testing|mic|one two|okay so|alright|so yeah|um+|uh+)\b""", RegexOption.IGNORE_CASE)
    private val HOOKY = Regex("""\b(stop|never|mistake|secret|truth|why|how|don'?t|dont|nobody|most people|biggest|worst|best|wrong|actually|could be|sabotag\w*|ruin\w*|lying|myth|warning|danger\w*|lose|losing|gain|fast|only|must|need to|every|problem|here'?s)\b""", RegexOption.IGNORE_CASE)
    private val YOU = Regex("""\byou(r|'re)?\b""", RegexOption.IGNORE_CASE)

    fun hookScore(t: String, i: Int): Double {
        var sc = 0.0
        val n = t.split(Regex("\\s+")).size
        if (t.contains('?')) sc += 3
        if (HOOKY.containsMatchIn(t)) sc += 3
        if (YOU.containsMatchIn(t)) sc += 2
        if (t.any { it.isDigit() }) sc += 1
        if (FILLER.containsMatchIn(t)) sc -= 5
        if (n < 4) sc -= 2
        if (n in 6..25) sc += 1
        return sc - i * 0.05
    }

    /* ---------------- zoom ---------------- */
    fun zoomK(mode: String, strengthPct: Double, durSec: Double, wholeClip: Boolean, clipLen: Double, rel: Double): Double {
        if (mode == "none") return 1.0
        val S = strengthPct / 100.0
        val D = if (wholeClip) clipLen else durSec
        var p = min(1.0, max(0.0, rel / D))
        p = 1 - Math.pow(1 - p, 3.0)
        return if (mode == "in") 1 + S * p else 1 + S * (1 - p)
    }
}

/** mulberry32, identical to the web editor's rng() so random picks behave the same way. */
class Mulberry(seed: Int) {
    private var a = seed
    fun next(): Double {
        a += 0x6D2B79F5
        var t = (a xor (a ushr 15)) * (1 or a)
        t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
        return ((t xor (t ushr 14)).toLong() and 0xffffffffL).toDouble() / 4294967296.0
    }
}
