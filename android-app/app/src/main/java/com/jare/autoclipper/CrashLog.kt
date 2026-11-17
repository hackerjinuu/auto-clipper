package com.jare.autoclipper

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Remembers why the app closed, so the next launch can show it (and you can copy it).
 *  - Kotlin/Java crashes: full stack trace.
 *  - Native crashes, "not responding" and low-memory kills: the reason Android recorded.
 *  - The last few actions before the app closed.
 */
object CrashLog {
    private var dir: File? = null
    private val marks = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun install(app: Application) {
        dir = File(app.filesDir, "crash").apply { mkdirs() }
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                File(dir, "crash.txt").writeText("Thread: ${t.name}\n$sw")
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** breadcrumb: call for user actions and engine steps (not per frame) */
    @Synchronized fun mark(msg: String) {
        Log.i("AutoClipper", msg)
        marks.addLast(fmt.format(Date()) + "  " + msg)
        while (marks.size > 40) marks.removeFirst()
        runCatching { File(dir ?: return, "marks.txt").writeText(marks.joinToString("\n")) }
    }

    /** a handled error worth knowing about */
    fun warn(where: String, e: Throwable) {
        Log.w("AutoClipper", where, e)
        mark("ERROR in $where: ${e.javaClass.simpleName}: ${e.message}")
    }

    /** Text describing the last abnormal exit, or null. Call once at launch, before new marks. */
    fun pending(ctx: Context): String? {
        val d = dir ?: return null
        val sb = StringBuilder()
        val crash = File(d, "crash.txt")
        if (crash.exists()) { sb.append("APP CRASH\n").append(crash.readText().take(6000)).append('\n') }
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java)
            // only the editor process counts (the speech-model process may be stopped on purpose by Android)
            val info = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 8).firstOrNull { it.processName == ctx.packageName }
            val seen = App.prefs.lastExitSeen
            if (info != null && info.timestamp > seen) {
                App.prefs.lastExitSeen = info.timestamp
                val name = when (info.reason) {
                    ApplicationExitInfo.REASON_CRASH -> if (crash.exists()) null else "APP CRASH"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE CRASH (GPU / video decoder / ONNX)"
                    ApplicationExitInfo.REASON_ANR -> "APP NOT RESPONDING (screen thread blocked)"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "KILLED BY ANDROID: PHONE OUT OF MEMORY"
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "KILLED BY ANDROID: TOO MUCH RESOURCE USE"
                    ApplicationExitInfo.REASON_SIGNALED -> if (info.status == 9) "KILLED BY ANDROID (signal 9, usually memory)" else null
                    else -> null
                }
                if (name != null) {
                    sb.append(name).append('\n')
                    info.description?.let { sb.append(it).append('\n') }
                    sb.append("memory used: ${info.pss / 1024} MB (pss), ${info.rss / 1024} MB (rss)\n")
                    if (info.reason == ApplicationExitInfo.REASON_ANR)
                        runCatching { info.traceInputStream?.use { s -> sb.append(String(s.readBytes()).take(5000)).append('\n') } }
                }
            }
        }
        if (sb.isEmpty()) { File(d, "marks.txt").delete(); return null }
        val m = File(d, "marks.txt")
        if (m.exists()) sb.append("\nLAST ACTIONS\n").append(m.readText())
        sb.append("\n\n${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        crash.delete(); m.delete()
        return sb.toString()
    }
}
