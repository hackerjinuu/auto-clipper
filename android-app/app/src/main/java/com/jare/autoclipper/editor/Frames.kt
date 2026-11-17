package com.jare.autoclipper.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.jare.autoclipper.model.AssetPack
import java.io.File
import java.io.InputStream
import kotlin.math.max

/**
 * Video frame shapes (masks). A frame is a picture with a WHITE shape on BLACK:
 * the video shows only inside the white part, with soft, anti-aliased edges.
 *
 * Sources:
 *  - built in   : app assets/frames (squircle, rounded square, retro TV 4:5, wide 16:9)
 *  - R2 pack    : any image in a "frames" folder of your bucket (kind "frame" in assets.json)
 *  - your phone : "Add frame from phone" (saved in the app)
 *
 * Every image is cleaned the same way: cropped to the white area, turned to grayscale,
 * so even full-screen 9:16 pictures with black around the shape work.
 * The shape's width/height becomes the video frame ratio.
 */
object Frames {
    class Item(val id: String, val name: String, val custom: Boolean)

    private val PRESETS = listOf("squircle" to "Squircle", "rounded" to "Rounded", "tv" to "Retro TV", "wide" to "Wide 16:9")
    private val IMG = Regex("\\.(png|jpe?g|webp)$", RegexOption.IGNORE_CASE)
    private val cache = LinkedHashMap<String, Bitmap>(16, 0.75f, true)

    private fun customDir(ctx: Context) = File(ctx.filesDir, "frames").apply { mkdirs() }

    fun list(ctx: Context): List<Item> {
        val out = ArrayList<Item>()
        PRESETS.forEach { (k, n) -> out.add(Item("asset:$k", n, false)) }
        AssetPack.manifest(ctx)?.filter { it.kind == "frame" && IMG.containsMatchIn(it.path) }?.forEach { e ->
            val f = AssetPack.file(ctx, e)
            out.add(Item("file:" + f.absolutePath, f.nameWithoutExtension, false))
        }
        customDir(ctx).listFiles()?.filter { it.isFile && IMG.containsMatchIn(it.name) }?.sortedBy { it.lastModified() }?.forEach { f ->
            out.add(Item("file:" + f.absolutePath, "My frame", true))
        }
        return out
    }

    private fun open(ctx: Context, id: String): InputStream? = when {
        id.startsWith("asset:") -> runCatching { ctx.assets.open("frames/" + id.removePrefix("asset:") + ".png") }.getOrNull()
        id.startsWith("file:") -> File(id.removePrefix("file:")).takeIf { it.exists() }?.inputStream()
        else -> null
    }

    /** cleaned mask (cropped, grayscale), cached. Null for "none" or unreadable files. */
    @Synchronized fun mask(ctx: Context, id: String): Bitmap? {
        if (id == "none" || id.isEmpty()) return null
        cache[id]?.let { if (!it.isRecycled) return it }
        val raw = decode { open(ctx, id) } ?: return null
        val m = clean(raw); raw.recycle()
        if (m != null) {
            cache[id] = m
            while (cache.size > 12) cache.remove(cache.keys.first())
        }
        return m
    }

    /** width / height of the shape (0 if unknown) */
    fun aspect(ctx: Context, id: String): Double = mask(ctx, id)?.let { it.width.toDouble() / it.height } ?: 0.0

    /** decode with a size limit (masks do not need to be huge) */
    private fun decode(src: () -> InputStream?): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = src() ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, o) } // fills o.outWidth/outHeight (returns no bitmap)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= 1200) sample *= 2
        val d = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return src()?.use { BitmapFactory.decodeStream(it, null, d) }
    }

    /** white-on-black (or opaque-on-transparent) picture -> cropped grayscale mask */
    private fun clean(src: Bitmap): Bitmap? {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        val lum = IntArray(w * h)
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) {
            val c = px[y * w + x]
            val a = c ushr 24
            val l = (((c shr 16) and 0xff) * 299 + ((c shr 8) and 0xff) * 587 + (c and 0xff) * 114) / 1000 * a / 255
            lum[y * w + x] = l
            if (l > 128) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
        }
        if (x1 < x0 || y1 < y0) return null
        val cw = x1 - x0 + 1; val ch = y1 - y0 + 1
        if (cw < 8 || ch < 8) return null
        val out = IntArray(cw * ch)
        for (y in 0 until ch) for (x in 0 until cw) {
            // stretch contrast so JPEG noise around the edge disappears
            val l = ((lum[(y + y0) * w + x + x0] - 40) * 255 / 175).coerceIn(0, 255)
            out[y * cw + x] = (0xff shl 24) or (l shl 16) or (l shl 8) or l
        }
        return Bitmap.createBitmap(out, cw, ch, Bitmap.Config.ARGB_8888)
    }

    /** "Add frame from phone": clean the picture and keep it in the app. Returns the new item, or null. */
    fun import(ctx: Context, uri: Uri): Item? {
        val raw = decode { ctx.contentResolver.openInputStream(uri) } ?: return null
        val m = clean(raw); raw.recycle()
        m ?: return null
        val f = File(customDir(ctx), "frame_${System.currentTimeMillis()}.png")
        f.outputStream().use { m.compress(Bitmap.CompressFormat.PNG, 100, it) }
        synchronized(this) { cache["file:" + f.absolutePath] = m }
        return Item("file:" + f.absolutePath, "My frame", true)
    }

    @Synchronized fun delete(id: String) {
        cache.remove(id)
        if (id.startsWith("file:")) {
            val f = File(id.removePrefix("file:"))
            // only frames the user added can be deleted
            if (f.parentFile?.name == "frames" && f.parentFile?.parentFile?.name == "files") f.delete()
        }
    }
}
