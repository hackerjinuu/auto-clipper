package com.jare.autoclipper.gl

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.min
import kotlin.math.roundToInt

/** A 2D texture made from a Bitmap (premultiplied alpha). */
class Tex(val id: Int, var w: Int, var h: Int) {
    fun upload(b: Bitmap) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        if (b.width == w && b.height == h) GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, b)
        else { GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0); w = b.width; h = b.height }
    }
    fun delete() { GLES30.glDeleteTextures(1, intArrayOf(id), 0) }

    companion object {
        fun create(b: Bitmap? = null): Tex {
            val t = IntArray(1); GLES30.glGenTextures(1, t, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            val tex = Tex(t[0], -1, -1)
            if (b != null) tex.upload(b)
            return tex
        }

        fun createOes(): Int {
            val t = IntArray(1); GLES30.glGenTextures(1, t, 0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0])
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            return t[0]
        }
    }
}

/**
 * Tiny 2D GPU drawing layer in reel coordinates (1080×1920, y down).
 * The 9:16 reel is letterboxed into whatever surface is current.
 */
class GlCanvas {
    companion object {
        const val W = 1080f
        const val H = 1920f
        private const val VS = """#version 300 es
uniform mat4 uMvp; uniform mat4 uTex;
in vec4 aPos; in vec4 aTc; out vec2 vTc;
void main() { gl_Position = uMvp * aPos; vTc = (uTex * aTc).xy; }"""
        private const val FS_OES = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES sTex; uniform float uAlpha;
uniform sampler2D sMask; uniform vec4 uMask;
in vec2 vTc; out vec4 o;
float mask() { vec2 uv = vec2((gl_FragCoord.x - uMask.x) * uMask.z, 1.0 - (gl_FragCoord.y - uMask.y) * uMask.w); return smoothstep(0.3, 0.7, texture(sMask, uv).r); }
void main() { vec4 c = texture(sTex, vTc); o = vec4(c.rgb, 1.0) * uAlpha * mask(); }"""
        private const val FS_2D = """#version 300 es
precision mediump float;
uniform sampler2D sTex; uniform float uAlpha;
uniform sampler2D sMask; uniform vec4 uMask;
in vec2 vTc; out vec4 o;
float mask() { vec2 uv = vec2((gl_FragCoord.x - uMask.x) * uMask.z, 1.0 - (gl_FragCoord.y - uMask.y) * uMask.w); return smoothstep(0.3, 0.7, texture(sMask, uv).r); }
void main() { o = texture(sTex, vTc) * uAlpha * mask(); }"""
        private const val FS_COL = """#version 300 es
precision mediump float;
uniform vec4 uColor; out vec4 o;
void main() { o = uColor; }"""
        /** overlay effects (flash, film burn, light leak …) drawn procedurally; premultiplied, alpha 0 = additive */
        private const val FS_FX = """#version 300 es
precision highp float;
uniform int uKind; uniform float uP; uniform float uT; uniform float uAmt; uniform float uAsp; uniform float uSeed;
uniform sampler2D sMask; uniform vec4 uMask;
in vec2 vTc; out vec4 o;
float mask() { vec2 uv = vec2((gl_FragCoord.x - uMask.x) * uMask.z, 1.0 - (gl_FragCoord.y - uMask.y) * uMask.w); return smoothstep(0.3, 0.7, texture(sMask, uv).r); }
float h1(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float n2(vec2 p) { vec2 i = floor(p); vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
  return mix(mix(h1(i), h1(i + vec2(1.0, 0.0)), f.x), mix(h1(i + vec2(0.0, 1.0)), h1(i + vec2(1.0, 1.0)), f.x), f.y); }
float fbm(vec2 p) { float s = 0.0; float a = 0.5; for (int i = 0; i < 5; i++) { s += a * n2(p); p *= 2.03; a *= 0.5; } return s; }
float env(float a, float d) { return smoothstep(0.0, a, uP) * (1.0 - smoothstep(d, 1.0, uP)); }
void main() {
  vec2 uv = vTc; vec2 q = vec2(uv.x * uAsp, uv.y);
  vec3 c = vec3(0.0); float al = 0.0; float add = 0.0;
  if (uKind == 0) { // flash
    al = env(0.06, 0.1); c = vec3(1.0);
  } else if (uKind == 1) { // film burn: a hot, ragged blob sweeping across
    float e = env(0.12, 0.45);
    vec2 cen = vec2(mix(-0.3, 1.3, uP) * uAsp, 0.5 + 0.35 * sin(uSeed * 6.283));
    float f = fbm(q * 2.6 + vec2(uT * 0.9, uSeed * 13.0));
    float b = smoothstep(1.0, 0.05, length(q - cen) - f * 0.55) * e;
    c = mix(vec3(0.75, 0.08, 0.0), vec3(1.0, 0.6, 0.12), smoothstep(0.2, 0.7, b));
    c = mix(c, vec3(1.0, 0.97, 0.88), smoothstep(0.75, 1.0, b));
    al = clamp(b * 1.5, 0.0, 1.0); add = 0.55;
  } else if (uKind == 2) { // light leak: warm glows drifting in from the edges
    float e = env(0.3, 0.45);
    float g1 = smoothstep(1.05, 0.0, length(q - vec2((0.05 + 0.5 * uP) * uAsp, 0.95)));
    float g2 = smoothstep(0.85, 0.0, length(q - vec2((1.0 - 0.45 * uP) * uAsp, 0.1)));
    c = vec3(1.0, 0.5, 0.15) * g1 + vec3(1.0, 0.22, 0.4) * g2;
    al = clamp((g1 * 0.9 + g2 * 0.7) * e, 0.0, 1.0); c = c / max(g1 + g2, 0.001); add = 0.85;
  } else if (uKind == 3) { // dip to black
    al = smoothstep(0.0, 0.4, uP) * (1.0 - smoothstep(0.55, 1.0, uP)); c = vec3(0.0);
  } else if (uKind == 4) { // glitch: coloured bands + blocks that flicker
    float fr = floor(uT * 24.0);
    float band = step(0.72, h1(vec2(floor(uv.y * 28.0), fr)));
    float blk = step(0.93, h1(floor(vec2(uv.x * 9.0, uv.y * 16.0)) + fr));
    float on = step(0.25, h1(vec2(fr, uSeed))) * env(0.02, 0.6);
    c = vec3(h1(vec2(fr, 1.0)), h1(vec2(fr, 2.0)), 1.0) * 0.6 + 0.4 * vec3(1.0, 0.1, 0.6) * blk;
    al = clamp((band * 0.45 + blk * 0.7) * on, 0.0, 1.0); add = 0.5;
  } else if (uKind == 5) { // VHS: scanlines, noise and a rolling tracking bar
    float e = env(0.05, 0.55);
    float lines = 0.5 + 0.5 * sin(uv.y * 900.0);
    float nz = h1(uv * vec2(420.0, 840.0) + floor(uT * 30.0));
    float bar = smoothstep(0.06, 0.0, abs(uv.y - fract(1.0 - uT * 0.8)));
    c = vec3(nz) * 0.8 + vec3(0.2, 0.25, 0.3);
    al = clamp((0.14 * lines + 0.22 * nz + 0.45 * bar) * e, 0.0, 1.0); add = 0.4;
  } else if (uKind == 6) { // bokeh: soft warm circles drifting up
    float e = env(0.25, 0.5); float s = 0.0;
    for (int i = 0; i < 12; i++) { float fi = float(i);
      vec2 cp = vec2(h1(vec2(fi, uSeed)) * uAsp, fract(h1(vec2(uSeed, fi)) + uP * (0.15 + 0.2 * h1(vec2(fi, 7.0)))));
      float r = 0.05 + 0.09 * h1(vec2(fi, 3.0));
      s += smoothstep(r, r * 0.6, length(q - cp)) * (0.4 + 0.6 * h1(vec2(fi, 5.0))); }
    c = vec3(1.0, 0.82, 0.5); al = clamp(s * e * 0.8, 0.0, 1.0); add = 1.0;
  } else if (uKind == 7) { // strobe
    al = step(0.5, fract(uP * 4.0)) * (1.0 - smoothstep(0.6, 1.0, uP)) * 0.85; c = vec3(1.0);
  } else if (uKind == 8) { // RGB flash: red → green → blue tints
    float ph = floor(uP * 3.0);
    c = ph < 1.0 ? vec3(1.0, 0.1, 0.25) : (ph < 2.0 ? vec3(0.1, 1.0, 0.45) : vec3(0.2, 0.4, 1.0));
    al = 0.55 * (1.0 - smoothstep(0.7, 1.0, uP)); add = 0.6;
  } else { // shine: a bright diagonal sweep
    float d = abs((q.x + (1.0 - q.y) * 0.6) - mix(-0.6, uAsp + 0.6, uP));
    al = (smoothstep(0.22, 0.0, d) * 0.85 + smoothstep(0.6, 0.0, d) * 0.25) * env(0.05, 0.8); c = vec3(1.0, 0.98, 0.92); add = 1.0;
  }
  al *= uAmt * mask();
  o = vec4(c * al, al * (1.0 - add));
}"""
        val IDENT = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        val FLIP_Y = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
    }

    private class Prog(val id: Int) {
        val aPos = GLES30.glGetAttribLocation(id, "aPos")
        val aTc = GLES30.glGetAttribLocation(id, "aTc")
        val uMvp = GLES30.glGetUniformLocation(id, "uMvp")
        val uTex = GLES30.glGetUniformLocation(id, "uTex")
        val uAlpha = GLES30.glGetUniformLocation(id, "uAlpha")
        val uColor = GLES30.glGetUniformLocation(id, "uColor")
        val sTex = GLES30.glGetUniformLocation(id, "sTex")
        val sMask = GLES30.glGetUniformLocation(id, "sMask")
        val uMask = GLES30.glGetUniformLocation(id, "uMask")
    }

    private val oes = Prog(link(VS, FS_OES))
    private val tex2d = Prog(link(VS, FS_2D))
    private val col = Prog(link(VS, FS_COL))
    private val fx = Prog(link(VS, FS_FX))
    private val fxKind = GLES30.glGetUniformLocation(fx.id, "uKind")
    private val fxP = GLES30.glGetUniformLocation(fx.id, "uP")
    private val fxT = GLES30.glGetUniformLocation(fx.id, "uT")
    private val fxAmt = GLES30.glGetUniformLocation(fx.id, "uAmt")
    private val fxAsp = GLES30.glGetUniformLocation(fx.id, "uAsp")
    private val fxSeed = GLES30.glGetUniformLocation(fx.id, "uSeed")
    // unit quad, y up: BL, BR, TL, TR ; tex coords identical
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(0f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f, 1f, 0f, 1f, 1f, 1f, 0f, 1f)); position(0)
    }
    private val mvp = FloatArray(16)
    private var vx = 0; private var vy = 0; private var k = 1f; private var surfH = 0
    // frame shape: when set, video/images are only visible inside the mask's white area
    private var white: Tex? = null
    private var maskTex: Tex? = null
    private val maskRect = FloatArray(4)

    /** draw the next video/image through this mask, stretched over the reel rect x,y,w,h */
    fun setMask(t: Tex?, x: Float, y: Float, w: Float, h: Float) {
        maskTex = t
        maskRect[0] = vx + x * k; maskRect[1] = vy + (H - y - h) * k
        maskRect[2] = 1f / (w * k).coerceAtLeast(1f); maskRect[3] = 1f / (h * k).coerceAtLeast(1f)
    }
    fun clearMask() { maskTex = null }

    private fun bindMask(p: Prog) {
        if (p.sMask < 0) return
        val t = maskTex ?: white ?: Tex.create(Bitmap.createBitmap(intArrayOf(-1), 1, 1, Bitmap.Config.ARGB_8888)).also { white = it }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t.id)
        GLES30.glUniform1i(p.sMask, 1)
        if (maskTex != null) GLES30.glUniform4f(p.uMask, maskRect[0], maskRect[1], maskRect[2], maskRect[3])
        else GLES30.glUniform4f(p.uMask, 0f, 0f, 0f, 0f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        if (p.sTex >= 0) GLES30.glUniform1i(p.sTex, 0)
    }

    /** surface pixels per reel unit */
    val scale get() = k

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
        val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("shader: " + GLES30.glGetShaderInfoLog(s))
        return s
    }
    private fun link(vs: String, fs: String): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs)); GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("link: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    /** Start a frame on a surface of w×h pixels: clear to black and letterbox the 9:16 reel. */
    fun begin(w: Int, h: Int) {
        surfH = h
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClearColor(0f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        k = min(w / W, h / H)
        val vw = (W * k).roundToInt(); val vh = (H * k).roundToInt()
        vx = (w - vw) / 2; vy = (h - vh) / 2
        GLES30.glViewport(vx, vy, vw, vh)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        maskTex = null
    }

    fun clip(x: Float, y: Float, w: Float, h: Float) {
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        val sx = (vx + x * k).roundToInt(); val sy = (vy + (H - y - h) * k).roundToInt()
        GLES30.glScissor(sx, sy, (w * k).roundToInt().coerceAtLeast(0), (h * k).roundToInt().coerceAtLeast(0))
    }
    fun noClip() = GLES30.glDisable(GLES30.GL_SCISSOR_TEST)

    private fun setRect(x: Float, y: Float, w: Float, h: Float) {
        val l = x / W * 2 - 1; val r = (x + w) / W * 2 - 1
        val t = 1 - y / H * 2; val b = 1 - (y + h) / H * 2
        java.util.Arrays.fill(mvp, 0f)
        mvp[0] = r - l; mvp[5] = t - b; mvp[10] = 1f; mvp[12] = l; mvp[13] = b; mvp[15] = 1f
    }

    private fun draw(p: Prog, texMatrix: FloatArray) {
        GLES30.glUniformMatrix4fv(p.uMvp, 1, false, mvp, 0)
        if (p.uTex >= 0) GLES30.glUniformMatrix4fv(p.uTex, 1, false, texMatrix, 0)
        quad.position(0)
        GLES30.glEnableVertexAttribArray(p.aPos)
        GLES30.glVertexAttribPointer(p.aPos, 4, GLES30.GL_FLOAT, false, 16, quad)
        if (p.aTc >= 0) { quad.position(0); GLES30.glEnableVertexAttribArray(p.aTc); GLES30.glVertexAttribPointer(p.aTc, 4, GLES30.GL_FLOAT, false, 16, quad) }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** decoder frame (SurfaceTexture), texMatrix from SurfaceTexture.getTransformMatrix */
    fun drawOes(tex: Int, texMatrix: FloatArray, x: Float, y: Float, w: Float, h: Float, alpha: Float = 1f) {
        GLES30.glUseProgram(oes.id)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES30.glUniform1f(oes.uAlpha, alpha)
        bindMask(oes)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        setRect(x, y, w, h); draw(oes, texMatrix)
    }

    fun drawTex(t: Tex, x: Float, y: Float, w: Float, h: Float, alpha: Float = 1f) {
        GLES30.glUseProgram(tex2d.id)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t.id)
        GLES30.glUniform1f(tex2d.uAlpha, alpha)
        bindMask(tex2d)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t.id)
        setRect(x, y, w, h); draw(tex2d, FLIP_Y)
    }

    fun fill(argb: Int, x: Float, y: Float, w: Float, h: Float) {
        GLES30.glUseProgram(col.id)
        val a = (argb ushr 24) / 255f
        GLES30.glUniform4f(col.uColor, ((argb shr 16) and 0xff) / 255f * a, ((argb shr 8) and 0xff) / 255f * a, (argb and 0xff) / 255f * a, a)
        setRect(x, y, w, h); draw(col, IDENT)
    }

    /** overlay effect over the reel rect; p = 0..1 through the effect, t = seconds (for noise), seed varies each placement */
    fun drawFx(kind: Int, p: Float, t: Float, amt: Float, seed: Float, x: Float, y: Float, w: Float, h: Float) {
        GLES30.glUseProgram(fx.id)
        GLES30.glUniform1i(fxKind, kind); GLES30.glUniform1f(fxP, p); GLES30.glUniform1f(fxT, t)
        GLES30.glUniform1f(fxAmt, amt); GLES30.glUniform1f(fxAsp, w / h.coerceAtLeast(1f)); GLES30.glUniform1f(fxSeed, seed)
        bindMask(fx)
        setRect(x, y, w, h); draw(fx, IDENT)
    }
}
