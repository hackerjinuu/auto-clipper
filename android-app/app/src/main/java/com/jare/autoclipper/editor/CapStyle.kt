package com.jare.autoclipper.editor

import android.content.Context
import android.graphics.Typeface

/** Caption fonts bundled in assets/fonts (Google Fonts, OFL / Apache licensed). */
object CapFonts {
    class F(val id: String, val label: String, val file: String?, val wght: Int = 0)

    val ALL = listOf(
        F("montserrat", "Montserrat Black", "fonts/montserrat.ttf", 900),
        F("poppins", "Poppins Black", "fonts/poppins_black.ttf"),
        F("poppins_xb", "Poppins Bold", "fonts/poppins_extrabold.ttf"),
        F("poppins_sb", "Poppins Clean", "fonts/poppins_semibold.ttf"),
        F("anton", "Anton", "fonts/anton.ttf"),
        F("bebas", "Bebas Neue", "fonts/bebas.ttf"),
        F("oswald", "Oswald", "fonts/oswald.ttf", 700),
        F("archivo", "Archivo Black", "fonts/archivo_black.ttf"),
        F("luckiest", "Luckiest Guy", "fonts/luckiest.ttf"),
        F("bangers", "Bangers", "fonts/bangers.ttf"),
        F("titan", "Titan One", "fonts/titan.ttf"),
        F("marker", "Marker", "fonts/marker.ttf"),
        F("system", "System Bold", null),
    )

    private lateinit var app: Context
    private val cache = HashMap<String, Typeface>()

    fun init(ctx: Context) { app = ctx.applicationContext }

    @Synchronized
    fun get(id: String): Typeface = cache.getOrPut(id) {
        val f = ALL.firstOrNull { it.id == id } ?: ALL[0]
        val file = f.file
        if (file == null || !::app.isInitialized) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        else runCatching {
            val b = Typeface.Builder(app.assets, file)
            if (f.wght > 0) b.setFontVariationSettings("'wght' ${f.wght}")
            b.build()
        }.getOrNull() ?: Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
}

/**
 * One caption look (like CapCut's text templates).
 * hl: which words get the highlight colour — "current" (spoken word), "spoken" (karaoke fill), "keyword" (longest word), "none".
 * box: "none", "line" (pill behind the line), "word" (pill behind the highlighted word, in the highlight colour).
 * anim: "pop", "bounce", "slide", "none". reveal = words appear as they are spoken.
 */
data class CapPreset(
    val id: String, val name: String, val font: String, val up: Boolean,
    val tc: Int, val hc: Int, val sc: Int = 0xFF000000.toInt(), val stw: Double = 0.12,
    val shd: Boolean = true, val glow: Boolean = false, val box: String = "none", val bc: Int = 0xCC000000.toInt(),
    val hl: String = "current", val anim: String = "pop", val reveal: Boolean = false, val wn: Int = 3, val grow: Boolean = true,
) {
    companion object {
        private fun c(v: Long) = v.toInt()
        val ALL = listOf(
            CapPreset("bold", "Bold Pop", "montserrat", true, c(0xFFFFFFFF), c(0xFFFFE14D)),
            CapPreset("comic", "Comic", "luckiest", true, c(0xFFFFFFFF), c(0xFF4DFF6A), stw = 0.16, anim = "bounce", wn = 2),
            CapPreset("karaoke", "Karaoke", "poppins", true, c(0xFFFFFFFF), c(0xFFB8FF3D), hl = "spoken", anim = "none", wn = 4, grow = false),
            CapPreset("hlbox", "Highlight", "poppins_xb", true, c(0xFFFFFFFF), c(0xFF8B5CF6), stw = 0.0, box = "word", wn = 3),
            CapPreset("subtitle", "Subtitle", "poppins_sb", false, c(0xFFFFFFFF), c(0xFFFFFFFF), stw = 0.0, shd = false, box = "line", hl = "none", anim = "none", wn = 5, grow = false),
            CapPreset("oneword", "One Word", "anton", true, c(0xFFFFFFFF), c(0xFFFFFFFF), stw = 0.1, hl = "none", wn = 1),
            CapPreset("neon", "Neon", "poppins_xb", true, c(0xFF7DF9FF), c(0xFFFF5EE6), stw = 0.0, shd = false, glow = true, anim = "slide"),
            CapPreset("marker", "Marker", "marker", false, c(0xFFFFFFFF), c(0xFFFFE14D), stw = 0.08, anim = "slide", reveal = true, wn = 4),
            CapPreset("type", "Typewriter", "archivo", true, c(0xFFFFFFFF), c(0xFFFFFFFF), stw = 0.1, hl = "none", anim = "none", reveal = true, wn = 4, grow = false),
            CapPreset("cinema", "Cinema", "bebas", true, c(0xFFFFFFFF), c(0xFFFFD27A), stw = 0.0, hl = "keyword", anim = "slide", wn = 4, grow = false),
            CapPreset("gaming", "Gaming", "titan", true, c(0xFFFFFFFF), c(0xFFFF3B3B), stw = 0.15, anim = "bounce", wn = 2),
            CapPreset("redkw", "Red Keyword", "oswald", true, c(0xFFFFFFFF), c(0xFFFF3B3B), stw = 0.12, hl = "keyword", wn = 3),
            CapPreset("comicbook", "Comic Book", "bangers", true, c(0xFFFFE14D), c(0xFFFFFFFF), sc = c(0xFF1A1A1A), stw = 0.14, anim = "bounce", wn = 2),
            CapPreset("minimal", "Minimal", "poppins_sb", false, c(0xFFFFFFFF), c(0xFFFFFFFF), stw = 0.0, hl = "none", anim = "none", wn = 4, grow = false),
        )
        fun byId(id: String) = ALL.firstOrNull { it.id == id }
    }
}
