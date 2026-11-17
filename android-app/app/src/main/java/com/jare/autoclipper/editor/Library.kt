package com.jare.autoclipper.editor

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.jare.autoclipper.App
import com.jare.autoclipper.Prefs
import com.jare.autoclipper.media.AudioDecoder
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One audio track from the "bgm", "best hook" or "transitionsfx" folders, analysed like the web editor does. */
class Track(val name: String, val uri: Uri, val dur: Double, val e: FloatArray, val hop: Double) {
    var sc = 0.0; var off = 0.0; var n = 1.0    // music
    var pk = 0.0; var hs = 0.0; var ts = 0.0    // sfx
}

/** a sound on the timeline. manual = added or edited by the user (auto runs keep it); brId = the B-roll clip a transition belongs to */
data class SfxPlacement(val s: Track, val t: Double, val off: Double, val hit: Double, val v: Double, val dur: Double, val tr: Boolean = false,
                        val manual: Boolean = false, val brId: Int = -1)

class LibListing(val bgm: List<BrFile>, val sfx: List<BrFile>, val trans: List<BrFile>, val broll: List<BrFile>, val brollFolder: String)

/**
 * Where the music / SFX / B-roll come from. Same folders as the website:
 * bgm/, best hook/, transitionsfx/, broll/.
 *  - r2    : the pack downloaded with the model (default, fast)
 *  - local : a folder on the phone picked once
 * Remote files are downloaded ONCE into app storage and reused afterwards.
 */
object Library {
    private val AUD = Regex("\\.(mp3|wav|ogg|m4a|aac|flac|opus)$", RegexOption.IGNORE_CASE)
    private val TRN = Regex("\\.(mp3|wav|ogg|m4a|aac|flac|opus|mp4|mov|webm|m4v)$", RegexOption.IGNORE_CASE)
    private val VIS = Regex("\\.(mp4|mov|webm|m4v|gif|png|jpe?g|webp)$", RegexOption.IGNORE_CASE)
    private val http by lazy { OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }

    private var cache: Pair<Long, LibListing>? = null

    private fun norm(n: String) = n.lowercase().filter { it.isLetterOrDigit() }
    private fun isBroll(n: String) = norm(n) in setOf("broll", "brolls")
    private fun isTrans(n: String) = Regex("^trans[a-z]*sfx$").matches(norm(n))

    fun invalidate() { cache = null }

    fun list(ctx: Context, maxAgeMs: Long = 60_000): LibListing {
        cache?.let { if (System.currentTimeMillis() - it.first < maxAgeMs) return it.second }
        val p = App.prefs
        val l = when (p.libSource) {
            "local" -> listLocal(ctx, p)
            else -> listPack(ctx) // R2 pack (default); GitHub/website sources were removed (too slow)
        }
        cache = System.currentTimeMillis() to l
        return l
    }

    /* ---------- R2 pack already on the phone (instant) ---------- */
    private fun listPack(ctx: Context): LibListing {
        val m = com.jare.autoclipper.model.AssetPack.manifest(ctx)
            ?: throw IOException("Music / SFX / B-roll pack not downloaded yet. Open Settings → Model & assets and press Download.")
        fun of(kind: String) = m.filter { it.kind == kind }.map { e ->
            val f = com.jare.autoclipper.model.AssetPack.file(ctx, e)
            BrFile(f.name, Uri.fromFile(f).toString(), f.length(), e.sha256)
        }.sortedBy { it.name.lowercase() }
        return LibListing(of("bgm"), of("sfx"), of("trans"), of("broll"), "broll")
    }

    /* ---------- folder on the phone ---------- */
    private fun listLocal(ctx: Context, p: Prefs): LibListing {
        val tree = p.localTree.takeIf { it.isNotBlank() } ?: throw IOException("Pick your project folder in Settings (Library source)")
        val root = DocumentFile.fromTreeUri(ctx, Uri.parse(tree)) ?: throw IOException("Folder not accessible, pick it again")
        val kids = root.listFiles().filter { it.isDirectory }
        fun walk(d: DocumentFile?, re: Regex, depth: Int, out: MutableList<BrFile>) {
            d ?: return
            for (f in d.listFiles()) {
                val n = f.name ?: continue
                if (f.isDirectory) { if (depth < 2) walk(f, re, depth + 1, out) }
                else if (re.containsMatchIn(n)) out.add(BrFile(n, f.uri.toString(), f.length(), f.lastModified().toString()))
            }
        }
        val bgm = ArrayList<BrFile>(); val sfx = ArrayList<BrFile>(); val trans = ArrayList<BrFile>(); val br = ArrayList<BrFile>()
        walk(kids.find { it.name.equals("bgm", true) }, AUD, 0, bgm)
        walk(kids.find { it.name.equals("best hook", true) }, AUD, 0, sfx)
        walk(kids.find { isTrans(it.name ?: "") }, TRN, 0, trans)
        val bd = kids.find { isBroll(it.name ?: "") }
        walk(bd, VIS, 0, br)
        return LibListing(bgm.sortedBy { it.name.lowercase() }, sfx.sortedBy { it.name.lowercase() }, trans.sortedBy { it.name.lowercase() }, br.sortedBy { it.name.lowercase() }, bd?.name ?: "broll")
    }

