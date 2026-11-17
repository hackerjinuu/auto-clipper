package com.jare.autoclipper.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.jare.autoclipper.App
import com.jare.autoclipper.editor.EditorViewModel
import com.jare.autoclipper.editor.ExportUi
import com.jare.autoclipper.editor.Library

@Composable
private fun OptBox(title: String, sub: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Raise, RoundedCornerShape(12.dp))
            .border(2.dp, if (on) Glow else Color.Transparent, RoundedCornerShape(12.dp)).clickable { onClick() }.padding(12.dp)
    ) {
        Text(title, fontWeight = FontWeight.Bold, color = Tx, fontSize = 13.sp)
        Text(sub, color = Mut, fontSize = 12.sp)
    }
}

@Composable
fun ExportDialog(vm: EditorViewModel, e: ExportUi) {
    val ctx = LocalContext.current
    AlertDialog(
        containerColor = Panel, shape = RoundedCornerShape(20.dp), tonalElevation = 0.dp,
        onDismissRequest = { vm.closeExport() },
        properties = DialogProperties(dismissOnClickOutside = e.stage != 1, dismissOnBackPress = e.stage != 1),
        title = { Text(if (e.stage == 0) "Export video" else e.title) },
        text = {
            Column {
                if (e.stage == 0) {
                    Text("Rendered frame by frame, so the file is smooth with audio in sync.", color = Mut, fontSize = 12.sp)
                    Label("Resolution")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OptBox("Full HD · 1080p", "1080 × 1920", e.res == 1080, Modifier.weight(1f)) { vm.setExportOpt(res = 1080) }
                        OptBox("Ultra HD · 4K", "2160 × 3840", e.res == 2160, Modifier.weight(1f)) { vm.setExportOpt(res = 2160) }
                    }
                    Label("Frame rate")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OptBox("30 fps", "Fast, smooth", e.fps == 30, Modifier.weight(1f)) { vm.setExportOpt(fps = 30) }
                        OptBox("60 fps", "Extra smooth", e.fps == 60, Modifier.weight(1f)) { vm.setExportOpt(fps = 60) }
                    }
                    Note(vm.exportSummary(e.res, e.fps))
                } else {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        ChargeLoader(when (e.stage) { 1 -> "Exporting."; 2 -> "Saved."; else -> "Stopped." }, when (e.stage) { 1 -> e.pct; 2 -> 1f; else -> e.pct.coerceAtLeast(0.04f) }, size = 200.dp)
                        if (e.stage == 1) Text("${(e.pct * 100).toInt()}%", color = Tx, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                        Text(e.note, color = Mut, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        },
        confirmButton = {
            when (e.stage) {
                0 -> TextButton(onClick = { vm.runExport() }) { Text("⬇ Export & save", color = Acc, fontWeight = FontWeight.Bold) }
                1 -> TextButton(onClick = { vm.cancelExport() }) { Text("Cancel") }
                2 -> Row {
                    TextButton(onClick = {
                        e.outUri?.let { u -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } }
                    }) { Text("▶ Play") }
                    TextButton(onClick = {
                        e.outUri?.let { u ->
                            val i = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            runCatching { ctx.startActivity(Intent.createChooser(i, "Share video")) }
                        }
                    }) { Text("Share", color = Acc) }
                }
                else -> TextButton(onClick = { vm.export = null }) { Text("Close") }
            }
        },
        dismissButton = {
            when (e.stage) {
                0 -> Row {
                    TextButton(onClick = { vm.closeExport(); vm.togglePlay() }) { Text("▶ Preview") }
                    TextButton(onClick = { vm.closeExport() }) { Text("Close") }
                }
                2 -> TextButton(onClick = { vm.export = null }) { Text("Close") }
                else -> {}
            }
        }
    )
}

/** Shown once after the app closed unexpectedly, so the real cause can be copied and sent. */
@Composable
fun LastExitDialog(text: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        containerColor = Panel, shape = RoundedCornerShape(20.dp), tonalElevation = 0.dp,
        onDismissRequest = onClose,
        title = { Text("The app closed unexpectedly last time") },
        text = {
            Column {
                Text("Tap Copy and send this report so it can be fixed.", color = Mut, fontSize = 12.sp)
                Text(text, color = Tx, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth().background(Raise, RoundedCornerShape(8.dp)).padding(8.dp)
                        .verticalScroll(rememberScrollState()))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Auto Clipper crash report", text))
                onClose()
            }) { Text("Copy", color = Acc, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}

@Composable
fun SettingsDialog(vm: EditorViewModel, onClose: () -> Unit, onOpenModel: () -> Unit, onLogout: () -> Unit) {
    val p = App.prefs
    val ctx = LocalContext.current
    var src by remember { mutableStateOf(if (p.libSource == "local") "local" else "r2") }
    var tree by remember { mutableStateOf(p.localTree) }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u: Uri? ->
        if (u != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            tree = u.toString()
        }
    }
    AlertDialog(
        containerColor = Panel, shape = RoundedCornerShape(20.dp), tonalElevation = 0.dp,
        onDismissRequest = onClose,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Library source (bgm, best hook, transitionsfx, broll)", fontWeight = FontWeight.SemiBold, color = Tx, fontSize = 13.sp)
                OptBox("Cloudflare R2 pack (recommended)", "downloaded once with the model · instant afterwards", src == "r2", Modifier.fillMaxWidth().padding(top = 6.dp)) { src = "r2" }
                OptBox("Folder on this phone", "pick a folder that already has the four folders", src == "local", Modifier.fillMaxWidth().padding(top = 6.dp)) { src = "local" }
                when (src) {
                    "local" -> {
                        Note(if (tree.isBlank()) "No folder picked yet. Pick the folder that contains bgm, best hook, transitionsfx and broll." else "Folder: " + Uri.parse(tree).lastPathSegment)
                        PlainButton("Pick project folder", Modifier.fillMaxWidth().padding(top = 6.dp)) { pickTree.launch(null) }
                    }
                    else -> PlainButton("Download / update the pack", Modifier.fillMaxWidth().padding(top = 6.dp)) { onOpenModel() }
                }
                Note("The R2 pack downloads with the model and is reused, so Auto BGM, Hook SFX and B-roll are instant.")
                Text("Transcription model", fontWeight = FontWeight.SemiBold, color = Tx, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp))
                PlainButton("Model & assets", Modifier.fillMaxWidth().padding(top = 6.dp)) { onOpenModel() }
                var snd by remember { mutableStateOf(p.uiSounds) }
                CheckRow("Interface sounds & haptics", snd) { snd = it; p.uiSounds = it; if (it) UiSound.play(Snd.SUCCESS) }
                PlainButton("Log out", Modifier.fillMaxWidth().padding(top = 14.dp)) { onLogout() }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                p.libSource = src; p.localTree = tree
                Library.invalidate()
                vm.reloadLibrary()
                onClose()
            }) { Text("Save", color = Acc) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}
