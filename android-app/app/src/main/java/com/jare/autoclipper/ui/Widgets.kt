package com.jare.autoclipper.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import com.jare.autoclipper.R
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ---------------- palette: flat dark editor (CapCut-like), white primary, cyan for selection ---------------- */
val Bg = Color(0xFF0A0A0B)
val Panel = Color(0xFF161618)          // sheets / bars
val Raise = Color(0xFF232326)          // chips, inputs, rows
val Line = Color(0xFF2C2C30)           // hairlines (used sparingly)
val Tx = Color(0xFFF5F5F7)
val Mut = Color(0xFF8E8E95)
val Acc = Color(0xFFFFFFFF)            // primary buttons
val OnAcc = Color(0xFF0A0A0B)
val Glow = Color(0xFF34D3F5)           // selection / active accent (cyan)
val GlowDeep = Color(0xFF169BC0)
val Danger = Color(0xFFFF5A5F)

val AppFont = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal), Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold), Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold), Font(R.font.inter_extrabold, FontWeight.Black),
)
/** numbers (time, %, values): same font, tabular figures so they do not jump */
val Mono = AppFont

@Composable
fun ClipperTheme(content: @Composable () -> Unit) {
    val base = Typography()
    fun TextStyle.inter() = copy(fontFamily = AppFont, fontFeatureSettings = "tnum")
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Acc, onPrimary = OnAcc, background = Bg, onBackground = Tx,
            surface = Panel, onSurface = Tx, surfaceVariant = Raise, onSurfaceVariant = Mut, outline = Line, secondary = Glow,
            surfaceContainerHigh = Raise, surfaceContainer = Panel, surfaceContainerHighest = Raise,
        ),
        typography = Typography(
            displayLarge = base.displayLarge.inter(), displayMedium = base.displayMedium.inter(), displaySmall = base.displaySmall.inter(),
            headlineLarge = base.headlineLarge.inter(), headlineMedium = base.headlineMedium.inter(), headlineSmall = base.headlineSmall.inter(),
            titleLarge = base.titleLarge.inter(), titleMedium = base.titleMedium.inter(), titleSmall = base.titleSmall.inter(),
            bodyLarge = base.bodyLarge.inter(), bodyMedium = base.bodyMedium.inter(), bodySmall = base.bodySmall.inter(),
            labelLarge = base.labelLarge.inter(), labelMedium = base.labelMedium.inter(), labelSmall = base.labelSmall.inter(),
        ),
        content = content,
    )
}

/** flat card / chip surface (kept the old name so every screen picks up the new look) */
fun Modifier.glass(shape: Shape = RoundedCornerShape(16.dp), strong: Boolean = false): Modifier = this
    .clip(shape)
    .background(if (strong) Panel else Raise)

/** glows were removed from the design: flat surfaces read cleaner on a phone */
@Suppress("UNUSED_PARAMETER")
fun Modifier.softGlow(color: Color, radius: Dp = 24.dp, alpha: Float = 0.35f): Modifier = this

/** small section label */
@Composable
fun Kicker(t: String, color: Color = Mut, modifier: Modifier = Modifier) =
    Text(t, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(bottom = 12.dp).glass(RoundedCornerShape(18.dp), strong = true).padding(16.dp)) {
        Kicker(title)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
fun Label(t: String) = Kicker(t, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))

@Composable
fun Note(t: String) { if (t.isNotEmpty()) Text(t, color = Mut, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp)) }

/** progress bar: flat track, white fill (sweeps when the length is unknown) */
@Composable
fun GlowProgress(progress: Float?, modifier: Modifier = Modifier, color: Color = Acc, height: Dp = 4.dp) {
    val anim by animateFloatAsState((progress ?: 0f).coerceIn(0f, 1f), tween(350), label = "p")
    val inf = rememberInfiniteTransition(label = "indet")
    val run by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "run")
    Canvas(modifier.fillMaxWidth().height(height + 8.dp)) {
        val h = height.toPx(); val y = (size.height - h) / 2; val r = CornerRadius(h / 2)
        drawRoundRect(Color(0x26FFFFFF), Offset(0f, y), Size(size.width, h), r)
        val (x0, w) = if (progress == null) { val seg = size.width * 0.3f; (run * (size.width + seg) - seg) to seg } else 0f to size.width * anim
        val x = x0.coerceAtLeast(0f); val ww = (x0 + w).coerceAtMost(size.width) - x
        if (ww > 0f) drawRoundRect(color, Offset(x, y), Size(ww, h), r)
    }
}

