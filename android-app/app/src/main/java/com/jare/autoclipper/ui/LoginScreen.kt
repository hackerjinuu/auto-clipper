package com.jare.autoclipper.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jare.autoclipper.App
import com.jare.autoclipper.AuthApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** Put your sign-up page here to show a "Create an account" button on the welcome screen. */
private const val SIGNUP_URL = ""

private val LoginBg = Color.Black
private val Pill2 = Color(0xFF1F1F22)   // dark secondary button
private val Sub = Color(0xFF8A8A90)     // subtitle grey

/**
 * Welcome / sign-in screen: black page, animated line field, big "Welcome",
 * white primary pill + dark secondary pill, small note at the bottom.
 */
@Composable
fun LoginScreen(onDone: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }       // 0 = welcome, 1 = email + password
    var email by remember { mutableStateOf(App.prefs.lastEmail) }
    var pass by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val fb = rememberFeedback()

    BackHandler(enabled = step == 1 && !busy) { step = 0; err = "" }

    fun submit() {
        if (busy) return
        if (email.isBlank() || pass.isEmpty()) { err = "Enter your email and password"; fb(Snd.ERROR); return }
        focus.clearFocus()
        busy = true; err = ""
        scope.launch {
            try {
                val t = withContext(Dispatchers.IO) { AuthApi.login(email.trim(), pass) }
                App.prefs.token = t
                App.prefs.lastEmail = email.trim()
                fb(Snd.SUCCESS)
                onDone()
            } catch (e: Exception) {
                err = e.message ?: "Invalid credentials"; fb(Snd.ERROR)
            } finally { busy = false }
        }
    }

    Column(
        Modifier.fillMaxSize().background(LoginBg).systemBarsPadding().imePadding().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // small round logo at the top
        Spacer(Modifier.height(14.dp))
        Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            LogoMark(16.dp, color = Color.Black, cut = Color.White)
        }

        // animated line field fills the space above the text
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val side = min(maxWidth.value * 0.82f, maxHeight.value * 0.92f).dp
            LineField(Modifier.size(side))
        }

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 6 }) togetherWith
                    (fadeOut(tween(160)) + slideOutVertically(tween(160)) { -it / 8 })
            },
            label = "login-step"
        ) { st ->
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (st == 0) {
                    Text("Welcome", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.2).sp)
                    Text("Your edits start from here", color = Sub, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(30.dp))
                    PillButton("Continue with Email", primary = true) { fb(Snd.OPEN); step = 1 }
                    if (SIGNUP_URL.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        PillButton("Create an account", primary = false) {
                            fb(Snd.TAP)
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SIGNUP_URL))) }
                        }
                    }
                } else {
                    Text("Sign in", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                    Text("Use your jarecut account", color = Sub, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(22.dp))
                    LoginField(email, { email = it; err = "" }, "Email address", KeyboardType.Email, ImeAction.Next,
                        onIme = { focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) })
                    Spacer(Modifier.height(10.dp))
                    LoginField(pass, { pass = it; err = "" }, "Password", KeyboardType.Password, ImeAction.Done, onIme = { submit() },
                        hidden = !show, trailing = if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, onTrailing = { show = !show })
                    if (err.isNotEmpty()) Text(err, color = Color(0xFFFF6B6B), fontSize = 13.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                    Spacer(Modifier.height(18.dp))
                    PillButton(if (busy) "Signing in" else "Sign in", primary = true, busy = busy) { submit() }
                    Spacer(Modifier.height(10.dp))
                    PillButton("Back", primary = false, enabled = !busy) { fb(Snd.CLOSE); step = 0; err = "" }
                }
            }
        }

        Text(
            "Your videos stay on this phone.\nCaptions are made offline, on device.",
            color = Color(0xFF5E5E64), fontSize = 11.sp, lineHeight = 15.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 22.dp, bottom = 14.dp)
        )
    }
}

