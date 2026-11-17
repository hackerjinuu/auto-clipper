package com.jare.autoclipper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Flare
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jare.autoclipper.editor.EditorViewModel
import kotlin.math.roundToInt

private val brCols = listOf(Color(0xFF2F6FA8), Color(0xFF2A6396), Color(0xFF3579B5), Color(0xFF285E8E))
private val CapCol = Color(0xFFB8732E)      // text track (warm)
private val HookSfxCol = Color(0xFF7A4FD0)  // hook SFX
private val TransSfxCol = Color(0xFF2A9D8F) // transition SFX
private val MusicCol = Color(0xFF3FBF8F)
private val FxCol = Color(0xFFC2416B)       // overlay effects
private val LaneBg = Color(0xFF141416)

private fun Double.dpAt(pps: Float): Dp = (this * pps).toFloat().coerceIn(0f, 60000f).dp

@Composable
private fun BoxScope.Block(start: Double, len: Double, pps: Float, color: Color, label: String, dark: Boolean = true, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(5.dp)
    var m = Modifier.offset(x = start.dpAt(pps)).width(len.dpAt(pps).coerceAtLeast(4.dp)).fillMaxHeight()
        .padding(horizontal = 0.5.dp).clip(shape).background(color)
    if (selected) m = m.border(2.dp, Color.White, shape)
    val fb = rememberFeedback()
    if (onClick != null) m = m.clickable { fb(Snd.TAP); onClick() }
    Box(m, contentAlignment = Alignment.CenterStart) {
        Text(label, fontSize = 10.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, fontWeight = FontWeight.Medium,
            color = if (dark) Color(0xFF10120A) else Color.White, modifier = Modifier.padding(horizontal = 6.dp))
    }
}

@Composable
private fun Lane(width: Dp, h: Dp, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.padding(vertical = 2.dp).width(width).height(h).clip(RoundedCornerShape(6.dp)).background(LaneBg), content = content)
}

@Composable
private fun LaneIcon(icon: ImageVector, h: Dp) {
    Box(Modifier.padding(vertical = 2.dp).height(h).width(22.dp), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Mut, modifier = Modifier.size(14.dp))
    }
}

/**
 * Editor timeline: the playhead stays in the middle and the tracks scroll under it.
 * Scrolling scrubs the preview; while playing, the tracks follow the playhead.
 * Tap a B-roll or SFX block to select it (actions appear in the bar below).
 */
