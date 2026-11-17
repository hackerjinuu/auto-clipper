package com.jare.autoclipper.editor

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What the renderer needs to know about the edit. Implemented by the EditorViewModel. */
interface Project {
    val s: Settings
    val hasVideo: Boolean
    val cues: List<Cue>
    val broll: List<BrollItem>
    val overlays: List<OverlayItem>
    val faces: FaceScan?
    fun clipLen(): Double
    fun otime(ts: Double): Double
}

/**
 * Text layers for the GPU compositor (web: frameText + caption block of draw()).
 * Text is painted once into bitmaps and reused; the GPU only animates scale/alpha per frame.
 */
class Overlay(private val p: Project) {
    companion object {
        const val W = 1080f
        const val H = 1920f
        private val hookTf: Typeface = Typeface.create(Typeface.SANS_SERIF, 600, false)
        private val handleTf: Typeface = Typeface.create(Typeface.SANS_SERIF, 500, false)
    }

    class Frame(val full: Boolean, val x: Float, val y: Float, val w: Float, val h: Float,
                val lines: List<String> = emptyList(), val hs: Float = 0f, val hl: Float = 1.18f, val top: Float = 0f, val asz: Float = 0f, val ay: Float = 0f)

    /** A caption ready to draw: bitmap + where/how (reel units). */
    class Cap(val key: String, val paint: () -> Bitmap, val w: Float, val h: Float, val cy: Float, val scale: Float, val alpha: Float)

    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    private fun wrapLines(txt: String, maxW: Float): List<String> {
        val words = txt.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val lines = ArrayList<String>(); var cur = ""
        for (w in words) { val t = if (cur.isNotEmpty()) "$cur $w" else w; if (text.measureText(t) > maxW && cur.isNotEmpty()) { lines.add(cur); cur = w } else cur = t }
        if (cur.isNotEmpty()) lines.add(cur)
        return lines
    }

    fun layout(): Frame {
        val s = p.s
        val shaped = s.frameId != "none" && s.frameAspect > 0
        if (s.rt == "full" && !shaped) return Frame(true, 0f, 0f, W, H)
        // a frame shape decides the ratio of the video box
        val r = if (shaped) s.frameAspect.toFloat() else (Settings.FR[s.rt] ?: 1.0).toFloat()
        var bw = W * (s.vw / 100).toFloat(); var bh = bw / r
        if (bh > 1250f) { bh = 1250f; bw = bh * r }
        val hs = s.hs.toFloat(); val hl = 1.18f; val asz = (hs * 0.75f).roundToInt().toFloat()
        text.typeface = hookTf; text.textSize = hs
        val lines = wrapLines(s.hk, W - 40)
        val hh = lines.size * hs * hl; val gap = hs * 0.28f; val ah = if (s.hd.isNotEmpty()) asz * 1.25f else 0f
        val total = hh + (if (hh > 0) gap else 0f) + bh + (if (ah > 0) gap else 0f) + ah
        val top = (H - total) / 2; val vy = top + hh + (if (hh > 0) gap else 0f)
        return Frame(false, (W - bw) / 2, vy + s.vo.toFloat(), bw, bh, lines, hs, hl, top, asz, vy + bh + gap)
    }

    fun staticKey(F: Frame, k: Float) = "${p.s.hk}|${p.s.hd}|${F.hs}|${F.top}|${F.ay}|$k"

    private fun mid(pt: Paint): Float { val fm = pt.fontMetrics; return -(fm.ascent + fm.descent) / 2 }

    /** hook text + handle on a transparent reel-sized canvas (already scaled to pixels) */
    fun paintStatic(c: Canvas, F: Frame) {
        val tp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
        tp.typeface = hookTf; tp.textSize = F.hs
        val m = mid(tp)
        F.lines.forEachIndexed { i, l -> c.drawText(l, W / 2, F.top + (i + 0.5f) * F.hs * F.hl + m, tp) }
        if (p.s.hd.isNotEmpty()) { tp.typeface = handleTf; tp.textSize = F.asz; c.drawText(p.s.hd, W / 2, F.ay + F.asz * 0.65f + mid(tp), tp) }
    }

