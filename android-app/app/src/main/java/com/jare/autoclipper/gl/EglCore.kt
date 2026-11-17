package com.jare.autoclipper.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface
import java.io.IOException

/**
 * One OpenGL ES 3 context, used for both the on-screen preview and the encoder's input surface
 * ("recordable" config), so preview and export draw with exactly the same code.
 */
class EglCore {
    val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val config: EGLConfig
    val context: EGLContext
    private val pbuffer: EGLSurface

    init {
        val v = IntArray(2)
        if (!EGL14.eglInitialize(display, v, 0, v, 1)) throw IOException("EGL init failed")
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            0x3142 /* EGL_RECORDABLE_ANDROID */, 1,
            EGL14.EGL_NONE
        )
        val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attrs, 0, cfgs, 0, 1, n, 0) || n[0] == 0) throw IOException("No EGL config")
        config = cfgs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) throw IOException("No GLES3 context")
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        makeCurrent(pbuffer)
    }

    fun windowSurface(s: Surface): EGLSurface {
        val es = EGL14.eglCreateWindowSurface(display, config, s, intArrayOf(EGL14.EGL_NONE), 0)
        if (es == null || es == EGL14.EGL_NO_SURFACE) throw IOException("eglCreateWindowSurface failed: 0x" + Integer.toHexString(EGL14.eglGetError()))
        return es
    }

    fun makeCurrent(s: EGLSurface) { EGL14.eglMakeCurrent(display, s, s, context) }
    fun makeNothingCurrent() { makeCurrent(pbuffer) }
    fun swap(s: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, s)
    fun setPresentationTime(s: EGLSurface, ns: Long) { EGLExt.eglPresentationTimeANDROID(display, s, ns) }

    fun size(s: EGLSurface): IntArray {
        val w = IntArray(1); val h = IntArray(1)
        EGL14.eglQuerySurface(display, s, EGL14.EGL_WIDTH, w, 0); EGL14.eglQuerySurface(display, s, EGL14.EGL_HEIGHT, h, 0)
        return intArrayOf(w[0], h[0])
    }

    fun destroy(s: EGLSurface) { makeNothingCurrent(); EGL14.eglDestroySurface(display, s) }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, pbuffer)
        EGL14.eglDestroyContext(display, context)
    }
}