@Composable
fun Timeline(vm: EditorViewModel, pps: Float, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val rev = vm.rev // recompose on edits (not on every playhead step)
    val L = vm.clipLen().coerceAtLeast(0.1)
    val pxPerSec = with(LocalDensity.current) { pps.dp.toPx() }
    val scroll = rememberScrollState()
    val byCode = remember { booleanArrayOf(false) }

    LaunchedEffect(pxPerSec) {
        snapshotFlow { scroll.value }.collect { v -> if (scroll.isScrollInProgress && !byCode[0] && !vm.playing) vm.scrubTo(v / pxPerSec.toDouble()) }
    }
    LaunchedEffect(pxPerSec, L) {
        snapshotFlow { vm.phRel }.collect { p ->
            if (!scroll.isScrollInProgress) { byCode[0] = true; try { scroll.scrollTo((p * pxPerSec).roundToInt()) } finally { byCode[0] = false } }
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth().background(Bg)) {
        val half = maxWidth / 2
        val cw = L.dpAt(pps).coerceAtLeast(8.dp)
        Row(Modifier.horizontalScroll(scroll).padding(vertical = 6.dp)) {
            Spacer(Modifier.width(half))
            Column(Modifier.width(cw)) {
                // ruler
                Box(Modifier.width(cw).height(16.dp)) {
                    val step = listOf(1, 2, 5, 10, 15, 30, 60).firstOrNull { it * pps >= 52 } ?: 60
                    var t = 0
                    while (t <= L) {
                        Text("%d:%02d".format(t / 60, t % 60), color = Color(0xFF6E6E75), fontSize = 9.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, modifier = Modifier.offset(x = t.toDouble().dpAt(pps)))
                        t += step
                    }
                }
                // video: thumbnails + silence cuts
                Lane(cw, 54.dp) {
                    val span = vm.thumbSpan
                    vm.thumbs.forEach { (rel, img) ->
                        Image(img, null, contentScale = ContentScale.Crop,
                            modifier = Modifier.offset(x = rel.dpAt(pps)).width(span.dpAt(pps) + 1.dp).fillMaxHeight())
                    }
                    if (vm.cutK != null) vm.ranges().drop(1).forEach { r ->
                        Box(Modifier.offset(x = r.o.dpAt(pps)).width(2.dp).fillMaxHeight().background(Acc))
                    }
                    if (vm.thumbs.isEmpty()) Text(if (vm.hasVideo) "Loading frames…" else "Import a video to start", color = Mut, fontSize = 11.sp,
                        maxLines = 1, softWrap = false, modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp))
                }
                Lane(cw, 24.dp) {
                    vm.cues.forEach { c ->
                        val s = vm.otime(c.s); val e = vm.otime(c.e)
                        if (e > s) Block(s, e - s, pps, CapCol, c.words.joinToString(" "), dark = false)
                    }
                }
                Lane(cw, 26.dp) {
                    vm.broll.filter { it.dur > 0 }.forEach { b ->
                        val sel = vm.selBroll == b.id
                        Block(b.start, b.dur, pps, brCols[(b.asset.id and 0x7fffffff) % 4], b.asset.name, dark = false, selected = sel) { vm.select(broll = if (sel) null else b.id) }
                    }
                }
                Lane(cw, 22.dp) {
                    vm.overlays.forEach { o ->
                        val sel = vm.selOv == o.id
                        val k = com.jare.autoclipper.editor.Fx.byId(o.kind)
                        Block(o.t, o.dur, pps, FxCol, k?.name ?: o.kind, dark = false, selected = sel) { vm.select(ov = if (sel) null else o.id) }
                    }
                }
                Lane(cw, 22.dp) {
                    vm.sfxRows().forEachIndexed { i, x ->
                        val sel = vm.selSfx == i
                        Block(x.t, maxOf(0.3, x.dur), pps, if (x.tr) TransSfxCol else HookSfxCol, x.s.name.substringBeforeLast('.'), dark = false, selected = sel) { vm.select(sfx = if (sel) null else i) }
                    }
                }
                Lane(cw, 30.dp) {
                    val name = vm.bgmName()
                    Canvas(Modifier.fillMaxSize()) {
                        val n = (size.width / 4f).toInt().coerceIn(1, 1500)
                        val wf = vm.waveform(n)
                        if (wf.isNotEmpty()) {
                            val bar = size.width / n
                            val on = name != null
                            for (i in wf.indices) {
                                val hh = size.height * wf[i] * 0.9f
                                val y = (size.height - hh) / 2
                                drawRoundRect(if (on) MusicCol else Color(0xFF3A3A42), Offset(i * bar, y), Size(bar * 0.6f, hh), CornerRadius(1f, 1f))
                            }
                        }
                    }
                    if (name != null) Text(name, color = Color.White, fontSize = 10.sp, maxLines = 1, softWrap = false, modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp))
                }
            }
            Spacer(Modifier.width(half))
        }
        // track icons (fixed on the left)
        Column(Modifier.align(Alignment.TopStart).padding(top = 22.dp).background(Color(0xE60A0A0B), RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))) {
            LaneIcon(Icons.Rounded.Videocam, 54.dp); LaneIcon(Icons.Rounded.ClosedCaption, 24.dp); LaneIcon(Icons.Rounded.Movie, 26.dp)
            LaneIcon(Icons.Rounded.Flare, 22.dp); LaneIcon(Icons.Rounded.GraphicEq, 22.dp); LaneIcon(Icons.Rounded.MusicNote, 30.dp)
        }
        // playhead (white line with a soft glow)
        Box(Modifier.align(Alignment.TopCenter).width(2.dp).fillMaxHeight().background(Color.White))
        Box(Modifier.align(Alignment.TopCenter).size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.White))
    }
}
