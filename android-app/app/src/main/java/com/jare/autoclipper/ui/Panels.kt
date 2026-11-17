package com.jare.autoclipper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.FilterFrames
import androidx.compose.material.icons.rounded.Flare
import androidx.compose.material.icons.rounded.Face
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.jare.autoclipper.editor.Fx
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import com.jare.autoclipper.editor.Track
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jare.autoclipper.editor.EditorViewModel
import com.jare.autoclipper.editor.Settings
import com.jare.autoclipper.editor.CapFonts
import com.jare.autoclipper.editor.CapPreset
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/** Bottom-toolbar tools. Each opens one focused panel, like a mobile video editor. */
enum class Tool(val icon: ImageVector, val label: String) {
    EDIT(Icons.Rounded.ContentCut, "Trim"), FACE(Icons.Rounded.Face, "Face"), HOOK(Icons.Rounded.AutoAwesome, "Hook"), TRANSCRIPT(Icons.Rounded.Notes, "Transcript"),
    CAPTIONS(Icons.Rounded.ClosedCaption, "Captions"), SHAPES(Icons.Rounded.FilterFrames, "Frames"), FRAME(Icons.Rounded.AspectRatio, "Layout"),
    BROLL(Icons.Rounded.Movie, "B-roll"), OVERLAY(Icons.Rounded.Flare, "Overlay"), AUDIO(Icons.Rounded.MusicNote, "Music"), SOUNDS(Icons.Rounded.GraphicEq, "SFX"),
    ZOOM(Icons.Rounded.ZoomIn, "Zoom"), CENSOR(Icons.Rounded.BlurOn, "Censor"), REPORT(Icons.Rounded.Article, "Report")
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Kicker(title, modifier = Modifier.padding(top = 20.dp, bottom = 2.dp))
    Column(content = content)
}

@Composable
private fun FrameTile(f: EditorViewModel.FrameUi?, selected: Boolean, modifier: Modifier, onClick: () -> Unit, onDelete: (() -> Unit)?) {
    val fb = rememberFeedback()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val shape = RoundedCornerShape(10.dp)
        Box(
            Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(shape).background(Raise)
                .border(2.dp, if (selected) Glow else Color.Transparent, shape).clickable { fb(Snd.TOGGLE); onClick() }.padding(7.dp),
            contentAlignment = Alignment.Center
        ) {
            val img = f?.thumb
            if (f != null && img != null) Image(img, f.name, Modifier.fillMaxWidth().aspectRatio(f.aspect.toFloat().coerceIn(0.3f, 3f)), contentScale = ContentScale.Fit)
            else Text("None", color = Mut, fontSize = 11.sp)
            if (onDelete != null) Box(
                Modifier.align(Alignment.TopEnd).size(22.dp).clip(CircleShape).background(Color(0xCC000000)).clickable { onDelete() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.Close, "Delete", tint = Tx, modifier = Modifier.size(14.dp)) }
        }
        Text(f?.name ?: "None", color = if (selected) Glow else Mut, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun SoundRow(vm: EditorViewModel, t: Track, transition: Boolean) {
    val fb = rememberFeedback()
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).glass(RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF34343A)).clickable { vm.audition(t) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, "Listen", tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(t.name.substringBeforeLast('.'), color = Tx, fontSize = 12.sp, maxLines = 1)
            Text("${"%.1f".format(t.dur)} s", color = Mut, fontSize = 11.sp)
        }
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).background(Acc).clickable { fb(Snd.TAP); vm.addSoundAt(t, transition) }.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Add, null, tint = OnAcc, modifier = Modifier.size(16.dp))
            Text("Add", color = OnAcc, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private val SWATCHES = listOf(0xFFFFFFFF, 0xFF000000, 0xFFFFE14D, 0xFFB8FF3D, 0xFF4DFF6A, 0xFF34D3F5, 0xFF5E8BFF, 0xFF8B5CF6, 0xFFFF5EE6, 0xFFFF3B3B, 0xFFFF8F3D)

@Composable
private fun ColorRow(label: String, value: Int, onPick: (Int) -> Unit) {
    val fb = rememberFeedback()
    Label(label)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SWATCHES.forEach { cl ->
            val c = cl.toInt(); val on = (value or 0xFF000000.toInt()) == c
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                .border(if (on) 3.dp else 1.dp, if (on) Glow else Color(0x33FFFFFF), CircleShape)
                .clickable { fb(Snd.TAP); onPick(c) })
        }
    }
}

