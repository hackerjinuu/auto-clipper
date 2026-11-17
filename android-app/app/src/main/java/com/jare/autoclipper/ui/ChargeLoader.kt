package com.jare.autoclipper.ui

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin

/** liquid colour for a fill level: red → orange → yellow → green */
fun chargeColor(p: Float): Color {
    val stops = listOf(0f to Color(0xFFFF2A2A), 0.4f to Color(0xFFFF7A1A), 0.82f to Color(0xFFF5E31A), 1f to Color(0xFF3BFF2A))
    val x = p.coerceIn(0f, 1f)
    for (i in 1 until stops.size) {
        val (a, ca) = stops[i - 1]; val (b, cb) = stops[i]
        if (x <= b) return lerp(ca, cb, (x - a) / (b - a))
    }
    return stops.last().second
}

/**
 * Battery-style loader: dark rounded card, bold title, 0 / 50 / 100 scale and a glass capsule
 * filled with glowing liquid whose colour goes red → yellow → green as it fills.
 * progress = null → it fills and drains in a loop (work of unknown length).
 */
@Composable
fun ChargeLoader(title: String, progress: Float?, modifier: Modifier = Modifier, size: Dp = 240.dp) {
    val inf = rememberInfiniteTransition(label = "charge")
    val wave by inf.animateFloat(0f, 6.2832f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "wave")
    val loop by inf.animateFloat(0.06f, 1f, infiniteRepeatable(tween(3600), RepeatMode.Reverse), label = "loop")
    val target = progress?.coerceIn(0f, 1f) ?: loop
    val p by animateFloatAsState(target, tween(if (progress == null) 0 else 450), label = "level")
    val col = chargeColor(p)

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { drawCharge(p, col, wave) }
        Column(Modifier.fillMaxSize().padding(horizontal = size * 0.12f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color.White, fontSize = (size.value * 0.12f).sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp,
                maxLines = 1, modifier = Modifier.padding(top = size * 0.15f))
            Row(Modifier.fillMaxWidth().padding(top = size * 0.10f, start = size * 0.02f, end = size * 0.02f)) {
                val st = androidx.compose.ui.text.TextStyle(color = Color(0xFF6E6E6E), fontSize = (size.value * 0.055f).sp)
                Text("0", style = st, modifier = Modifier.weight(1f))
                Text("50", style = st, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Text("100", style = st, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun DrawScope.drawCharge(p: Float, col: Color, wave: Float) {
    val s = size.width
    val pad = s * 0.06f                      // room for the glow
    val cardL = pad; val cardT = pad * 0.6f; val cardW = s - 2 * pad; val cardH = s - 2 * pad
    val r = cardW * 0.24f

    // coloured glow under the card (stronger the fuller it is)
    drawIntoCanvas { c ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = col.copy(alpha = 0.55f + 0.25f * p).toArgb()
            maskFilter = BlurMaskFilter(s * 0.09f, BlurMaskFilter.Blur.NORMAL)
        }
        c.nativeCanvas.drawRoundRect(cardL + cardW * 0.1f, cardT + cardH * 0.35f, cardL + cardW * 0.9f, cardT + cardH + s * 0.035f, r, r, paint)
    }

    // card: thick dark rim + inner face that picks up the liquid colour at the bottom
    drawRoundRect(Color(0xFF1C1C1C), Offset(cardL, cardT), Size(cardW, cardH), CornerRadius(r))
    val rim = cardW * 0.035f
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF3E3E3E), Color(0xFF262626), lerp(Color(0xFF1A1A1A), col, 0.16f)), startY = cardT, endY = cardT + cardH),
        Offset(cardL + rim, cardT + rim), Size(cardW - 2 * rim, cardH - 2 * rim), CornerRadius(r - rim)
    )
    // faint dot texture on the lower half of the face
    val dot = cardW * 0.035f
    var yy = cardT + cardH * 0.48f
    while (yy < cardT + cardH - rim * 2) {
        var xx = cardL + rim * 3
        while (xx < cardL + cardW - rim * 3) { drawCircle(Color(0x0FFFFFFF), dot * 0.12f, Offset(xx, yy)); xx += dot }
        yy += dot
    }

    // capsule track
    val tL = cardL + cardW * 0.065f; val tW = cardW * 0.87f
    val tH = cardH * 0.37f; val tT = cardT + cardH - tH - cardW * 0.065f
    val cap = RoundRect(tL, tT, tL + tW, tT + tH, CornerRadius(tH / 2))
    val capPath = Path().apply { addRoundRect(cap) }
    drawPath(capPath, lerp(Color(0xFF2E2A2A), col, 0.10f).copy(alpha = 0.9f))

    clipPath(capPath) {
        // dot texture inside the empty glass
        var y = tT + dot * 0.6f
        while (y < tT + tH) { var x = tL + dot * 0.6f; while (x < tL + tW) { drawCircle(Color(0x14FFFFFF), dot * 0.1f, Offset(x, y)); x += dot * 0.75f }; y += dot * 0.75f }

        // liquid: dark at the back, bright at the front edge, which wobbles a little
        val fx = tL + tW * p.coerceIn(0f, 1f)
        if (p > 0.004f) {
            val bulge = tH * 0.10f
            val liquid = Path().apply {
                moveTo(tL - 2f, tT)
                lineTo(fx + bulge * sin(wave), tT)
                cubicTo(fx - bulge * 1.2f + bulge * sin(wave + 1f), tT + tH * 0.35f, fx + bulge * 1.4f + bulge * sin(wave + 2f), tT + tH * 0.65f, fx + bulge * sin(wave + 3f), tT + tH)
                lineTo(tL - 2f, tT + tH); close()
            }
            val dark = lerp(Color.Black, col, 0.35f)
            drawPath(liquid, Brush.horizontalGradient(listOf(dark, lerp(dark, col, 0.6f), col, lerp(col, Color.White, 0.25f)), startX = tL, endX = fx + bulge))
            // gloss: light along the top, shade along the bottom
            drawPath(liquid, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.32f), Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.28f)), startY = tT, endY = tT + tH))
            // bright meniscus line at the front of the liquid
            val edge = Path().apply {
                moveTo(fx + bulge * sin(wave), tT)
                cubicTo(fx - bulge * 1.2f + bulge * sin(wave + 1f), tT + tH * 0.35f, fx + bulge * 1.4f + bulge * sin(wave + 2f), tT + tH * 0.65f, fx + bulge * sin(wave + 3f), tT + tH)
            }
            drawPath(edge, lerp(col, Color.White, 0.35f), style = Stroke(width = tH * 0.05f))
        }
        // glass highlight over everything
        drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent), startY = tT, endY = tT + tH * 0.45f),
            Offset(tL + tH * 0.25f, tT + tH * 0.06f), Size(tW - tH * 0.5f, tH * 0.32f), CornerRadius(tH * 0.16f))
    }
    // glass rim, tinted by the liquid
    drawPath(capPath, lerp(Color(0xFF8A7070), col, 0.5f).copy(alpha = 0.55f), style = Stroke(width = cardW * 0.008f))
}