@Composable
internal fun PillButton(text: String, primary: Boolean, busy: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val fg = if (primary) Color.Black else Color.White
    Row(
        Modifier.widthIn(max = 420.dp).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp))
            .background(if (primary) Color.White else Pill2)
            .clickable(enabled = enabled && !busy) { onClick() },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = fg); Spacer(Modifier.size(10.dp)) }
        Text(text, color = if (enabled) fg else fg.copy(alpha = 0.4f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LoginField(
    value: String, onChange: (String) -> Unit, hint: String, type: KeyboardType, ime: ImeAction, onIme: () -> Unit,
    hidden: Boolean = false, trailing: ImageVector? = null, onTrailing: () -> Unit = {},
) {
    OutlinedTextField(
        value, onChange, Modifier.widthIn(max = 420.dp).fillMaxWidth(),
        placeholder = { Text(hint, color = Color(0xFF6E6E75), fontSize = 15.sp) },
        singleLine = true,
        shape = RoundedCornerShape(26.dp),
        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 15.sp, fontFamily = AppFont),
        visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ime),
        keyboardActions = KeyboardActions(onNext = { onIme() }, onDone = { onIme() }),
        trailingIcon = trailing?.let { ic -> { Icon(ic, "Show password", tint = Color(0xFF8A8A90), modifier = Modifier.clickable { onTrailing() }.padding(8.dp)) } },
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Pill2, unfocusedContainerColor = Pill2,
            focusedBorderColor = Color(0x66FFFFFF), unfocusedBorderColor = Color.Transparent,
            cursorColor = Color.White, focusedTextColor = Color.White, unfocusedTextColor = Color.White,
        ),
    )
}

/* ---------------- animated line field ---------------- */

private const val N = 22                 // 22 × 22 short strokes
private const val CYCLE_MS = 9000        // one full loop, like the reference

private fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
private fun bell(x: Float, c: Float, w: Float): Float { val d = (x - c) / w; return exp(-d * d) }

/**
 * A grid of short white strokes that loops: tight small grid → expands and turns into a swirl →
 * scatters → falls back into the small grid. The change sweeps diagonally across the grid.
 */
@Composable
fun LineField(modifier: Modifier) {
    val inf = rememberInfiniteTransition(label = "field")
    val phi by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(CYCLE_MS, easing = LinearEasing)), label = "phi")
    // fixed random numbers per stroke (position jitter x/y, angle jitter)
    val rnd = remember { val r = java.util.Random(7); FloatArray(N * N * 3) { r.nextFloat() * 2f - 1f } }
    Canvas(modifier) {
        val R = size.minDimension * 0.5f * 0.94f
        val cx = size.width / 2; val cy = size.height / 2
        val cell = 2 * R / (N - 1)
        val sw = 1.4.dp.toPx()
        val spin = 0.35f * sin(phi * 2f * PI.toFloat())
        for (i in 0 until N) for (j in 0 until N) {
            val u = -1f + 2f * i / (N - 1); val v = -1f + 2f * j / (N - 1)
            val k = (i * N + j) * 3
            // each stroke runs a little behind the one above-left of it: the change sweeps across
            var p = phi - 0.14f * (u + v + 2f) / 4f
            p -= kotlin.math.floor(p)
            val e = smooth(0.10f, 0.36f, p) * (1f - smooth(0.58f, 0.86f, p))   // 0 tight grid … 1 swirl
            val ch = 0.55f * bell(p, 0.22f, 0.07f) + bell(p, 0.70f, 0.09f)     // scatter while changing
            val sc = 0.40f + 0.60f * e
            val x = cx + (u * sc + ch * rnd[k] * 0.16f) * R
            val y = cy + (v * sc + ch * rnd[k + 1] * 0.16f) * R
            val aTight = (PI / 2.6).toFloat()
            val aSwirl = atan2(v, u) + (PI / 2).toFloat() + spin * e
            val ax = (1 - e) * cos(aTight) + e * cos(aSwirl); val ay = (1 - e) * sin(aTight) + e * sin(aSwirl)
            val a = atan2(ay, ax) + ch * rnd[k + 2] * 0.65f * PI.toFloat()
            val len = cell * (0.13f + 0.30f * e)
            val dx = cos(a) * len; val dy = sin(a) * len
            drawLine(Color.White.copy(alpha = (0.92f - 0.22f * ch).coerceIn(0.3f, 1f)), Offset(x - dx, y - dy), Offset(x + dx, y + dy), sw, StrokeCap.Round)
        }
    }
}
