package com.jare.autoclipper.editor

/** One overlay effect on the timeline (output time t, length dur, strength amt 0..1). */
data class OverlayItem(val id: Int, val kind: String, val t: Double, val dur: Double, val amt: Double, val auto: Boolean)

/**
 * Built-in overlay effects. They are drawn by a GPU shader (GlCanvas.drawFx), so they need no
 * files, look the same in preview and export, and cost almost nothing to render.
 */
object Fx {
    class Kind(val id: String, val name: String, val code: Int, val dur: Double, val color: Long)

    val ALL = listOf(
        Kind("flash", "Flash", 0, 0.35, 0xFFFFFFFF),
        Kind("burn", "Film Burn", 1, 0.9, 0xFFFF7A1A),
        Kind("leak", "Light Leak", 2, 1.2, 0xFFFF9F5A),
        Kind("dip", "Dip to Black", 3, 0.5, 0xFF000000),
        Kind("glitch", "Glitch", 4, 0.45, 0xFF5EF0FF),
        Kind("vhs", "VHS", 5, 0.8, 0xFFB8B8C0),
        Kind("bokeh", "Bokeh", 6, 1.1, 0xFFFFD27A),
        Kind("strobe", "Strobe", 7, 0.5, 0xFFF5F5F5),
        Kind("rgb", "RGB Flash", 8, 0.4, 0xFFFF3B6B),
        Kind("shine", "Shine", 9, 0.55, 0xFFFFF4D6),
    )
    /** the rotation used by "Mix": the punchy ones, never the same twice in a row */
    val MIX = listOf("flash", "burn", "shine", "leak", "rgb", "glitch")

    fun byId(id: String) = ALL.firstOrNull { it.id == id }

    /**
     * Where clips start in the output: the video start, every silence cut, every B-roll start
     * and the jump back to the main video after a B-roll. Points closer than minGap are merged.
     */
    fun clipStarts(L: Double, ranges: List<Range>, broll: List<BrollItem>, minGap: Double = 0.8): List<Double> {
        val pts = ArrayList<Double>()
        pts.add(0.0)
        ranges.drop(1).forEach { pts.add(it.o) }
        broll.filter { it.dur > 0 }.forEach { pts.add(it.start); pts.add(it.start + it.dur) }
        val out = ArrayList<Double>()
        for (p in pts.filter { it >= 0 && it < L - 0.25 }.sorted()) if (out.isEmpty() || p - out.last() >= minGap) out.add(p)
        return out
    }

    /** overlays for every clip start; kind = an effect id or "mix" */
    fun plan(kind: String, starts: List<Double>, L: Double, lenK: Double, amt: Double, keep: List<OverlayItem>, skip: Collection<Double>, nextId: () -> Int): List<OverlayItem> {
        val out = ArrayList<OverlayItem>()
        var mi = 0
        for (p in starts) {
            if (keep.any { kotlin.math.abs(it.t - p) < 0.3 }) continue // the user placed one here by hand
            if (skip.any { kotlin.math.abs(it - p) < 0.3 }) continue   // the user moved or deleted the one that was here
            val id = if (kind == "mix") MIX[mi++ % MIX.size] else kind
            val k = byId(id) ?: continue
            val d = (k.dur * lenK).coerceAtMost(L - p).coerceAtLeast(0.1)
            out.add(OverlayItem(nextId(), id, p, d, amt, true))
        }
        return out
    }
}
