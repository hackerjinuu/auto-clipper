package com.jare.autoclipper.ui

import android.content.Intent
import android.net.Uri
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.jare.autoclipper.editor.EditorViewModel

private fun clock(t: Double): String {
    val ds = (t * 10).toInt().coerceAtLeast(0)
    return "%d:%02d.%d".format(ds / 600, (ds / 10) % 60, ds % 10)
}

@Composable
private fun IconBtn(icon: ImageVector, desc: String, size: Dp = 40.dp, tint: Color = Tx, bg: Color? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val fb = rememberFeedback()
    val base = if (bg != null) Modifier.size(size).clip(CircleShape).background(bg) else Modifier.size(size).clip(CircleShape)
    Box(base.clickable(enabled = enabled) { fb(Snd.TAP); onClick() }, contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = if (enabled) tint else Color(0xFF55555B), modifier = Modifier.size(size * 0.56f))
    }
}

@Composable
private fun Pill(text: String, icon: ImageVector?, primary: Boolean, busy: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val fb = rememberFeedback()
    val base = Modifier.height(34.dp).clip(shape).background(if (primary) Acc else Raise)
    Row(
        base.clickable(enabled = !busy) { fb(Snd.TAP); onClick() }.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val fg = if (primary) OnAcc else Tx
        if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = if (primary) fg else Glow)
        else if (icon != null) Icon(icon, null, tint = if (primary) fg else Glow, modifier = Modifier.size(16.dp))
        Text(text, color = fg, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
fun EditorScreen(vm: EditorViewModel, onOpenModel: () -> Unit, onLogout: () -> Unit) {
    var showSettings by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf<Tool?>(null) }
    val lastTool = remember { arrayOf(Tool.EDIT) }
    var pps by remember { mutableFloatStateOf(48f) } // timeline zoom, dp per second
    val ctx = LocalContext.current
    val fb = rememberFeedback()

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null) { runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; vm.pickSource(u) }
    }
    val pickTr = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? -> if (u != null) vm.loadTranscriptFile(u) }
    val pickBr = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { l: List<Uri> -> if (l.isNotEmpty()) vm.addManualBroll(l) }
    val importVideo = { pickVideo.launch(arrayOf("video/*")) }
    val pickFrame = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? -> if (u != null) vm.importFrame(u) }

    @Suppress("UNUSED_VARIABLE") val rev = vm.rev
    val selB = vm.selBroll?.let { id -> vm.broll.firstOrNull { it.id == id } }
    val selS = vm.selSfx?.let { i -> vm.sfxRows().getOrNull(i)?.let { i to it } }
    val selO = vm.selOv?.let { id -> vm.overlays.firstOrNull { it.id == id } }
    val hasSel = selB != null || selS != null || selO != null

    BackHandler(enabled = tool != null || hasSel) { if (tool != null) tool = null else vm.select() }

    BoxWithConstraints(Modifier.fillMaxSize().background(Bg)) {
        val screenH = maxHeight
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // ---------- top bar ----------
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconBtn(Icons.Rounded.Settings, "Settings", 40.dp) { showSettings = true }
                Column(Modifier.weight(1f)) {
                    if (vm.srcName.isEmpty()) Brand(18.dp)
                    else {
                        Text(vm.srcName.substringBeforeLast('.'), color = Tx, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                        if (vm.stat.isNotEmpty()) Text(vm.stat, color = Mut, fontSize = 11.sp, maxLines = 1)
                    }
                }
                if (vm.hasVideo) Pill("Auto", Icons.Rounded.AutoFixHigh, primary = false, busy = vm.busy["magic"] == true) { vm.autoEdit() }
                Pill("Export", null, primary = true) { vm.openExport() }
            }
            // ---------- preview ----------
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                val shape = RoundedCornerShape(6.dp)
                Box(Modifier.fillMaxHeight().aspectRatio(9f / 16f, matchHeightConstraintsFirst = true).clip(shape).background(Color(0xFF111113))) {
                    AndroidView(factory = { c -> SurfaceView(c).apply { holder.addCallback(vm.preview) } }, modifier = Modifier.fillMaxSize())
                    if (vm.hasVideo) {
                        // tap the picture to play / pause
                        Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { vm.togglePlay() })
                    } else {
                        Column(Modifier.fillMaxSize().background(Color(0xFF111113)).clickable { importVideo() }.padding(22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Box(Modifier.size(60.dp).clip(RoundedCornerShape(18.dp)).background(Acc), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Add, "Import video", tint = OnAcc, modifier = Modifier.size(30.dp))
                            }
                            Text("New project", color = Tx, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 14.dp))
                            Text("Import a video, then tap Auto for captions, B-roll, music and sounds.", color = Mut, fontSize = 12.sp, lineHeight = 17.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
                vm.task?.let { t -> TaskToast(t.text, t.state, t.pct, Modifier.align(Alignment.TopCenter)) }
            }
            // ---------- transport ----------
            Row(Modifier.fillMaxWidth().height(48.dp).background(Bg).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Clock(vm) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconBtn(Icons.Rounded.SkipPrevious, "To start", 40.dp, enabled = vm.hasVideo) { vm.preview.stop(); vm.scrubTo(0.0) }
                    IconBtn(if (vm.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play", 48.dp, enabled = vm.hasVideo) { vm.togglePlay() }
                    IconBtn(Icons.Rounded.Add, "Import video", 40.dp) { importVideo() }
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End)) {
                    IconBtn(Icons.Rounded.ZoomOut, "Zoom out", 38.dp, tint = Mut) { pps = (pps / 1.5f).coerceAtLeast(12f) }
                    IconBtn(Icons.Rounded.ZoomIn, "Zoom in", 38.dp, tint = Mut) { pps = (pps * 1.5f).coerceAtMost(240f) }
                }
            }
            // ---------- timeline ----------
            Timeline(vm, pps, Modifier.height(232.dp))
            // ---------- selection actions, or the toolbar ----------
            Box(Modifier.fillMaxWidth().height(70.dp).background(Panel), contentAlignment = Alignment.Center) {
                if (selB != null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(selB.asset.name, color = Tx, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text("${"%.1f".format(selB.start)}s · ${"%.1f".format(selB.dur)}s long", color = Mut, fontSize = 11.sp, maxLines = 1)
                        }
                        IconBtn(Icons.Rounded.ChevronLeft, "Earlier", 36.dp) { vm.nudgeBroll(selB.id, -0.1, 0.0) }
                        IconBtn(Icons.Rounded.ChevronRight, "Later", 36.dp) { vm.nudgeBroll(selB.id, 0.1, 0.0) }
                        IconBtn(Icons.Rounded.Remove, "Shorter", 36.dp) { vm.nudgeBroll(selB.id, 0.0, -0.1) }
                        IconBtn(Icons.Rounded.Add, "Longer", 36.dp) { vm.nudgeBroll(selB.id, 0.0, 0.1) }
                        IconBtn(Icons.Rounded.Delete, "Delete", 36.dp, tint = Color(0xFFFF8F8F)) { vm.removeBroll(selB.id) }
                        IconBtn(Icons.Rounded.Close, "Done", 36.dp) { vm.select() }
                    }
                } else if (selO != null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(com.jare.autoclipper.editor.Fx.byId(selO.kind)?.name ?: selO.kind, color = Tx, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text("${"%.1f".format(selO.t)}s · ${"%.1f".format(selO.dur)}s · ${(selO.amt * 100).toInt()}%", color = Mut, fontSize = 11.sp, maxLines = 1)
                        }
                        IconBtn(Icons.Rounded.ChevronLeft, "Earlier", 36.dp) { vm.nudgeOverlay(selO.id, -0.1, 0.0) }
                        IconBtn(Icons.Rounded.ChevronRight, "Later", 36.dp) { vm.nudgeOverlay(selO.id, 0.1, 0.0) }
                        IconBtn(Icons.Rounded.Remove, "Shorter", 36.dp) { vm.nudgeOverlay(selO.id, 0.0, -0.1) }
                        IconBtn(Icons.Rounded.Add, "Longer", 36.dp) { vm.nudgeOverlay(selO.id, 0.0, 0.1) }
                        IconBtn(Icons.Rounded.Delete, "Delete", 36.dp, tint = Danger) { vm.removeOverlay(selO.id) }
                        IconBtn(Icons.Rounded.Close, "Done", 36.dp) { vm.select() }
                    }
                } else if (selS != null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(selS.second.s.name, color = Tx, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text((if (selS.second.tr) "Transition" else "Hook") + " · ${"%.2f".format(selS.second.hit)}s · vol ${(selS.second.v * 100).toInt()}%", color = Mut, fontSize = 11.sp, maxLines = 1)
                        }
                        IconBtn(Icons.Rounded.ChevronLeft, "Earlier", 36.dp) { vm.nudgeSfx(selS.first, -0.05, 0.0) }
                        IconBtn(Icons.Rounded.ChevronRight, "Later", 36.dp) { vm.nudgeSfx(selS.first, 0.05, 0.0) }
                        IconBtn(Icons.Rounded.VolumeDown, "Quieter", 36.dp) { vm.nudgeSfx(selS.first, 0.0, -0.1) }
                        IconBtn(Icons.Rounded.VolumeUp, "Louder", 36.dp) { vm.nudgeSfx(selS.first, 0.0, 0.1) }
                        IconBtn(Icons.Rounded.Delete, "Delete", 36.dp, tint = Color(0xFFFF8F8F)) { vm.removeSfx(selS.first) }
                        IconBtn(Icons.Rounded.Close, "Done", 36.dp) { vm.select() }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                        Tool.values().forEach { t ->
                            val on = tool == t
                            val busy = when (t) { Tool.HOOK -> vm.busy["hook"]; Tool.TRANSCRIPT -> vm.busy["asr"]; Tool.BROLL -> vm.busy["brauto"]; Tool.AUDIO -> vm.busy["bgmbtn"]; Tool.SOUNDS -> vm.busy["sfxbtn"] ?: vm.busy["transbtn"]; Tool.OVERLAY -> vm.busy["fxbtn"]; Tool.FACE -> vm.busy["facebtn"]; else -> null } == true
                            Column(
                                Modifier.width(64.dp).clip(RoundedCornerShape(12.dp))
                                    .clickable { fb(if (on) Snd.CLOSE else Snd.OPEN); tool = if (on) null else t }.padding(vertical = 9.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Glow)
                                else Icon(t.icon, t.label, tint = if (on) Glow else Tx, modifier = Modifier.size(22.dp))
                                Text(t.label, color = if (on) Glow else Color(0xFFC7C7CC), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(top = 5.dp))
                            }
                        }
                    }
                }
            }
        }

        // ---------- tool panel (bottom sheet; the preview stays visible above) ----------
        if (tool != null) Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { fb(Snd.CLOSE); tool = null })
        SideEffect { tool?.let { lastTool[0] = it } }
        AnimatedVisibility(
            visible = tool != null, modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(), exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            val t = tool ?: lastTool[0]
            val shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
            Column(
                Modifier.fillMaxWidth().heightIn(max = screenH * 0.55f).clip(shape).background(Panel)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .navigationBarsPadding()
            ) {
                Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(t.icon, null, tint = Tx, modifier = Modifier.size(20.dp))
                    Text(t.label, color = Tx, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f).padding(start = 10.dp))
                    IconBtn(Icons.Rounded.Check, "Done", 44.dp) { tool = null }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                ToolPanel(t, vm,
                    pickVideo = { importVideo() },
                    pickTranscript = { pickTr.launch(arrayOf("text/*", "application/x-subrip", "application/octet-stream")) },
                    pickBroll = { pickBr.launch(arrayOf("video/*", "image/*")) },
                    pickFrame = { pickFrame.launch(arrayOf("image/*")) })
            }
        }
    }

    vm.export?.let { ExportDialog(vm, it) }
    if (showSettings) SettingsDialog(vm, onClose = { showSettings = false }, onOpenModel = { showSettings = false; onOpenModel() }, onLogout = onLogout)
    if (vm.needModel) AlertDialog(
        onDismissRequest = { vm.needModel = false },
        title = { Text("Download the model & assets") },
        text = { Text("Parakeet (≈670 MB) and your R2 music / SFX / B-roll are downloaded once and stored inside the app. After that everything runs on the phone.") },
        confirmButton = { TextButton(onClick = { vm.needModel = false; onOpenModel() }) { Text("Open download") } },
        dismissButton = { TextButton(onClick = { vm.needModel = false }) { Text("Later") } }
    )
}

/** its own composable, so the ticking time does not redraw the rest of the screen */
@Composable
private fun Clock(vm: EditorViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(clock(vm.phRel), color = Tx, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(" / " + clock(vm.clipLen()), color = Mut, fontSize = 13.sp)
    }
}

@Composable
private fun TaskToast(text: String, state: Int, pct: Float?, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.padding(10.dp).fillMaxWidth(0.92f).clip(shape).background(Color(0xF2232326)).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (state) {
                0 -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Tx)
                1 -> Icon(Icons.Rounded.CheckCircle, null, tint = Glow, modifier = Modifier.size(18.dp))
                else -> Icon(Icons.Rounded.ErrorOutline, null, tint = Danger, modifier = Modifier.size(18.dp))
            }
            Text(text.removePrefix("✓ ").removePrefix("⚠ "), color = Tx, fontSize = 13.sp, maxLines = 3, lineHeight = 17.sp,
                modifier = Modifier.padding(start = 10.dp).weight(1f))
            if (state == 0 && pct != null) Text("${(pct * 100).toInt()}%", color = Mut, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        if (state == 0) GlowProgress(pct, Modifier.padding(top = 6.dp), height = 3.dp)
    }
}