@Composable
private fun <T> Chips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val fb = rememberFeedback()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, t) ->
            val on = v == selected
            Box(Modifier.height(34.dp).clip(RoundedCornerShape(10.dp)).background(if (on) Acc else Raise)
                .clickable { fb(Snd.TAP); onSelect(v) }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text(t, color = if (on) OnAcc else Tx, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

/** a caption template drawn with its own font, colours, outline and background */
@Composable
private fun PresetTile(p: CapPreset, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fam = remember(p.font) { FontFamily(CapFonts.get(p.font)) }
    val shape = RoundedCornerShape(12.dp)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().height(64.dp).clip(shape).background(Color(0xFF2E2E33))
                .border(2.dp, if (selected) Glow else Color.Transparent, shape).clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            val w1 = if (p.up) "SO" else "So"; val w2 = if (p.up) "GOOD" else "good"
            val hi2 = p.hl != "none"
            val line = Modifier.then(if (p.box == "line") Modifier.clip(RoundedCornerShape(5.dp)).background(Color(p.bc)).padding(horizontal = 6.dp, vertical = 1.dp) else Modifier)
            Row(line, verticalAlignment = Alignment.CenterVertically) {
                CapWord(w1, fam, p.tc, p)
                Spacer(Modifier.width(5.dp))
                if (p.box == "word") Box(Modifier.clip(RoundedCornerShape(5.dp)).background(Color(p.hc)).padding(horizontal = 4.dp)) { CapWord(w2, fam, p.tc, p) }
                else CapWord(w2, fam, if (hi2) p.hc else p.tc, p)
            }
        }
        Text(p.name, color = if (selected) Glow else Mut, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun CapWord(t: String, fam: FontFamily, col: Int, p: CapPreset) {
    val size = 19.sp
    Box {
        if (p.stw > 0.001) Text(t, fontFamily = fam, fontSize = size, maxLines = 1, softWrap = false,
            style = TextStyle(color = Color(p.sc), drawStyle = Stroke(width = (p.stw * 19 * 3).toFloat(), join = StrokeJoin.Round)))
        Text(t, fontFamily = fam, fontSize = size, maxLines = 1, softWrap = false,
            style = TextStyle(color = Color(col), shadow = if (p.glow) Shadow(Color(col), blurRadius = 14f) else if (p.shd && p.stw <= 0.001) Shadow(Color(0xAA000000), Offset(0f, 2f), 6f) else null))
    }
}

/** effect tile with a small painted preview of what the overlay looks like */
@Composable
private fun FxTile(k: Fx.Kind?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val brush: Brush = when (k?.id) {
        null -> Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0xFFFF7A1A), Color(0xFFFF3B6B), Color(0xFF5EF0FF)))
        "flash" -> Brush.radialGradient(listOf(Color.White, Color(0xFFE8E8EE), Color(0xFF6A6A72)))
        "burn" -> Brush.radialGradient(listOf(Color(0xFFFFF4D6), Color(0xFFFF9A1F), Color(0xFFB3200A), Color(0xFF2A1410)))
        "leak" -> Brush.linearGradient(listOf(Color(0xFFFF9F5A), Color(0xFF6B3A55), Color(0xFFFF5E8A)))
        "dip" -> Brush.verticalGradient(listOf(Color(0xFF3A3A40), Color.Black, Color(0xFF3A3A40)))
        "glitch" -> Brush.verticalGradient(0f to Color(0xFF1C1C22), 0.3f to Color(0xFF1C1C22), 0.3f to Color(0xFF5EF0FF), 0.42f to Color(0xFF5EF0FF),
            0.42f to Color(0xFF1C1C22), 0.62f to Color(0xFF1C1C22), 0.62f to Color(0xFFFF3BAA), 0.7f to Color(0xFFFF3BAA), 0.7f to Color(0xFF1C1C22))
        "vhs" -> Brush.verticalGradient(listOf(Color(0xFF55555E), Color(0xFF2A2A30), Color(0xFF8A8A94), Color(0xFF2A2A30), Color(0xFF55555E)))
        "bokeh" -> Brush.radialGradient(listOf(Color(0xFFFFD27A), Color(0xFF6B4E2A), Color(0xFF1C1610)))
        "strobe" -> Brush.horizontalGradient(0f to Color.White, 0.5f to Color.White, 0.5f to Color(0xFF111114), 1f to Color(0xFF111114))
        "rgb" -> Brush.horizontalGradient(listOf(Color(0xFFFF2A55), Color(0xFF2AFF7A), Color(0xFF3366FF)))
        else -> Brush.linearGradient(0f to Color(0xFF2A2A30), 0.42f to Color(0xFF2A2A30), 0.5f to Color(0xFFFFF4D6), 0.58f to Color(0xFF2A2A30), 1f to Color(0xFF2A2A30))
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(56.dp).clip(shape).background(brush).border(2.dp, if (selected) Glow else Color.Transparent, shape).clickable { onClick() })
        Text(k?.name ?: "Mix (auto)", color = if (selected) Glow else Mut, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun ToolPanel(tool: Tool, vm: EditorViewModel, pickVideo: () -> Unit, pickTranscript: () -> Unit, pickBroll: () -> Unit, pickFrame: () -> Unit) {
    val s = vm.s
    val fb = rememberFeedback()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 20.dp)) {
        when (tool) {
            Tool.EDIT -> {
                Text(if (vm.srcName.isEmpty()) "No video loaded" else vm.srcName, color = Tx, fontSize = 13.sp)
                PlainButton(if (vm.srcName.isEmpty()) "Import video" else "Replace video", Modifier.fillMaxWidth().padding(top = 6.dp)) { pickVideo() }
                Row2 {
                    NumField("Start (s)", s.st, Modifier.weight(1f)) { s.st = it.coerceAtLeast(0.0); vm.rangeChanged() }
                    NumField("Length (s)", s.ln, Modifier.weight(1f)) { s.ln = it.coerceIn(3.0, 90.0); vm.rangeChanged(true) }
                }
                Group("Silence") {
                    CheckRow("Auto-cut silence", s.cut) { s.cut = it; vm.rangeChanged() }
                    LSlider("Cut strength", s.sth, 12.0..40.0, 1.0, onChange = { s.sth = it }, onDone = { vm.rangeChanged() })
                    Choice("Min silence to cut", listOf(0.25 to "0.25 s (tight)", 0.35 to "0.35 s", 0.5 to "0.5 s", 0.8 to "0.8 s (gentle)"), s.sgp, { s.sgp = it; vm.rangeChanged() })
                    Note(vm.sn)
                }
            }
            Tool.HOOK -> {
                AccButton("Find hook lines", vm.busy["hook"] == true, Modifier.fillMaxWidth()) { vm.findHooks() }
                Note(vm.hkn)
                vm.hooks.forEachIndexed { i, h ->
                    val on = i == vm.hookSel
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp))
                            .background(Raise).border(2.dp, if (on) Glow else Color.Transparent, RoundedCornerShape(12.dp))
                            .clickable { vm.applyStart(i) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${"%.1f".format(h.seg.s)}s", color = if (on) Glow else Mut, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(h.seg.text, color = Tx, fontSize = 12.sp, maxLines = 2, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                        h.badge?.let { Text(it, color = Glow, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            Tool.TRANSCRIPT -> {
                Row2 {
                    AccButton("Auto-transcribe", vm.busy["asr"] == true, Modifier.weight(1f)) { vm.transcribe() }
                    PlainButton("Load file", Modifier.weight(1f)) { pickTranscript() }
                }
                Note(vm.asrn); Note(vm.trn)
                OutlinedTextField(
                    s.tr, { s.tr = it; vm.transcriptEdited() }, Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 140.dp, max = 320.dp),
                    placeholder = { Text("Paste SRT, VTT, [0:05] lines, or plain text", fontSize = 12.sp) }, colors = fieldColors(), shape = RoundedCornerShape(12.dp),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Tx)
                )
            }
            Tool.CAPTIONS -> {
                val still = { vm.preview.requestStill() }
                Label("Templates")
                CapPreset.ALL.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { p ->
                            PresetTile(p, s.sty == p.id, Modifier.weight(1f)) {
                                fb(Snd.TOGGLE)
                                if (s.applyPreset(p.id)) vm.build()
                                still()
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Label("Font")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CapFonts.ALL.forEach { f ->
                        val on = s.font == f.id
                        val fam = remember(f.id) { FontFamily(CapFonts.get(f.id)) }
                        Column(
                            Modifier.width(84.dp).clip(RoundedCornerShape(12.dp)).background(Raise)
                                .border(2.dp, if (on) Glow else Color.Transparent, RoundedCornerShape(12.dp))
                                .clickable { fb(Snd.TAP); s.font = f.id; still() }.padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Aa", color = Tx, fontFamily = fam, fontSize = 24.sp, maxLines = 1)
                            Text(f.label, color = if (on) Glow else Mut, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp))
                        }
                    }
                }
                ColorRow("Text colour", s.tc) { s.tc = it; still() }
                ColorRow("Highlight colour", s.hc) { s.hc = it; still() }
                ColorRow("Outline colour", s.sc) { s.sc = it; still() }
                LSlider("Size", s.fs, 50.0..150.0, 1.0, onChange = { s.fs = it; still() })
                LSlider("Outline", s.stw * 100, 0.0..20.0, 1.0, onChange = { s.stw = it / 100; still() })
                LSlider("Position", s.fy, 30.0..90.0, 1.0, onChange = { s.fy = it; still() })
                LSlider("Timing (s) · + later, − earlier", s.co, -0.5..0.5, 0.01, { "%.2f".format(it) }, onChange = { s.co = it; still() })
                Label("Words on screen")
                Chips(listOf(1 to "1", 2 to "2", 3 to "3", 4 to "4", 5 to "5"), s.wn) { s.wn = it; vm.build(); still() }
                Label("Highlight")
                Chips(listOf("current" to "Spoken word", "spoken" to "Karaoke", "keyword" to "Keyword", "none" to "None"), s.hl) { s.hl = it; still() }
                Label("Background")
                Chips(listOf("none" to "None", "line" to "Behind line", "word" to "Behind word"), s.box) { s.box = it; still() }
                Label("Animation")
                Chips(listOf("pop" to "Pop", "bounce" to "Bounce", "slide" to "Slide up", "none" to "None"), s.anim) { s.anim = it; still() }
                CheckRow("ALL CAPS", s.up) { s.up = it; still() }
                CheckRow("Word-by-word reveal", s.reveal) { s.reveal = it; still() }
                CheckRow("Enlarge the highlighted word", s.grow) { s.grow = it; still() }
                CheckRow("Shadow", s.shd) { s.shd = it; still() }
                CheckRow("Glow", s.glow) { s.glow = it; still() }
            }
            Tool.FRAME -> {
                Choice("Video ratio inside the reel", Settings.RATIOS, s.rt, { s.rt = it; vm.bump() })
                Label("Top hook text")
                OutlinedTextField(s.hk, { s.hk = it; vm.preview.requestStill() }, Modifier.fillMaxWidth(), minLines = 2, shape = RoundedCornerShape(14.dp), colors = fieldColors())
                Label("Bottom text (channel / handle)")
                OutlinedTextField(s.hd, { s.hd = it; vm.preview.requestStill() }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp), colors = fieldColors())
                LSlider("Hook size", s.hs, 40.0..110.0, 1.0, onChange = { s.hs = it; vm.preview.requestStill() })
                LSlider("Video width", s.vw, 60.0..100.0, 1.0, onChange = { s.vw = it; vm.preview.requestStill() })
                Group("Position") {
                    LSlider("Picture focus ← →", s.cx, 0.0..1.0, 0.01, { "%.2f".format(it) }, onChange = { s.cx = it; vm.preview.requestStill() })
                    LSlider("Picture up ↕ down", s.cy, -1.0..1.0, 0.02, { "%.2f".format(it) }, onChange = { s.cy = it; vm.preview.requestStill() })
                    LSlider("Move frame in the reel", s.vo, -400.0..400.0, 2.0, onChange = { s.vo = it; vm.preview.requestStill() })
                    PlainButton("Reset position", Modifier.fillMaxWidth().padding(top = 6.dp)) { s.vo = 0.0; s.cy = 0.0; s.cx = 0.5; vm.preview.requestStill() }
                }
            }
            Tool.BROLL -> {
                AccButton("Auto B-roll from captions", vm.busy["brauto"] == true, Modifier.fillMaxWidth()) { vm.autoBroll() }
                Note(vm.brn)
                NumField("Max length per clip (s)", s.brmax, Modifier.fillMaxWidth().padding(top = 6.dp)) { s.brmax = it.coerceIn(0.5, 10.0); vm.brMaxChanged() }
                Group("Extra trigger words") {
                    var brw by remember { mutableStateOf("") }
                    var brt by remember { mutableStateOf("") }
                    OutlinedTextField(brw, { brw = it }, Modifier.fillMaxWidth(), singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(12.dp), placeholder = { Text("Caption words, e.g. needle, jab", fontSize = 12.sp) })
                    OutlinedTextField(brt, { brt = it }, Modifier.fillMaxWidth().padding(top = 6.dp), singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(12.dp), placeholder = { Text("B-roll name, e.g. injection", fontSize = 12.sp) })
                    if (vm.brTargets.isNotEmpty()) Text("Available: " + vm.brTargets.joinToString(", "), color = Mut, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    PlainButton("Add word rule", Modifier.fillMaxWidth().padding(top = 6.dp)) { if (vm.addRule(brw, brt)) { brw = ""; brt = "" } }
                    vm.brRules.forEachIndexed { i, r ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(r.w.joinToString(", ") + " → " + r.t, color = Mut, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.removeRule(i) }) { Text("×", color = Tx) }
                        }
                    }
                }
                Group("Your own clips") {
                    PlainButton("Add B-roll files from phone", Modifier.fillMaxWidth()) { pickBroll() }
                    Row2 {
                        PlainButton("Auto-place", Modifier.weight(1f)) { vm.autoPlace() }
                        PlainButton("Clear all", Modifier.weight(1f)) { vm.clearBroll() }
                    }
                    Note("Tap a B-roll block on the timeline to move, resize or delete it.")
                }
            }
            Tool.FACE -> {
                val fs = vm.faces
                AccButton(if (fs == null) "Track faces" else "Scan again", vm.busy["facebtn"] == true, Modifier.fillMaxWidth()) { vm.trackFaces() }
                Note(when {
                    !vm.hasVideo -> "Import a video first."
                    fs != null && vm.facesStale() -> "You changed the trim since the scan. Tap Scan again so tracking covers the new part."
                    vm.faceNote.isNotEmpty() -> vm.faceNote
                    else -> "Finds the people in your video and keeps the one you pick centred in the 9:16 frame. Best for wide 16:9 videos, like a two-person podcast."
                })
                if (fs != null && fs.persons.isNotEmpty()) {
                    Label("Who to follow")
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        fs.persons.forEach { p ->
                            val on = (s.faceMode == "person" && s.facePerson == p.id) || (s.faceMode == "split" && (s.facePerson == p.id || s.faceB == p.id))
                            val tag = when {
                                s.faceMode == "split" && s.facePerson == p.id -> "Top"
                                s.faceMode == "split" && s.faceB == p.id -> "Bottom"
                                s.faceMode == "person" && s.facePerson == p.id -> "Following"
                                s.faceMode == "speaker" -> "Auto · ${(p.coverage * 100).toInt()}%"
                                else -> "${(p.coverage * 100).toInt()}% on screen"
                            }
                            val img = remember(p) { p.thumb?.asImageBitmap() }
                            Column(
                                Modifier.width(96.dp).clip(RoundedCornerShape(16.dp)).background(Raise)
                                    .border(2.dp, if (on) Glow else Color.Transparent, RoundedCornerShape(16.dp))
                                    .clickable { fb(Snd.TOGGLE); vm.followPerson(p.id) }.padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(Modifier.size(60.dp).clip(CircleShape).background(Color(0xFF34343A)), contentAlignment = Alignment.Center) {
                                    if (img != null) Image(img, p.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    else Icon(Icons.Rounded.Face, null, tint = Mut, modifier = Modifier.size(30.dp))
                                }
                                Text(p.label, color = Tx, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
                                Text(tag, color = if (on) Glow else Mut, fontSize = 10.sp, maxLines = 1)
                            }
                        }
                    }
                    Label("View")
                    val modes = buildList {
                        if (fs.persons.size >= 2) add("speaker" to "Follow the speaker")
                        add("person" to "Follow one person")
                        if (fs.persons.size >= 2) add("split" to "Split screen")
                        add("off" to "Off")
                    }
                    Chips(modes, s.faceMode) { fb(Snd.TOGGLE); vm.setFaceMode(it) }
                    if (s.faceMode == "speaker") Note(if (vm.cues.isEmpty()) "Tip: make captions first (Transcript). Knowing when words are spoken makes speaker switching much more accurate."
                        else "Cuts to whoever is talking, using mouth movement and your caption timing. Each shot lasts at least 2 s.")
                    if (s.faceMode == "split") {
                        Note("Top: ${fs.person(s.facePerson)?.label ?: "-"}  ·  Bottom: ${fs.person(s.faceB)?.label ?: "-"}")
                        PlainButton("Swap top and bottom", Modifier.fillMaxWidth().padding(top = 6.dp)) { vm.swapSplit() }
                    }
                    Label("Camera movement")
                    Chips(listOf("smooth" to "Smooth", "normal" to "Normal", "fast" to "Quick"), s.faceSmooth) { s.faceSmooth = it; vm.bump() }
                }
                Note("Runs on this phone. Your video is never uploaded.")
            }
            Tool.OVERLAY -> {
                val starts = vm.clipStarts()
                AccButton("Auto overlay on every clip", vm.busy["fxbtn"] == true, Modifier.fillMaxWidth()) { vm.autoOverlay() }
                Note(vm.fxn.ifEmpty {
                    if (!vm.hasVideo) "Import a video first."
                    else "Adds the chosen effect at the start of every clip: the video start, every silence cut and every B-roll in/out (${starts.size} spots now)."
                })
                Label("Effect")
                val items = listOf<Fx.Kind?>(null) + Fx.ALL
                items.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { k ->
                            val id = k?.id ?: "mix"
                            FxTile(k, s.fxKind == id, Modifier.weight(1f)) { fb(Snd.TOGGLE); s.fxKind = id }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                val nm = Fx.byId(s.fxKind)?.name ?: "Mix"
                Row2 {
                    PlainButton("Add $nm at playhead", Modifier.weight(1f), enabled = vm.hasVideo) { vm.addOverlayAt(s.fxKind) }
                }
                LSlider("Strength", s.fxAmt * 100, 10.0..100.0, 1.0, { "${it.toInt()}%" }, onChange = { s.fxAmt = it / 100; vm.preview.requestStill() }, onDone = { vm.fxSettingsChanged() })
                LSlider("Length", s.fxLen, 0.5..2.5, 0.05, { "×%.2f".format(it) }, onChange = { s.fxLen = it }, onDone = { vm.fxSettingsChanged() })
                PlainButton("Remove all overlays", Modifier.fillMaxWidth().padding(top = 12.dp)) { vm.clearOverlays() }
                Note("Tap an overlay on the timeline to move it, change its length or strength, or delete it.")
            }
            Tool.AUDIO -> {
                AccButton("Auto BGM · pick the best track", vm.busy["bgmbtn"] == true, Modifier.fillMaxWidth()) { vm.autoBgm() }
                Note(vm.libn)
                Choice("Background music", listOf("auto" to "Auto · best track") + vm.bselOptions.map { it to it }, s.bsel, { vm.onBselChange(it) })
                LSlider("Music volume", s.bv, 0.05..0.6, 0.01, { "%.2f".format(it) }, onChange = { s.bv = it }, onDone = { vm.bump() })
                Note("Music ducks under speech and fades out at the end. Hook and transition sounds are in the SFX tool.")
            }
            Tool.SOUNDS -> {
                LaunchedEffect(Unit) { vm.ensureSounds() }
                Row2 {
                    AccButton("Auto hook SFX", vm.busy["sfxbtn"] == true, Modifier.weight(1f)) { vm.autoHook() }
                    AccButton("Auto transitions", vm.busy["transbtn"] == true, Modifier.weight(1f)) { vm.autoTransitions() }
                }
                CheckRow("Add transition sounds to B-roll clips automatically", s.sfxcut) { s.sfxcut = it; vm.sfxcutChanged() }
                Note("▶ listens to a sound. Add puts it on the timeline at the playhead (${"%.1f".format(vm.phRel)}s), with its hit exactly there. Tap it on the timeline to move it or change its volume.")
                Group("Hook SFX · best hook folder") {
                    if (vm.sfxTracks.isEmpty()) Note("No sounds yet. Put files in the \"best hook\" folder of your R2 pack.")
                    vm.sfxTracks.forEach { SoundRow(vm, it, false) }
                }
                Group("Transition sounds · transitionsfx folder") {
                    if (vm.transTracks.isEmpty()) Note("No sounds yet. Put files in the \"transitionsfx\" folder of your R2 pack.")
                    vm.transTracks.forEach { SoundRow(vm, it, true) }
                }
                PlainButton("Remove all sounds from the video", Modifier.fillMaxWidth().padding(top = 12.dp)) { vm.clearSounds() }
            }
            Tool.SHAPES -> {
                LaunchedEffect(Unit) { vm.loadFrames() }
                Text("Pick a frame shape. The video (and B-roll) shows inside the white part, with smooth edges.", color = Mut, fontSize = 12.sp)
                val items: List<EditorViewModel.FrameUi?> = listOf<EditorViewModel.FrameUi?>(null) + vm.frames
                items.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { f ->
                            FrameTile(f, (f?.id ?: "none") == s.frameId, Modifier.weight(1f), { vm.applyFrame(f) },
                                if (f != null && f.custom) ({ vm.deleteFrame(f) }) else null)
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                PlainButton("Add frame from phone", Modifier.fillMaxWidth().padding(top = 12.dp)) { pickFrame() }
                Note("Make your own: a white shape on a black picture (PNG or JPG, any size). Its width and height become the video's ratio. To give everyone the same frames, put them in a \"frames\" folder in R2 and run make-r2-manifest.js again.")
                if (s.frameId != "none") Group("Fit") {
                    LSlider("Frame size", s.vw, 60.0..100.0, 1.0, onChange = { s.vw = it; vm.preview.requestStill() })
                    LSlider("Picture focus ← →", s.cx, 0.0..1.0, 0.01, { "%.2f".format(it) }, onChange = { s.cx = it; vm.preview.requestStill() })
                    LSlider("Picture up ↕ down", s.cy, -1.0..1.0, 0.02, { "%.2f".format(it) }, onChange = { s.cy = it; vm.preview.requestStill() })
                }
            }
            Tool.ZOOM -> {
                Choice("Start effect", listOf("none" to "None", "in" to "Zoom in", "out" to "Zoom out"), s.zm, { s.zm = it; vm.bump() })
                LSlider("Strength %", s.zs, 5.0..50.0, 1.0, onChange = { s.zs = it; vm.preview.requestStill() }, onDone = { vm.report() })
                LSlider("Duration (s)", s.zd, 0.5..8.0, 0.1, { "%.1f".format(it) }, onChange = { s.zd = it; vm.preview.requestStill() }, onDone = { vm.report() })
                CheckRow("Keep zooming across the whole clip", s.zw) { s.zw = it; vm.bump() }
            }
            Tool.CENSOR -> {
                CheckRow("Blur listed words in captions", s.bon) { s.bon = it; vm.build() }
                CheckRow("Bleep (mute + beep) them in the audio", s.bbp) { s.bbp = it; vm.build() }
                OutlinedTextField(
                    s.bw, { s.bw = it; vm.blurListEdited() }, Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 280.dp),
                    placeholder = { Text("One word or phrase per line", fontSize = 12.sp) }, colors = fieldColors(), shape = RoundedCornerShape(12.dp),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Tx)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Note(vm.bwn)
                    TextButton(onClick = { vm.resetBlurList() }) { Text("Reset list", color = Glow) }
                }
            }
            Tool.REPORT -> {
                @Suppress("UNUSED_VARIABLE") val r = vm.rev
                Text(vm.rep, color = Mut, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