    /* ---------- download once, then reuse ---------- */
    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Returns a local Uri for f, downloading it into app storage the first time. */
    fun resolve(ctx: Context, f: BrFile): Uri {
        if (!f.url.startsWith("http")) return Uri.parse(f.url)
        val dir = File(ctx.filesDir, "library").apply { mkdirs() }
        val ext = f.name.substringAfterLast('.', "bin")
        val out = File(dir, sha1(f.url + "|" + f.version) + "." + ext)
        if (out.exists() && out.length() > 0 && (f.size <= 0 || out.length() == f.size)) return Uri.fromFile(out)
        val tmp = File(dir, out.name + ".part")
        val rb = Request.Builder().url(f.url)
        http.newCall(rb.build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException(f.name + ": HTTP " + r.code)
            r.body!!.byteStream().use { inp -> tmp.outputStream().use { inp.copyTo(it, 1 shl 16) } }
        }
        if (!tmp.renameTo(out)) { tmp.copyTo(out, true); tmp.delete() }
        return Uri.fromFile(out)
    }

    /* ---------- analysis (energy envelope, cached on disk) ---------- */
    fun analyse(ctx: Context, f: BrFile, kind: Char): Track {
        val uri = resolve(ctx, f)
        val key = sha1(f.url + "|" + f.version + "|env1")
        val cf = File(File(ctx.filesDir, "libcache").apply { mkdirs() }, "$key.env")
        var dur: Double; var e: FloatArray
        val hop = 0.05
        if (cf.exists()) {
            DataInputStream(cf.inputStream().buffered()).use { s -> dur = s.readDouble(); val n = s.readInt(); e = FloatArray(n) { s.readFloat() } }
        } else {
            val env = AudioDecoder.envelope(ctx, uri, hop) // fast: no resampling
            dur = env.first; e = env.second
            DataOutputStream(cf.outputStream().buffered()).use { s -> s.writeDouble(dur); s.writeInt(e.size); e.forEach { s.writeFloat(it) } }
        }
        val p = Track(f.name, uri, dur, e, hop)
        if (kind != 'b') {
            var mx = 0f; var mi = 0; var sm = 0.0
            e.forEachIndexed { i, v -> sm += v; if (v > mx) { mx = v; mi = i } }
            val n = max(1, e.size)
            p.pk = mi * hop
            p.hs = (if (Regex("hook|hit|impact|boom|bass|drop|slam|punch|cinematic|riser|stinger", RegexOption.IGNORE_CASE).containsMatchIn(f.name)) 2.0 else 0.0) +
                min(3.0, mx / (sm / n + 1e-6)) / 3 + (if (dur >= 0.4 && dur <= 4) 1.0 else 0.0)
            p.ts = (if (Regex("whoosh|woosh|swoosh|swish|transition|swipe|pop", RegexOption.IGNORE_CASE).containsMatchIn(f.name)) 2.0 else 0.0) + (if (dur < 1.6) 1.0 else 0.0)
        }
        return p
    }

    /** Scores a music track for steady energy, no dead air and enough length (web: scoreBgm). */
    fun scoreBgm(p: Track, L: Double) {
        val e = p.e
        if (e.isEmpty()) { p.sc = -9.0; p.off = 0.0; p.n = 1.0; return }
        val w = max(1, min(e.size, floor(L / p.hop).toInt()))
        var best = -9.0; var bi = 0; var bm = 0.05
        var i = 0
        while (i + w <= e.size) {
            var s = 0.0; var q = 0.0; var z = 0
            for (j in i until i + w) s += e[j]
            val m = s / w
            for (j in i until i + w) { val d = e[j] - m; q += d * d; if (e[j] < m * 0.15) z++ }
            val cv = sqrt(q / w) / (m + 1e-6)
            val sc = (1 - min(1.0, cv)) + 0.5 * min(1.0, m / 0.12) - 2.0 * z / w
            if (sc > best) { best = sc; bi = i; bm = m }
            i += 20
        }
        p.sc = best + (if (p.dur >= L) 0.3 else 0.0)
        p.off = bi * p.hop
        p.n = 0.25 / max(bm, 0.01)
    }

    fun pickRand(list: List<Track>, lastName: String?): Track {
        val c = if (list.size > 1) list.filter { it.name != lastName } else list
        return c[(Math.random() * c.size).toInt().coerceIn(0, c.size - 1)]
    }

    /* ---------- decoded stereo 48 kHz buffers for mixing ---------- */
    private var segKey: String? = null
    private var segData: FloatArray? = null
    @Synchronized fun segment48(ctx: Context, t: Track, from: Double, len: Double): FloatArray {
        val key = "${t.uri}|$from|$len"
        segData?.let { if (key == segKey) return it }
        val d = AudioDecoder.decode(ctx, t.uri, from, from + len, 48000, 2).data
        segKey = key; segData = d
        return d
    }

    private val stereo = LinkedHashMap<String, FloatArray>(16, 0.75f, true)
    @Synchronized fun dropDecoded() { stereo.clear(); segKey = null; segData = null }

    @Synchronized fun stereo48(ctx: Context, t: Track): FloatArray {
        stereo[t.uri.toString()]?.let { return it }
        val d = AudioDecoder.decode(ctx, t.uri, outRate = 48000, channels = 2).data
        stereo[t.uri.toString()] = d
        // keep the cache small: long music tracks are big
        var total = stereo.values.sumOf { it.size.toLong() }
        val it = stereo.entries.iterator()
        while (total > 12_000_000L && stereo.size > 1 && it.hasNext()) { val x = it.next(); if (x.value === d) continue; total -= x.value.size; it.remove() }
        return d
    }
}