    /** Caption for source time ct0, or null. k = output pixels per reel unit. */
    fun caption(ct0: Double, F: Frame, k: Float): Cap? {
        val s = p.s
        val ct = ct0 - s.co
        val cue = p.cues.firstOrNull { ct >= it.s && ct < it.e } ?: return null
        val words = cue.words.map { if (s.up) it.uppercase() else it }
        if (words.isEmpty()) return null
        var cur = 0
        cue.wt.forEachIndexed { i, x -> if (ct >= x.s) cur = i }
        cur = cur.coerceAtMost(words.size - 1)
        var kw = 0
        words.forEachIndexed { i, w -> if (w.length > words[kw].length) kw = i }

        val size = (s.fs * (if (F.full) 1.0 else 0.6)).toFloat()
        val tf = CapFonts.get(s.font)
        text.typeface = tf; text.textSize = size
        val gap = size * 0.26f
        val ws = words.map { text.measureText(it) }
        // wrap into lines that fit the reel
        val maxW = W * 0.86f
        val lines = ArrayList<IntRange>()
        var st = 0; var lw = 0f
        for (i in words.indices) {
            val add = if (i == st) ws[i] else lw + gap + ws[i]
            if (i > st && add > maxW) { lines.add(st until i); st = i; lw = ws[i] } else lw = add
        }
        lines.add(st until words.size)
        val lineW = lines.map { r -> r.sumOf { ws[it].toDouble() }.toFloat() + gap * (r.count() - 1) }
        val lineH = size * 1.22f
        val padX = size * 0.75f; val padY = size * 0.6f
        val bw = lineW.max() + 2 * padX; val bh = lines.size * lineH + 2 * padY

        // whole-caption entrance animation
        val age = (ct - cue.s).toFloat()
        var sc = 1f; var alpha = 1f; var dy = 0f
        when (s.anim) {
            "pop" -> { val a = min(1f, age / 0.1f); sc = 1.15f - 0.15f * a; alpha = 0.4f + 0.6f * a }
            "bounce" -> { val t = min(1f, age / 0.28f); val u = t - 1; sc = 0.55f + 0.45f * (1 + 2.70158f * u * u * u + 1.70158f * u * u); alpha = min(1f, age / 0.08f) }
            "slide" -> { val a = min(1f, age / 0.16f); dy = 46f * (1 - a) * (1 - a); alpha = a }
        }
        val y = F.y + F.h * (s.fy / 100).toFloat() + dy
        val ss = k * 1.2f // paint at the biggest size we will show it
        val dyn = if (s.hl == "current" || s.hl == "spoken" || s.reveal || s.box == "word") cur else -1
        val look = "${s.font}|${s.up}|${s.tc}|${s.hc}|${s.sc}|${s.stw}|${s.shd}|${s.glow}|${s.box}|${s.bc}|${s.hl}|${s.reveal}|${s.grow}"
        val key = "${System.identityHashCode(cue)}|$dyn|$look|$size|${cue.wt.map { it.bl }}|$ss"
        val paint = {
            val bmp = Bitmap.createBitmap(ceil(bw * ss).toInt().coerceAtLeast(1), ceil(bh * ss).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp); c.scale(ss, ss); c.translate(bw / 2, bh / 2)
            drawWords(c, words, ws, lines, lineW, gap, lineH, bh, padY, cur, kw, size, tf, cue)
            bmp
        }
        return Cap(key, paint, bw, bh, y, sc, alpha)
    }

    private fun drawWords(c: Canvas, words: List<String>, ws: List<Float>, lines: List<IntRange>, lineW: List<Float>, gap: Float, lineH: Float,
                          bh: Float, padY: Float, cur: Int, kw: Int, size: Float, tf: Typeface, cue: Cue) {
        val s = p.s
        val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = tf; textSize = size }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf; textSize = size; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
            strokeWidth = (size * s.stw).toFloat(); color = s.sc
        }
        val glowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; textSize = size; maskFilter = BlurMaskFilter(size * 0.22f, BlurMaskFilter.Blur.NORMAL) }
        val boxP = Paint(Paint.ANTI_ALIAS_FLAG)
        val fm = fill.fontMetrics
        val base = -(fm.ascent + fm.descent) / 2
        val strokeOn = s.stw > 0.001
        fun isHi(i: Int) = when (s.hl) { "current" -> i == cur; "spoken" -> i <= cur; "keyword" -> i == kw; else -> false }

        lines.forEachIndexed { li, r ->
            val cy = -bh / 2 + padY + li * lineH + lineH / 2
            var x = -lineW[li] / 2
            if (s.box == "line") {
                boxP.color = s.bc
                val px = size * 0.38f; val py = lineH * 0.5f
                c.drawRoundRect(x - px, cy - py, x + lineW[li] + px, cy + py, size * 0.22f, size * 0.22f, boxP)
            }
            for (i in r) {
                val w = words[i]; val ww = ws[i]
                if (s.reveal && i > cur) { x += ww + gap; continue }
                val hi = isHi(i)
                val blur = cue.wt.getOrNull(i)?.bl == true
                c.save()
                if (hi && s.grow && (s.hl == "current" || s.hl == "keyword")) c.scale(1.12f, 1.12f, x + ww / 2, cy)
                if (hi && s.box == "word") {
                    boxP.color = s.hc
                    val px = size * 0.16f; val py = size * 0.62f
                    c.drawRoundRect(x - px, cy - py, x + ww + px, cy + py, size * 0.2f, size * 0.2f, boxP)
                }
                val col = if (hi && s.box != "word") s.hc else s.tc
                val mf = if (blur) BlurMaskFilter(size * 0.2f, BlurMaskFilter.Blur.NORMAL) else null
                if (s.glow && !blur) {
                    glowP.color = col
                    c.drawText(w, x, cy + base, glowP); c.drawText(w, x, cy + base, glowP)
                }
                if (strokeOn) {
                    stroke.maskFilter = mf
                    if (s.shd && !blur) stroke.setShadowLayer(size * 0.12f, 0f, size * 0.05f, Color.argb(200, 0, 0, 0)) else stroke.clearShadowLayer()
                    c.drawText(w, x, cy + base, stroke)
                    fill.clearShadowLayer()
                } else if (s.shd && !blur) fill.setShadowLayer(size * 0.08f, 0f, size * 0.03f, Color.argb(170, 0, 0, 0))
                else fill.clearShadowLayer()
                fill.maskFilter = mf
                fill.color = col
                c.drawText(w, x, cy + base, fill)
                c.restore()
                x += ww + gap
            }
        }
    }

    fun clipLen() = p.clipLen()
    fun zoom(rel: Double): Float { val s = p.s; return Core.zoomK(s.zm, s.zs, s.zd, s.zw, p.clipLen(), rel).toFloat() }
    @Suppress("unused") private fun unused() = max(0, 0)
}
