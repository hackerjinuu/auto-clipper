package com.jare.autoclipper.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.jare.autoclipper.media.FrameSource
import com.jare.autoclipper.media.MediaUtil
import com.jare.autoclipper.media.VFrame
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min

/** A loaded B-roll file: video, gif or still image. */
class BrAsset(val id: Int, val name: String, val uri: Uri, val type: String, val dur: Double, val w: Int, val h: Int, val rotation: Int) {
    @Volatile var still: Bitmap? = null       // images
    @Volatile var movie: Movie? = null        // gifs

    companion object {
        private val VID = Regex("\\.(mp4|mov|webm|m4v|3gp|mkv)$", RegexOption.IGNORE_CASE)
        private val GIF = Regex("\\.gif$", RegexOption.IGNORE_CASE)

        fun load(ctx: Context, id: Int, name: String, uri: Uri, mime: String? = null): BrAsset? = try {
            val isVid = VID.containsMatchIn(name) || (mime?.startsWith("video/") == true)
            val isGif = GIF.containsMatchIn(name) || mime == "image/gif"
            when {
                isVid -> {
                    val r = MediaMetadataRetriever()
                    try {
                        MediaUtil.setSource(r, ctx, uri)
                        val d = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
                        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                        BrAsset(id, name, uri, "video", d, w, h, rot)
                    } finally { r.release() }
                }
                isGif -> {
                    val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    @Suppress("DEPRECATION")
                    val m = Movie.decodeByteArray(bytes, 0, bytes.size) ?: return null
                    BrAsset(id, name, uri, "gif", 0.0, m.width(), m.height(), 0).also { it.movie = m }
                }
                else -> {
                    val src = ImageDecoder.createSource(ctx.contentResolver, uri)
                    val bmp = ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val s = info.size; val mx = max(s.width, s.height)
                        if (mx > 1920) { val k = 1920f / mx; dec.setTargetSize((s.width * k).toInt(), (s.height * k).toInt()) }
                    }
                    BrAsset(id, name, uri, "image", 0.0, bmp.width, bmp.height, 0).also { it.still = bmp }
                }
            }
        } catch (e: Exception) { android.util.Log.w("BRoll", "skip $name", e); null }
    }
}

/** A B-roll cutaway placed on the timeline (output time). */
data class BrollItem(val id: Int, val asset: BrAsset, val start: Double, val dur: Double, val auto: Boolean)

/** Hands out frames for B-roll assets during preview playback / export. One instance per render session. */
class BrollFrames(private val ctx: Context) : Closeable {
    private val video = HashMap<Int, FrameSource>()
    private val gifBmp = HashMap<Int, Bitmap>()
    private val retr = HashMap<Int, MediaMetadataRetriever>()

    /** Exact frames (used by playback and export). */
    fun frame(it: BrollItem, local: Double): VFrame? {
        val a = it.asset
        return when (a.type) {
            "video" -> {
                val fs = video.getOrPut(a.id) { FrameSource.open(ctx, a.uri) }
                val t = if (a.dur > 0) local % a.dur else local
                fs.frameAt(t)
            }
            "gif" -> gif(a, local)
            else -> a.still?.let { VFrame(it, Rect(0, 0, it.width, it.height), 0) }
        }
    }

    /** Cheap still for the paused preview. */
    fun still(it: BrollItem, local: Double): VFrame? {
        val a = it.asset
        if (a.type != "video") return frame(it, local)
        val r = retr.getOrPut(a.id) { MediaMetadataRetriever().also { m -> MediaUtil.setSource(m, ctx, a.uri) } }
        val t = if (a.dur > 0) local % a.dur else local
        val b = r.getScaledFrameAtTime((t * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1280, 1280) ?: return null
        return VFrame(b, Rect(0, 0, b.width, b.height), 0)
    }

    @Suppress("DEPRECATION")
    private fun gif(a: BrAsset, local: Double): VFrame? {
        val m = a.movie ?: return null
        val bmp = gifBmp.getOrPut(a.id) { Bitmap.createBitmap(max(1, m.width()), max(1, m.height()), Bitmap.Config.ARGB_8888) }
        bmp.eraseColor(0)
        val d = max(1, m.duration())
        m.setTime(((local * 1000).toLong() % d).toInt())
        m.draw(Canvas(bmp), 0f, 0f)
        return VFrame(bmp, Rect(0, 0, bmp.width, bmp.height), 0)
    }

    override fun close() {
        video.values.forEach { runCatching { it.close() } }; video.clear()
        retr.values.forEach { runCatching { it.release() } }; retr.clear()
        gifBmp.clear()
    }
}

/* ---------------- keyword matching: caption word -> clip with that name ---------------- */
class BrFile(val name: String, val url: String, val size: Long, val version: String) {
    @Volatile var asset: BrAsset? = null
}

class BrKey(val key: String, val toks: List<String>, val files: MutableList<BrFile>)
class BrPat(val toks: List<String>, val k: BrKey)
class BrIndex(val keys: LinkedHashMap<String, BrKey>, val pats: List<BrPat>, val missing: List<String>)
class BrRule(val w: List<String>, val t: String)
class BrEvent(val k: BrKey, val w: String, val t: Double)
class BrPlanItem(val f: BrFile, val a: BrAsset, val start: Double, var dur: Double, val k: BrKey, val w: String)

object BrMatch {
    val ALIAS = mapOf(
        "injection" to listOf("needle", "syringe", "pinning"),
        "blood" to listOf("bloodwork", "bleeding", "bloodstream"),
        "heart" to listOf("cardiac", "cardiovascular"),
        "muscle" to listOf("bicep", "biceps", "gains"),
        "fire" to listOf("flame", "flames", "burn", "burning"),
    )

