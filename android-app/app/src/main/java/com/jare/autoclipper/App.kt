package com.jare.autoclipper

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.jare.autoclipper.editor.BrRule
import com.jare.autoclipper.editor.Core
import com.jare.autoclipper.model.ModelManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(getSharedPreferences("clipper", Context.MODE_PRIVATE))
        // the speech model runs in a second process (":asr"); crash reporting belongs to the editor process only
        if (Application.getProcessName() == packageName) {
            CrashLog.install(this)
            lastExit = CrashLog.pending(this)
            com.jare.autoclipper.ui.UiSound.init(this)
            com.jare.autoclipper.editor.CapFonts.init(this)
        }
    }

    /** Android says memory is short: free the big things we can rebuild (the speech model, decoded music). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) freeMemory("trim $level")
    }
    override fun onLowMemory() { super.onLowMemory(); freeMemory("low memory") }

    companion object {
        lateinit var prefs: Prefs
            private set
        /** why the app closed last time (shown once at launch) */
        @Volatile var lastExit: String? = null

        fun freeMemory(why: String) {
            val a = com.jare.autoclipper.asr.ParakeetEngine.unload()
            com.jare.autoclipper.editor.Library.dropDecoded()
            com.jare.autoclipper.editor.FaceTrack.unload()
            if (a) CrashLog.mark("freed speech model ($why)")
        }
    }
}

class Prefs(private val sp: SharedPreferences) {
    private fun str(k: String, d: String) = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()

    var token: String get() = str("clipper_token", ""); set(v) = put("clipper_token", v)
    /** pre-fills the sign-in form */
    var lastEmail: String get() = str("last_email", ""); set(v) = put("last_email", v)
    /** download address; phones still set to the old r2.dev address are moved to the faster Worker */
    var modelBase: String
        get() = str("model_base", ModelManager.DEFAULT_BASE).let { if (it.trim().trimEnd('/') == ModelManager.FALLBACK_BASE) ModelManager.DEFAULT_BASE else it }
        set(v) = put("model_base", v)
    /** r2 (pack downloaded with the model) | github | web | local */
    var libSource: String get() = str("lib_source2", "r2"); set(v) = put("lib_source2", v)
    var localTree: String get() = str("local_tree", ""); set(v) = put("local_tree", v)
    var blurWords: String get() = str("blurWords", "").ifBlank { Core.BL_DEFAULT.joinToString("\n") }; set(v) = put("blurWords", v)
    var packPrompted: Boolean get() = sp.getBoolean("pack_prompted", false); set(v) = sp.edit().putBoolean("pack_prompted", v).apply()
    var lastExitSeen: Long get() = sp.getLong("last_exit_seen", 0L); set(v) = sp.edit().putLong("last_exit_seen", v).apply()
    var uiSounds: Boolean get() = sp.getBoolean("ui_sounds", true); set(v) = sp.edit().putBoolean("ui_sounds", v).apply()
    var modelPrompted: Boolean get() = sp.getBoolean("model_prompted", false); set(v) = sp.edit().putBoolean("model_prompted", v).apply()

    var brRules: List<BrRule>
        get() = try {
            val a = JSONArray(str("brRules", "[]"))
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i); val w = o.optJSONArray("w") ?: return@mapNotNull null
                val t = o.optString("t"); if (t.isBlank()) null else BrRule((0 until w.length()).map { w.getString(it) }, t)
            }
        } catch (e: Exception) { emptyList() }
        set(v) = put("brRules", JSONArray(v.map { JSONObject().put("w", JSONArray(it.w)).put("t", it.t) }).toString())
}

/** Same login endpoint as the website. */
object AuthApi {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private const val URL = "https://app-backend-delta-livid.vercel.app/v1/auth/login"

    fun login(email: String, password: String): String {
        val body = JSONObject().put("email", email).put("password", password).toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(URL).post(body).header("x-app-version", "2").build()
        http.newCall(req).execute().use { r ->
            val txt = r.body?.string() ?: ""
            val j = try { JSONObject(txt) } catch (e: Exception) { JSONObject() }
            if (!r.isSuccessful) throw IOException(j.optString("error").ifBlank { "Invalid credentials" })
            return j.optString("token").ifBlank { throw IOException("No token in reply") }
        }
    }
}
