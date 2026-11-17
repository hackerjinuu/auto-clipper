package com.jare.autoclipper.model

import android.content.Context
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Your R2 folders (bgm, best hook, transitionsfx, broll), described by assets.json
 * (made by make-r2-manifest.js), mirrored into the app next to the model.
 * Only new or changed files are downloaded; files removed from R2 are removed here too.
 */
object AssetPack {
    class Entry(val path: String, val kind: String, val size: Long, val sha256: String)

    fun dir(ctx: Context) = File(ctx.filesDir, "pack").apply { mkdirs() }
    private fun localManifest(ctx: Context) = File(dir(ctx), "assets.json")

    fun parse(json: String): List<Entry> {
        val a = JSONObject(json).getJSONArray("files")
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Entry(it.getString("path"), it.getString("kind"), it.optLong("size", -1), it.optString("sha256"))
        }
    }

    /** entries that are fully on the phone (null = pack never downloaded) */
    fun manifest(ctx: Context): List<Entry>? {
        val f = localManifest(ctx)
        if (!f.exists()) return null
        return try { parse(f.readText()).filter { File(dir(ctx), it.path).exists() } } catch (e: Exception) { null }
    }

    fun file(ctx: Context, e: Entry) = File(dir(ctx), e.path)
    fun sizeOnDisk(ctx: Context): Long = dir(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp -> val b = ByteArray(1 shl 16); while (true) { val n = inp.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Fetches assets.json; returns null when the bucket has none (assets are optional). */
    fun remote(http: OkHttpClient, base: String): Pair<String, List<Entry>>? {
        http.newCall(Request.Builder().url("$base/assets.json").header("Cache-Control", "no-cache").build()).execute().use { r ->
            if (r.code == 404 || r.code == 403) return null
            if (!r.isSuccessful) throw IOException("assets.json: HTTP ${r.code}")
            val txt = r.body!!.string()
            return txt to parse(txt)
        }
    }

    /** which entries still need downloading */
    fun missing(ctx: Context, list: List<Entry>): List<Entry> {
        val known = try { localManifest(ctx).takeIf { it.exists() }?.let { parse(it.readText()).associateBy { e -> e.path } } } catch (e: Exception) { null } ?: emptyMap()
        return list.filter { e ->
            val f = file(ctx, e)
            !(f.exists() && (e.size < 0 || f.length() == e.size) &&
                (known[e.path]?.sha256 == e.sha256 || (e.sha256.isNotEmpty() && sha256(f) == e.sha256)))
        }
    }

    fun url(base: String, path: String) = base + "/" + path.split('/').joinToString("/") { Uri.encode(it) }

    /** resumable download of one entry; onBytes gets the number of new bytes written */
    fun download(ctx: Context, http: OkHttpClient, base: String, e: Entry, stopped: () -> Boolean, onBytes: (Long) -> Unit) {
        val target = file(ctx, e); target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        var have = if (part.exists()) part.length() else 0L
        if (e.size in 1 until have) { part.delete(); have = 0 }
        val rb = Request.Builder().url(url(base, e.path))
        if (have > 0) rb.header("Range", "bytes=$have-")
        http.newCall(rb.build()).execute().use { r ->
            if (!(r.code == 416 && have == e.size)) {
                if (!r.isSuccessful) throw IOException("${e.path}: HTTP ${r.code}")
                if (have > 0 && r.code != 206) have = 0
                RandomAccessFile(part, "rw").use { raf ->
                    raf.setLength(have); raf.seek(have)
                    val inp = r.body!!.byteStream(); val buf = ByteArray(1 shl 16)
                    while (true) {
                        if (stopped()) throw IOException("stopped")
                        val n = inp.read(buf); if (n < 0) break
                        raf.write(buf, 0, n); onBytes(n.toLong())
                    }
                }
            }
        }
        if (e.sha256.isNotEmpty() && sha256(part) != e.sha256) { part.delete(); throw IOException("${e.path}: checksum mismatch, will retry") }
        target.delete()
        if (!part.renameTo(target)) throw IOException("Could not save ${e.path}")
    }

    /** after a successful sync: store manifest, delete files R2 no longer has */
    fun commit(ctx: Context, json: String, list: List<Entry>) {
        localManifest(ctx).writeText(json)
        val keep = list.map { file(ctx, it).canonicalPath }.toSet() + localManifest(ctx).canonicalPath
        dir(ctx).walkBottomUp().forEach { f ->
            if (f.isFile && f.canonicalPath !in keep && !f.name.endsWith(".part")) f.delete()
            else if (f.isDirectory && f != dir(ctx) && (f.listFiles()?.isEmpty() == true)) f.delete()
        }
    }
}