/** jarecut mark: square split by a diagonal (from the brand film) */
@Composable
fun LogoMark(size: Dp = 28.dp, color: Color = Acc, cut: Color = Bg) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width; val sw = s * 0.08f
        drawRect(color, Offset(sw / 2, sw / 2), Size(s - sw, s - sw), style = Stroke(sw))
        val tri = Path().apply { moveTo(s - sw, sw); lineTo(s - sw, s - sw); lineTo(sw, s - sw); close() }
        drawPath(tri, color)
        drawLine(cut, Offset(sw * 2.4f, s - sw * 2.4f), Offset(s - sw * 2.4f, sw * 2.4f), strokeWidth = sw * 1.2f)
    }
}

@Composable
fun Brand(size: Dp = 26.dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LogoMark(size)
        Spacer(Modifier.width(9.dp))
        Text("jarecut", color = Tx, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.85f).sp, letterSpacing = (-0.5).sp)
    }
}

@Composable
fun LSlider(label: String, value: Double, range: ClosedFloatingPointRange<Double>, step: Double = 0.0, fmt: (Double) -> String = { "%.0f".format(it) }, onChange: (Double) -> Unit, onDone: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Tx, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(fmt(value), color = Mut, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
    val steps = if (step > 0) (((range.endInclusive - range.start) / step).toInt() - 1).coerceIn(0, 200) else 0
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.toDouble()) },
        onValueChangeFinished = onDone,
        valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
        steps = steps,
        colors = SliderDefaults.colors(thumbColor = Acc, activeTrackColor = Acc, inactiveTrackColor = Color(0xFF3A3A3F),
            activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
        modifier = Modifier.height(30.dp)
    )
}

/** row with a label and an iOS-style switch (replaces checkboxes) */
@Composable
fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val fb = rememberFeedback()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp))
            .clickable { fb(Snd.TOGGLE); onChange(!checked) }.padding(horizontal = 2.dp, vertical = 4.dp)
    ) {
        Text(label, color = Tx, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { fb(Snd.TOGGLE); onChange(it) },
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Glow, checkedBorderColor = Glow, uncheckedThumbColor = Color(0xFFB8B8BE), uncheckedTrackColor = Raise, uncheckedBorderColor = Raise))
    }
}

@Composable
fun <T> Choice(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val fb = rememberFeedback()
    Column(modifier) {
        Label(label)
        Box {
            Row(
                Modifier.fillMaxWidth().height(46.dp).glass(RoundedCornerShape(12.dp)).clickable { fb(Snd.TAP); open = true }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(options.firstOrNull { it.first == selected }?.second ?: selected.toString(), color = Tx, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Text("⌄", color = Mut, fontSize = 16.sp)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (v, t) -> DropdownMenuItem(text = { Text(t, color = if (v == selected) Acc else Tx) }, onClick = { fb(Snd.TAP); open = false; onSelect(v) }) }
            }
        }
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Glow, unfocusedBorderColor = Color.Transparent, focusedContainerColor = Raise, unfocusedContainerColor = Raise,
    cursorColor = Acc, focusedLabelColor = Tx, unfocusedLabelColor = Mut, focusedTextColor = Tx, unfocusedTextColor = Tx,
)

@Composable
fun NumField(label: String, value: Double, modifier: Modifier = Modifier, onValue: (Double) -> Unit) {
    fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)
    var txt by remember { mutableStateOf(fmt(value)) }
    LaunchedEffect(value) { if (txt.replace(',', '.').toDoubleOrNull() != value) txt = fmt(value) }
    OutlinedTextField(
        value = txt,
        onValueChange = { v -> txt = v; v.replace(',', '.').toDoubleOrNull()?.let(onValue) },
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = fieldColors(),
        textStyle = TextStyle(fontFamily = AppFont, fontSize = 14.sp, color = Tx),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier
    )
}

/** primary action: white pill, black text, soft light behind it */
@Composable
fun AccButton(text: String, busy: Boolean = false, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val fb = rememberFeedback()
    val on = enabled && !busy
    Box(
        modifier.height(46.dp)
            .clip(RoundedCornerShape(12.dp)).background(if (on) Acc else Color(0xFF5A5A60))
            .clickable(enabled = on) { fb(Snd.TAP); onClick() }.padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = OnAcc)
            Spacer(Modifier.width(10.dp)); Text("Working…", color = OnAcc, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        } else Text(text, color = OnAcc, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
    }
}

/** secondary action: frosted glass pill */
@Composable
fun PlainButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val fb = rememberFeedback()
    Box(
        modifier.height(46.dp).glass(RoundedCornerShape(12.dp)).clickable(enabled = enabled) { fb(Snd.TAP); onClick() }.padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = if (enabled) Tx else Mut, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

@Composable
fun Row2(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = content)
