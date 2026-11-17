package com.jare.autoclipper.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.jare.autoclipper.gl.GlCanvas
import com.jare.autoclipper.gl.Tex
import com.jare.autoclipper.gl.VideoTexture
import kotlin.math.max
import kotlin.math.min

/**
 * Builds one reel frame on the GPU (web: draw()). Used for the live preview AND the export,
 * so both look identical. GL thread only.
 */
class Compositor(private val p: Project, private val ctx: Context) {
    private val gl = GlCanvas()
    private val ov = Overlay(p)
    private var staticTex: Tex? = null
    private var staticKey = ""
    private val capTex = object : LinkedHashMap<String, Tex>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Tex>?): Boolean {
            if (size > 24) { e?.value?.delete(); return true }
            return false
        }
    }
    private val brVideo = HashMap<Int, VideoTexture>()
    private val brFailed = HashSet<Int>() // clips this phone could not open: skipped instead of retried every frame
    private val brTex = HashMap<Int, Tex>()
    private val gifBmp = HashMap<Int, Bitmap>()
    private var maskTex: Tex? = null
    private var maskId = "none"

    /** GPU texture of the chosen frame shape (re-uploaded only when the frame changes) */
    private fun frameMask(): Tex? {
        val id = p.s.frameId
        if (id == "none") return null
        if (id != maskId || maskTex == null) {
            val bmp = try { Frames.mask(ctx, id) } catch (t: Throwable) { null } ?: return null
            val t = maskTex ?: Tex.create().also { maskTex = it }
            t.upload(bmp); maskId = id
        }
        return maskTex
    }

    /**
     * @param ct source time of the main video
     * @param main main video texture (already advanced to ct) or null
     */
    fun render(surfW: Int, surfH: Int, ct: Double, main: VideoTexture?) {
        gl.begin(surfW, surfH)
        val k = gl.scale
        val F = ov.layout()
        if (!F.full) {
            val key = ov.staticKey(F, k)
            if (key != staticKey || staticTex == null) {
                val bmp = Bitmap.createBitmap(max(1, (Overlay.W * k).toInt()), max(1, (Overlay.H * k).toInt()), Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp); c.scale(k, k); ov.paintStatic(c, F)
                val t = staticTex ?: Tex.create().also { staticTex = it }
                t.upload(bmp); bmp.recycle(); staticKey = key
            }
            if (!p.hasVideo) gl.fill(0xFF161616.toInt(), F.x, F.y, F.w, F.h)
            gl.drawTex(staticTex!!, 0f, 0f, Overlay.W, Overlay.H)
        }
        if (!p.hasVideo) return
        val rel = p.otime(ct)
        val b = p.broll.firstOrNull { it.dur > 0 && rel >= it.start && rel < it.start + it.dur }
        gl.clip(F.x, F.y, F.w, F.h)
        frameMask()?.let { gl.setMask(it, F.x, F.y, F.w, F.h) }
        var drew = false
        if (b != null && b.asset.id !in brFailed) {
            try { drew = drawBroll(b, rel - b.start, F) }
            catch (t: Throwable) {
                brFailed.add(b.asset.id); brVideo.remove(b.asset.id)?.let { v -> runCatching { v.close() } }
                com.jare.autoclipper.CrashLog.warn("b-roll ${b.asset.name}", t)
            }
        }
        val fs = p.faces?.takeIf { p.s.faceMode != "off" }
        if (!drew && main != null && fs != null && p.s.faceMode == "split" && fs.person(p.s.facePerson) != null && fs.person(p.s.faceB) != null) {
            drawSplit(main, fs, ct, F)
        } else if (!drew && main != null) {
            val s = p.s
            val dw = main.dispW.toFloat(); val dh = main.dispH.toFloat()
            val kk = max(F.w / dw, F.h / dh)
            var cxv = s.cx.toFloat()
            // follow the chosen face: pan so it sits in the middle of the frame
            // follow the chosen face, or whoever is talking right now
            val who = when (s.faceMode) { "person" -> s.facePerson; "speaker" -> fs?.speakerAt(ct); else -> null }
            if (fs != null && who != null) fs.cam(who, s.faceSmooth, ct)?.let { c ->
                val w = dw * kk
                if (w - F.w > 1f) cxv = ((c[0] * w - F.w / 2) / (w - F.w)).coerceIn(0f, 1f)
            }
            var x = F.x + (F.w - dw * kk) * cxv
            var y = F.y + (F.h - dh * kk) * (0.5f - s.cy.toFloat() / 2)
            var w = dw * kk; var h = dh * kk
            val z = ov.zoom(rel)
            if (z != 1f) { val mx = F.x + F.w / 2; val my = F.y + F.h / 2; x = mx + (x - mx) * z; y = my + (y - my) * z; w *= z; h *= z }
            gl.drawOes(main.tex, main.texMatrix, x, y, w, h)
        }
        // overlay effects (flash, burn, leak …) on top of the picture, inside the frame shape
        for (o in p.overlays) {
            val lp = (rel - o.t) / o.dur
            if (lp < 0 || lp >= 1) continue
            val kd = Fx.byId(o.kind) ?: continue
            gl.drawFx(kd.code, lp.toFloat(), (rel - o.t).toFloat(), o.amt.toFloat(), ((o.id * 37) % 101) / 101f, F.x, F.y, F.w, F.h)
        }
        gl.noClip(); gl.clearMask()
        ov.caption(ct, F, k)?.let { cap ->
            val t = capTex[cap.key] ?: run { val bmp = cap.paint(); val nt = Tex.create(bmp); bmp.recycle(); capTex[cap.key] = nt; nt }
            val w = cap.w * cap.scale; val h = cap.h * cap.scale
            gl.drawTex(t, Overlay.W / 2 - w / 2, cap.cy - h / 2, w, h, cap.alpha)
        }
    }

    /** two people: the first in the top half, the second in the bottom half, each kept centred on their face */
    private fun drawSplit(main: VideoTexture, fs: FaceScan, ct: Double, F: Overlay.Frame) {
        val s = p.s
        val dw = main.dispW.toFloat(); val dh = main.dispH.toFloat()
        val hh = F.h / 2
        listOf(s.facePerson, s.faceB).forEachIndexed { k, id ->
            val c = fs.cam(id, s.faceSmooth, ct) ?: return@forEachIndexed
            val top = F.y + k * hh
            // zoom so the face fills a good part of the half, never smaller than "cover"
            val coverK = max(F.w / dw, hh / dh)
            val faceK = if (c[2] > 0.01f) (hh * 0.42f) / (c[2] * dh) else coverK
            val kk = max(coverK, min(faceK, coverK * 2.2f))
            val w = dw * kk; val h = dh * kk
            val x = (F.x + F.w / 2 - c[0] * w).coerceIn(F.x + F.w - w, F.x)
            val y = (top + hh * 0.45f - c[1] * h).coerceIn(top + hh - h, top)
            gl.clip(F.x, top, F.w, hh)
            gl.drawOes(main.tex, main.texMatrix, x, y, w, h)
        }
        gl.clip(F.x, F.y, F.w, F.h)
        gl.fill(0xFF000000.toInt(), F.x, F.y + hh - 3f, F.w, 6f)
    }

    private fun cover(dw: Float, dh: Float, F: Overlay.Frame): FloatArray {
        val k = max(F.w / dw, F.h / dh)
        return floatArrayOf(F.x + (F.w - dw * k) / 2, F.y + (F.h - dh * k) / 2, dw * k, dh * k)
    }

    @Suppress("DEPRECATION")
    private fun drawBroll(it: BrollItem, local: Double, F: Overlay.Frame): Boolean {
        val a = it.asset
        when (a.type) {
            "video" -> {
                var vt = brVideo[a.id]
                if (vt == null) {
                    // one B-roll decoder at a time: phones only allow a few hardware decoders
                    brVideo.values.forEach { v -> runCatching { v.close() } }; brVideo.clear()
                    vt = VideoTexture.open(ctx, a.uri); brVideo[a.id] = vt
                }
                val t = if (vt.duration > 0) local % vt.duration else local
                if (!vt.frameAt(t)) return false
                val r = cover(vt.dispW.toFloat(), vt.dispH.toFloat(), F)
                gl.drawOes(vt.tex, vt.texMatrix, r[0], r[1], r[2], r[3])
            }
            "gif" -> {
                val m = a.movie ?: return false
                val bmp = gifBmp.getOrPut(a.id) { Bitmap.createBitmap(max(1, m.width()), max(1, m.height()), Bitmap.Config.ARGB_8888) }
                bmp.eraseColor(0)
                m.setTime(((local * 1000).toLong() % max(1, m.duration())).toInt())
                m.draw(Canvas(bmp), 0f, 0f)
                val t = brTex.getOrPut(a.id) { Tex.create() }; t.upload(bmp)
                val r = cover(bmp.width.toFloat(), bmp.height.toFloat(), F)
                gl.drawTex(t, r[0], r[1], r[2], r[3])
            }
            else -> {
                val still = a.still ?: return false
                val t = brTex.getOrPut(a.id) { Tex.create(still) }
                val r = cover(still.width.toFloat(), still.height.toFloat(), F)
                gl.drawTex(t, r[0], r[1], r[2], r[3])
            }
        }
        return true
    }

    /** close every B-roll decoder (before an export, or when memory is short) */
    fun closeVideos() { brVideo.values.forEach { runCatching { it.close() } }; brVideo.clear(); brFailed.clear() }

    /** drop B-roll decoders that are no longer on the timeline */
    fun trimBroll() {
        val live = p.broll.map { it.asset.id }.toSet()
        brVideo.keys.filter { it !in live }.forEach { k -> brVideo.remove(k)?.let { v -> runCatching { v.close() } } }
    }

    fun release() {
        staticTex?.delete(); staticTex = null
        maskTex?.delete(); maskTex = null; maskId = "none"
        capTex.values.forEach { it.delete() }; capTex.clear()
        brVideo.values.forEach { runCatching { it.close() } }; brVideo.clear()
        brTex.values.forEach { it.delete() }; brTex.clear()
        gifBmp.clear()
    }
}
