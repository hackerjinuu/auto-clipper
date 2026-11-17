package com.jare.autoclipper.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.text.style.TextAlign
import com.jare.autoclipper.model.AssetPack
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import com.jare.autoclipper.App
import com.jare.autoclipper.AuthApi
import com.jare.autoclipper.asr.ParakeetEngine
import com.jare.autoclipper.model.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** plain backdrop (the grid and glow were removed in the flat redesign) */
@Composable
fun GridBackdrop() {}

@Composable
fun ModelScreen(onContinue: () -> Unit) {
    val ctx = LocalContext.current
    val mm = remember { ModelManager(ctx) }
    val flow = remember { mm.progress() }
    val infos by flow.collectAsState(initial = emptyList())
    val wi = infos.firstOrNull()
    var ready by remember { mutableStateOf(mm.isReady()) }
    var pack by remember { mutableStateOf(mm.packReady()) }
    var base by remember { mutableStateOf(App.prefs.modelBase) }
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { mm.start() }
    LaunchedEffect(wi?.state) { if (wi?.state == WorkInfo.State.SUCCEEDED) { ready = mm.isReady(); pack = mm.packReady() } }
    val running = wi != null && (wi.state == WorkInfo.State.RUNNING || wi.state == WorkInfo.State.ENQUEUED || wi.state == WorkInfo.State.BLOCKED)
    val done = wi?.progress?.getLong("done", 0L) ?: 0L
    val total = wi?.progress?.getLong("total", 0L) ?: 0L
    val file = wi?.progress?.getString("file") ?: ""
    val error = if (wi?.state == WorkInfo.State.FAILED) (wi?.outputData?.getString("error") ?: "unknown error") else null
    fun startDownload() {
        App.prefs.modelBase = base.trim().ifEmpty { ModelManager.DEFAULT_BASE }
        if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS) else mm.start()
    }

    val pct: Float? = when {
        running && total > 1 -> (done.toFloat() / total).coerceIn(0f, 1f)
        running -> null
        else -> ((if (ready) 0.5f else 0f) + (if (pack) 0.5f else 0f)).coerceAtLeast(0.04f)
    }
    var advanced by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().imePadding().padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(14.dp))
        Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            LogoMark(16.dp, color = Color.Black, cut = Color.White)
        }
        Spacer(Modifier.height(56.dp))
        ChargeLoader(
            when { running -> "Loading."; ready && pack -> "Ready."; else -> "Setup." },
            pct, size = 250.dp
        )
        Spacer(Modifier.height(26.dp))
        Text(
            when {
                running && wi?.state == WorkInfo.State.ENQUEUED -> "Waiting for network"
                running && total > 1 -> "${(pct!! * 100).toInt()}%"
                running -> "Getting ready"
                ready && pack -> "All set"
                else -> "One-time setup"
            },
            color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp
        )
        Text(
            when {
                running && total > 1 -> "${done / 1_048_576} / ${total / 1_048_576} MB" + (if (file.isNotEmpty()) " · $file" else "")
                running -> file.ifEmpty { "Checking your R2 pack" }
                ready && pack -> "Speech model and ${AssetPack.sizeOnDisk(ctx) / 1_048_576} MB of music, sounds and B-roll are on this phone."
                else -> "Download the speech model (≈670 MB) and your music, sounds and B-roll once. After that everything runs offline."
            },
            color = Color(0xFF8A8A90), fontSize = 14.sp, lineHeight = 19.sp, textAlign = TextAlign.Center, maxLines = 3,
            modifier = Modifier.widthIn(max = 360.dp).padding(top = 6.dp)
        )
        if (error != null) Text("Download failed: $error", color = Color(0xFFFF6B6B), fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp).padding(top = 10.dp))

        Spacer(Modifier.height(36.dp))
        if (running) {
            PillButton("Use the editor meanwhile", primary = true) { onContinue() }
            Spacer(Modifier.height(10.dp))
            PillButton("Cancel download", primary = false) { mm.cancel() }
        } else if (!ready || !pack) {
            PillButton(if (error != null) "Try again" else "Download (one time)", primary = true) { startDownload() }
            Spacer(Modifier.height(10.dp))
            PillButton(if (ready) "Continue to editor" else "Skip for now", primary = false) { onContinue() }
        } else {
            PillButton("Continue to editor", primary = true) { onContinue() }
            Spacer(Modifier.height(10.dp))
            PillButton("Check for new music & B-roll", primary = false) { startDownload() }
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(if (advanced) "Hide advanced" else "Advanced", color = Color(0xFF8A8A90), fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { advanced = !advanced }.padding(8.dp))
            if (ready && !running) Text("Delete model", color = Color(0xFF8A8A90), fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { ParakeetEngine.unload(); mm.delete(); ready = false }.padding(8.dp))
        }
        if (advanced) OutlinedTextField(base, { base = it }, Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(top = 4.dp),
            label = { Text("R2 bucket address") }, singleLine = true, shape = RoundedCornerShape(16.dp), colors = fieldColors())
        Spacer(Modifier.height(14.dp))
    }
}
