package com.jare.autoclipper.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Every control of the web editor, as observable state (defaults match index.html). */
class Settings {
    // 1 · Source video
    var st by mutableDoubleStateOf(0.0)          // start (s)
    var ln by mutableDoubleStateOf(30.0)         // max length (s)
    var cut by mutableStateOf(false)             // auto-cut silence
    var sth by mutableDoubleStateOf(26.0)        // cut strength 12..40
    var sgp by mutableDoubleStateOf(0.35)        // min silence to cut
    var cx by mutableDoubleStateOf(0.5)          // picture focus left/right

    // Frame · Hook · Handle
    var rt by mutableStateOf("7:6")
    /** frame shape (mask) id: "none", "asset:squircle", "file:/…" ; frameAspect = its width/height */
    var frameId by mutableStateOf("none")
    var frameAspect by mutableDoubleStateOf(0.0)
    var hk by mutableStateOf("Cortisol could be sabotaging your steroid cycle.")
    var hd by mutableStateOf("yt @diagofit")
    var hs by mutableDoubleStateOf(76.0)
    var vw by mutableDoubleStateOf(100.0)
    var cy by mutableDoubleStateOf(0.0)
    var vo by mutableDoubleStateOf(0.0)

    // 2 · Transcript
    var tr by mutableStateOf("")
    var bon by mutableStateOf(true)
    var bbp by mutableStateOf(true)
    var bw by mutableStateOf("")

    // 3 · Captions
    var sty by mutableStateOf("bold")             // preset id (CapPreset)
    var wn by mutableIntStateOf(3)                 // words on screen at once
    var font by mutableStateOf("montserrat")      // CapFonts id
    var fs by mutableDoubleStateOf(96.0)
    var co by mutableDoubleStateOf(0.0)
    var fy by mutableDoubleStateOf(84.0)
    var up by mutableStateOf(true)
    var tc by mutableStateOf(0xFFFFFFFF.toInt())   // text colour
    var hc by mutableStateOf(0xFFFFE14D.toInt())   // highlight colour
    var sc by mutableStateOf(0xFF000000.toInt())   // outline colour
    var stw by mutableDoubleStateOf(0.12)          // outline width (× font size)
    var shd by mutableStateOf(true)                // drop shadow
    var glow by mutableStateOf(false)
    var box by mutableStateOf("none")              // none | line | word
    var bc by mutableStateOf(0xCC000000.toInt())   // line box colour
    var hl by mutableStateOf("current")            // current | spoken | keyword | none
    var anim by mutableStateOf("pop")              // pop | bounce | slide | none
    var reveal by mutableStateOf(false)
    var grow by mutableStateOf(true)               // highlighted word drawn a bit bigger

    // Face tracking: "off" | "person" (follow one face) | "speaker" (whoever talks) | "split" (two people, top and bottom)
    var faceMode by mutableStateOf("off")
    var facePerson by mutableIntStateOf(1)   // followed person (top half in split)
    var faceB by mutableIntStateOf(2)        // bottom half in split
    var faceSmooth by mutableStateOf("normal") // smooth | normal | fast

    // Overlay effects
    var fxKind by mutableStateOf("mix")           // an Fx id or "mix"
    var fxAmt by mutableDoubleStateOf(0.85)        // strength
    var fxLen by mutableDoubleStateOf(1.0)         // length × the effect's normal length

    // 4 · B-roll
    var brmax by mutableDoubleStateOf(2.0)

    // 5 · Zoom · Music · SFX
    var zm by mutableStateOf("none")
    var zs by mutableDoubleStateOf(20.0)
    var zd by mutableDoubleStateOf(2.0)
    var zw by mutableStateOf(false)
    var bsel by mutableStateOf("auto")
    var bv by mutableDoubleStateOf(0.22)
    var sfxcut by mutableStateOf(true)

    /** Apply a caption template (everything stays editable afterwards). Returns true if words-per-cue changed. */
    fun applyPreset(id: String): Boolean {
        val p = CapPreset.byId(id) ?: return false
        sty = p.id; font = p.font; up = p.up; tc = p.tc; hc = p.hc; sc = p.sc; stw = p.stw; shd = p.shd; glow = p.glow
        box = p.box; bc = p.bc; hl = p.hl; anim = p.anim; reveal = p.reveal; grow = p.grow
        val changed = wn != p.wn; wn = p.wn
        return changed
    }

    companion object {
        val RATIOS = listOf("1:1" to "1:1 (square)", "7:6" to "7:6 (like reference)", "4:5" to "4:5", "4:3" to "4:3", "16:9" to "16:9", "9:16" to "9:16", "full" to "Full screen (no frame)")
        val FR = mapOf("1:1" to 1.0, "7:6" to 7.0 / 6, "4:5" to 4.0 / 5, "4:3" to 4.0 / 3, "16:9" to 16.0 / 9, "9:16" to 9.0 / 16)
    }
}