    fun stem(w0: String): String {
        var w = w0.lowercase().replace(Regex("[^a-z0-9]"), "")
        for (x in listOf("ings", "ing", "ions", "ion", "ers", "er", "ed", "es", "s")) {
            if (w.length - x.length >= 4 && w.endsWith(x)) { w = w.dropLast(x.length); break }
        }
        if (w.length >= 4 && w.endsWith("e")) w = w.dropLast(1)
        return w
    }

    /** "blood_01.mp4", "blood1.mp4", "blood-2.mov", "blood (3).gif" -> ["blood"] */
    fun key(name: String): List<String> {
        var b = name.replace(Regex("\\.[^.]+$"), "").lowercase()
        val tail = Regex("[\\s_\\-.]*(?:\\(\\s*\\d+\\s*\\)|\\d+|copy)\\s*$")
        repeat(3) { b = b.replace(tail, "") }
        return b.split(Regex("[\\s_\\-.]+")).filter { it.isNotEmpty() && !Regex("^\\d+$").matches(it) }
    }

    private val numCmp = Comparator<String> { a, b -> naturalCompare(a, b) }
    private fun naturalCompare(a: String, b: String): Int {
        val ra = Regex("\\d+|\\D+").findAll(a).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b).map { it.value }.toList()
        for (i in 0 until min(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) (x.toBigInteger().compareTo(y.toBigInteger())) else x.compareTo(y, true)
            if (c != 0) return c
        }
        return ra.size - rb.size
    }

    fun index(files: List<BrFile>, rules: List<BrRule>): BrIndex {
        val al = HashMap<String, List<String>>(); ALIAS.forEach { (k, v) -> al[stem(k)] = v }
        val keys = LinkedHashMap<String, BrKey>()
        for (f in files) {
            val t = key(f.name); if (t.isEmpty()) continue
            val k = t.joinToString(" ")
            keys.getOrPut(k) { BrKey(k, t.map { stem(it) }, ArrayList()) }.files.add(f)
        }
        keys.values.forEach { K -> K.files.sortWith { a, b -> numCmp.compare(a.name, b.name) } }
        val pats = ArrayList<BrPat>(); val own = HashMap<String, MutableSet<BrKey>>(); val missing = ArrayList<String>()
        fun bare(x: String) = x.replace(Regex("\\.[^.]+$"), "")
        for (r in rules) {
            val t = r.t.trim().lowercase(); if (t.isEmpty()) continue
            val f = files.find { it.name.lowercase() == t || bare(it.name.lowercase()) == t }
            val K: BrKey? = if (f != null) BrKey(bare(f.name), emptyList(), mutableListOf(f))
            else keys[key(t).joinToString(" ")] ?: keys.values.find { it.toks.joinToString(" ") == key(t).map { s -> stem(s) }.joinToString(" ") }
            if (K == null) { missing.add(r.t); continue }
            for (w in r.w) {
                val toks = w.trim().split(Regex("\\s+")).map { stem(it) }.filter { it.isNotEmpty() }
                if (toks.isNotEmpty()) pats.add(BrPat(toks, K))
            }
        }
        for (K in keys.values) {
            pats.add(BrPat(K.toks, K))
            if (K.toks.size == 1) (al[K.toks[0]] ?: emptyList()).forEach { a -> pats.add(BrPat(listOf(stem(a)), K)) }
            K.toks.forEach { t -> own.getOrPut(t) { HashSet() }.add(K) }
        }
        for (K in keys.values) if (K.toks.size > 1) K.toks.forEach { t -> if (t.length >= 4 && own[t]?.size == 1) pats.add(BrPat(listOf(t), K)) }
        val sorted = pats.sortedByDescending { it.toks.size }
        return BrIndex(keys, sorted, missing)
    }

    fun events(idx: BrIndex, W: List<WT>, R: List<Range>, ot: (Double) -> Double): List<BrEvent> {
        val st = W.map { stem(it.t) }
        val ev = ArrayList<BrEvent>()
        var i = 0
        while (i < W.size) {
            if (st[i].isNotEmpty() && R.any { W[i].s >= it.a - 0.01 && W[i].s <= it.b }) {
                for (p in idx.pats) {
                    if (i + p.toks.size > W.size) continue
                    if (p.toks.withIndex().all { (k, t) -> st[i + k] == t }) {
                        ev.add(BrEvent(p.k, W[i].t, ot(W[i].s))); i += p.toks.size - 1; break
                    }
                }
            }
            i++
        }
        return ev
    }

    fun plan(ev0: List<BrEvent>, maxD: Double, L: Double): List<BrPlanItem> {
        val out = ArrayList<BrPlanItem>(); val rr = HashMap<BrKey, Int>(); var prev: BrPlanItem? = null
        val ev = ev0.sortedBy { it.t }
        for (e in ev) {
            val st = max(0.0, e.t - 0.1)
            val p = prev
            if (p != null) {
                if (e.k === p.k && st < p.start + p.dur + 1) continue
                if (st < p.start + p.dur) { val room = st - p.start; if (room >= 0.6) p.dur = room else continue }
            }
            val i = rr[e.k] ?: 0
            val f = e.k.files[i % e.k.files.size]; rr[e.k] = i + 1
            val a = f.asset ?: continue
            val d = minOf(maxD, if (a.type == "video" && a.dur > 0) a.dur else maxD, L - st)
            if (d < 0.3) continue
            val it = BrPlanItem(f, a, st, d, e.k, e.w); out.add(it); prev = it
        }
        return out
    }
}
