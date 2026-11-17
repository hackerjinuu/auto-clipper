package com.jare.autoclipper.editor

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jare.autoclipper.App
import com.jare.autoclipper.asr.ParakeetEngine
import com.jare.autoclipper.media.AudioDecoder
import com.jare.autoclipper.media.Exporter
import com.jare.autoclipper.media.FloatList
import com.jare.autoclipper.media.FrameSource
import com.jare.autoclipper.media.MediaUtil
import com.jare.autoclipper.model.ModelManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import com.jare.autoclipper.CrashLog
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.IOException
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

data class TaskUi(val text: String, val state: Int /*0 running, 1 ok, 2 error*/, val pct: Float?)
data class HookItem(val seg: Seg, val badge: String?)
data class ExportUi(val stage: Int /*0 options, 1 running, 2 done, 3 stopped*/, val res: Int = 1080, val fps: Int = 30, val pct: Float = 0f,
                    val title: String = "", val note: String = "", val outUri: Uri? = null, val outName: String = "")

/** The whole editor (web: the big <script> of index.html). */
class EditorViewModel(app: Application) : AndroidViewModel(app), Project {
    val ctx: Context get() = getApplication()
    override val s = Settings()
    val preview = PreviewEngine(this)
    private val main = Handler(Looper.getMainLooper())
    fun post(r: () -> Unit) { main.post { r() } }
    /** nothing started from the editor may take the app down: report it instead */
    private val safe = CoroutineExceptionHandler { _, e ->
        CrashLog.warn("editor task", e)
        post { busy.clear(); if (task?.state == 0) task = null; stat = "Something went wrong: " + (e.message ?: e.javaClass.simpleName) }
    }

    companion object {
        const val SN_DEFAULT = "Removes pauses so the clip jumps from speech to speech. Max length = final length after cuts."
        const val HKN_DEFAULT = "Listens to the first 3 minutes and lists every spoken line. The top 3 are suggested, and the best is applied first. Tap any line to start the clip there instead."
        const val ASRN_DEFAULT = "Uses NVIDIA Parakeet TDT 0.6B v2 (INT8) stored on this phone, running natively with ONNX Runtime."
        const val BRN_DEFAULT = "Reads the \"broll\" folder of your library. A clip named blood_01.mp4 / blood1.mp4 is shown when a caption says \"blood\". Several numbered files for one keyword are used in turn."
        private const val PCM_CAP = 30 * 60 * 16000 // keep at most 30 min of 16 kHz audio in memory
    }

    /* ---------------- source ---------------- */
    var srcUri: Uri? = null; private set
    var srcName by mutableStateOf("")
    var dur = 0.0; private set
    override val hasVideo get() = srcUri != null && dur > 0
    @Volatile var cutK: List<Range>? = null
    @Volatile var env: Env? = null
    private var pcm16: ShortArray? = null
    private var pcm16Len = 0
    private var pcmFull = false
    private var pcmJob: Deferred<Unit>? = null
    private var wantLen = 0.0

    /* ---------------- transcript / captions ---------------- */
    @Volatile final override var cues: List<Cue> = emptyList(); private set
    @Volatile var segs: List<Seg> = emptyList(); private set
    @Volatile var blIv: List<Iv> = emptyList(); private set
    private var approx = false
    private var asrW: List<Iv> = emptyList()
    private var blT: List<List<Regex>> = emptyList()

    /* ---------------- B-roll ---------------- */
    @Volatile final override var broll: List<BrollItem> = emptyList(); private set
    /** overlay effects on the timeline (output time) */
    @Volatile final override var overlays: List<OverlayItem> = emptyList(); private set
    /** face tracking result for the current video (null = not scanned) */
    @Volatile final override var faces: FaceScan? = null; private set
    var srcW = 0; private set
    var srcH = 0; private set
    var faceNote by mutableStateOf("")
    private var fxSeq = 1
    /** the style "Auto overlay" used last; while set, overlays follow the clips when the edit changes */
    private var fxAuto: String? = null
    /** clip starts where the user moved or deleted an automatic overlay: auto runs leave them empty */
    private val fxSkip = HashSet<Double>()
    var brPool: List<BrAsset> = emptyList(); private set
    private val brAssets = HashMap<String, BrAsset>()
    private var brSeq = 1000
    private var assetSeq = 1
    var brRules by mutableStateOf(App.prefs.brRules)
    var brTargets by mutableStateOf(listOf<String>())
    private var brBusy = false; private var brAgain = false

    /* ---------------- music / SFX ---------------- */
    private var bgmList: List<Track> = emptyList()
    private var sfxList: List<Track> = emptyList()
    private var transList: List<Track> = emptyList()
    /** for the Sounds panel */
    var sfxTracks by mutableStateOf(listOf<Track>()); private set
    var transTracks by mutableStateOf(listOf<Track>()); private set
    /** B-roll clips whose automatic transition sound the user removed or edited */
    private val transSkip = HashSet<Int>()
    private var player: android.media.MediaPlayer? = null
    @Volatile var sfxT: List<SfxPlacement> = emptyList(); private set
    private var bgmChoice: String? = null
    private var hookChoice: String? = null
    private var keepPick = false
    private var trSeed = Random.nextInt(1_000_000_000)
    private val anCache = HashMap<String, Track>()
    private var libJob: Deferred<LibListing>? = null
    private class AutoOn { var bgm = false; var sfx = false; var br = false }
    private val autoOn = AutoOn()
    var bselOptions by mutableStateOf(listOf<String>())

    /* ---------------- UI state ---------------- */
    var sn by mutableStateOf(SN_DEFAULT)
    var hkn by mutableStateOf(HKN_DEFAULT)
    var asrn by mutableStateOf(ASRN_DEFAULT)
    var trn by mutableStateOf("")
    var bwn by mutableStateOf("")
    var brn by mutableStateOf(BRN_DEFAULT)
    var libn by mutableStateOf("Looking for your library…")
    var stat by mutableStateOf("Load a video to begin.")
    var rep by mutableStateOf("No edit yet.")
    var hooks by mutableStateOf(listOf<HookItem>())
    var hookSel by mutableIntStateOf(-1)
    var rev by mutableIntStateOf(0)
    var phRel by mutableDoubleStateOf(0.0)
    var playing by mutableStateOf(false); private set
    var task by mutableStateOf<TaskUi?>(null); private set
    val busy = mutableStateMapOf<String, Boolean>()
    var export by mutableStateOf<ExportUi?>(null)
    var needModel by mutableStateOf(false)
    @Volatile private var exportCancel = false
    private var lastRes = 1080; private var lastFps = 30

    init {
        s.bw = App.prefs.blurWords
        blCompile()
        reloadLibrary()
    }

    /** (re)read the library; with the R2 pack everything is prepared up front */
    fun reloadLibrary() {
        Library.invalidate(); libJob = null
        viewModelScope.launch(safe) {
            try {
                val L = withContext(Dispatchers.IO) { Library.list(ctx) }
                fillBsel(L.bgm.map { it.name })
                brTargets = L.broll.map { BrMatch.key(it.name).joinToString(" ") }.filter { it.isNotEmpty() }.distinct()
                libn = "Library ready: ${L.bgm.size} music track(s), ${L.sfx.size} hook SFX, ${L.trans.size} transition SFX and ${L.broll.size} B-roll clip(s)."
                if (App.prefs.libSource == "r2") { loadLibrary(); preloadBroll(L) } // everything is on the phone: prepare it now so Auto buttons are instant
            } catch (e: Exception) { srvDown(e) }
        }
    }

    /* ================= timeline maths ================= */
    fun t0() = s.st
    override fun clipLen(): Double {
        cutK?.let { val l = it.last(); return l.o + l.b - l.a }
        return max(1.0, min(if (s.ln > 0) s.ln else 30.0, dur - t0()))
    }
    fun ranges(): List<Range> = cutK ?: listOf(Range(t0(), t0() + clipLen(), 0.0))
    fun srcLen(): Double = cutK?.let { it.last().b - t0() } ?: clipLen()
    override fun otime(ts: Double) = Core.otime(ranges(), ts)
    fun stime(o: Double) = Core.stime(ranges(), o)

    /* ================= task / status toast ================= */
    private var hideTask: Runnable? = null
    private fun showTask(t: TaskUi, hideAfter: Long) {
        hideTask?.let { main.removeCallbacks(it) }
        task = t
        if (hideAfter > 0) { val r = Runnable { task = null }; hideTask = r; main.postDelayed(r, hideAfter) }
    }
    fun taskBegin(t: String, btn: String? = null) { btn?.let { busy[it] = true }; if (task?.state != 0) com.jare.autoclipper.ui.UiSound.play(com.jare.autoclipper.ui.Snd.START); showTask(TaskUi("$t…", 0, null), 0) }
    fun taskMsg(t: String, p: Float? = null) {
        post {
            val cur = task ?: return@post
            if (cur.state != 0) return@post
            val m = Regex("(\\d+)%").find(t)
            showTask(TaskUi(t, 0, p ?: m?.groupValues?.get(1)?.toFloatOrNull()?.div(100f)), 0)
        }
    }
    fun taskEnd(t: String, btn: String? = null) { btn?.let { busy.remove(it) }; com.jare.autoclipper.ui.UiSound.play(com.jare.autoclipper.ui.Snd.SUCCESS); showTask(TaskUi("✓ $t", 1, 1f), 2400) }
    fun taskFail(t: String, btn: String? = null) { btn?.let { busy.remove(it) }; com.jare.autoclipper.ui.UiSound.play(com.jare.autoclipper.ui.Snd.ERROR); showTask(TaskUi("⚠ $t", 2, null), 4500) }
    fun taskClose(btn: String? = null) { btn?.let { busy.remove(it) }; if (task?.state == 0) task = null }

    /** web: wrapTask() */
    /** why the last Auto step could not run, shown in the toast instead of a vague "failed" */
    private var why: String? = null

    private fun launchTask(title: String, btn: String, done: String, block: suspend () -> Boolean) = viewModelScope.launch(safe) {
        taskBegin(title, btn)
        why = null
        try {
            val ok = block()
            if (!ok) taskFail(why ?: "$title: nothing to do", btn) else taskEnd("Done · $done", btn)
            why = null
        } catch (e: CancellationException) { taskClose(btn); throw e }
        catch (e: Throwable) { CrashLog.warn(title, e); taskFail("$title failed", btn); stat = e.message ?: e.toString() }
    }

    private var warmJob: Job? = null
    fun bump() {
        refreshAutoFx()
        if (s.faceMode == "speaker") faces?.setSpeech(speechTimes())
        rev++; report(); preview.requestStill()
        // pre-mix the audio in the background so pressing Play starts instantly
        warmJob?.cancel()
        if (hasVideo) warmJob = viewModelScope.launch(safe + Dispatchers.Default) {
            delay(700)
            val job = coroutineContext[Job]
            runCatching { mixSuspend { job?.isActive == false } }
        }
    }
    fun onPlayState(p: Boolean) { playing = p }

    /* ================= 1 · source video ================= */
    fun pickSource(uri: Uri) = viewModelScope.launch(safe) {
        CrashLog.mark("import video")
        preview.stop()
        taskBegin("Loading video")
        try {
            val info = withContext(Dispatchers.IO) {
                val r = MediaMetadataRetriever()
                try {
                    MediaUtil.setSource(r, ctx, uri)
                    val d = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
                    var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rot % 180 != 0) { val t = w; w = h; h = t }
                    Triple(d, w, h)
                } finally { r.release() }
            }
            if (info.first <= 0 || info.second <= 0) throw IOException("not a video")
            pcmJob?.cancel(); pcmJob = null; pcm16 = null; pcm16Len = 0; env = null
            srcUri = uri; dur = info.first; srcName = displayName(uri)
            overlays = emptyList(); selOv = null; fxSkip.clear()
            srcW = info.second; srcH = info.third; faces = null; s.faceMode = "off"; faceNote = ""
            cutK = null; s.cut = false
            s.ln = min(30.0, floor(dur)); wantLen = s.ln
            phRel = 0.0
            preview.resetSource()
            stat = "$srcName · ${"%.1f".format(dur)}s · ${info.second}×${info.third}"
            taskEnd("Done · video loaded")
            if (s.tr.isNotBlank()) build() else bump()
            queueAuto()
            refreshThumbs()
            startPcm() // analyse the audio in the background (silence cut, captions snap, ASR)
        } catch (e: CancellationException) { throw e } catch (e: Throwable) { CrashLog.warn("load video", e); taskFail("Could not load this video") }
    }

    private fun displayName(uri: Uri): String = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) { null } ?: (uri.lastPathSegment ?: "video")

    /** One pass over the source audio: 16 kHz mono copy (for ASR) + 20 ms loudness envelope. */
    private fun startPcm(): Deferred<Unit> {
        pcmJob?.let { return it }
        val uri = srcUri ?: throw IOException("Load a source video first.")
        val job = viewModelScope.async(Dispatchers.Default) {
            var buf = ShortArray(16000 * 120); var n = 0
            val db = FloatList(); var acc = 0.0; var cnt = 0
            var total = 0L
            AudioDecoder.decodeStream(ctx, uri, 0.0, 1e9, 16000, 1, { !isActive }, {}) { v ->
                if (n < PCM_CAP) {
                    if (n == buf.size) buf = buf.copyOf(min(PCM_CAP, buf.size * 2))
                    buf[n++] = (v * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
                }
                total++
                acc += v * v; cnt++
                if (cnt == 320) { db.add(Env.rmsDb(acc, 320)); acc = 0.0; cnt = 0 }
            }
            if (srcUri == uri) {
                pcm16 = buf; pcm16Len = n; pcmFull = total <= PCM_CAP
                env = Env.fromDb(db.toArray(), 0.02).also { it.thr = it.sorted90 - s.sth.toFloat() }
                post { rev++ }
            }
        }
        pcmJob = job
        return job
    }

    suspend fun getEnv(): Env? {
        if (srcUri == null) return null
        return try { startPcm().await(); env?.also { it.thr = it.sorted90 - s.sth.toFloat() } }
        catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    }

    /** 16 kHz mono PCM of source [a, b). */
    private suspend fun pcmRange(a: Double, b: Double): FloatArray {
        val uri = srcUri ?: return FloatArray(0)
        try { startPcm().await() } catch (e: CancellationException) { throw e } catch (e: Exception) {}
        val p = pcm16
        val from = max(0, (a * 16000).toInt())
        var to = (b * 16000).toInt()
        if (p != null && (to <= pcm16Len || pcmFull)) {
            to = min(to, pcm16Len)
            if (to <= from) return FloatArray(0)
            return FloatArray(to - from) { p[from + it] / 32768f }
        }
        return withContext(Dispatchers.Default) { AudioDecoder.decode(ctx, uri, a, b, 16000, 1).data }
    }

    /* ---------- silence cut ---------- */
    private var rangeJob: Job? = null
    /** start / length / cut settings changed (debounced) */
    fun rangeChanged(lengthEdited: Boolean = false) {
        if (lengthEdited) wantLen = if (s.ln > 0) s.ln else 30.0
        rangeJob?.cancel()
        rangeJob = viewModelScope.launch(safe) { delay(300); recut(); build(); refreshThumbs() }
    }

    suspend fun recut() {
        if (!s.cut) { cutK = null; sn = SN_DEFAULT; return }
        taskBegin("Analyzing audio for silence")
        sn = "Analyzing audio..."
        if (srcUri == null) { cutK = null; sn = "Load a source video first."; s.cut = false; taskClose(); return }
        val E = getEnv()
        if (E == null) { cutK = null; sn = "Silence analysis failed: could not read the audio of this video."; taskFail("Silence cut failed"); return }
        val want = if (wantLen > 0) wantLen else s.ln
        val st = t0()
        val R = withContext(Dispatchers.Default) { Core.makeRanges(E.db, E.step, st, want, s.sth, s.sgp, min(if (dur > 0) dur else 1e9, E.lengthSec)) }
        if (R == null) { cutK = null; sn = "No speech found after the start time."; taskClose(); return }
        cutK = R
        val last = R.last(); val out = last.o + last.b - last.a; val removed = (last.b - st) - out
        sn = "Cut ${max(0, R.size - 1)} pauses" + (if (R[0].a > st + 0.05) " + leading silence" else "") + " · removed ${"%.1f".format(removed)}s · final length ${"%.1f".format(out)}s" +
            (if (out < want - 0.3) " (video ends before ${want.toInt()}s)" else "")
        taskEnd("Done · silence removed")
    }

    /* ================= 2 · transcript ================= */
    private var buildJob: Job? = null
    fun transcriptEdited() { buildJob?.cancel(); buildJob = viewModelScope.launch(safe) { delay(250); build() } }

    fun blCompile() {
        blT = Core.blCompile(s.bw)
        bwn = "${blT.size} words/phrases in the blur list."
        App.prefs.blurWords = s.bw
    }
    fun blurListEdited() { buildJob?.cancel(); buildJob = viewModelScope.launch(safe) { delay(300); blCompile(); build() } }
    fun resetBlurList() { s.bw = Core.BL_DEFAULT.joinToString("\n"); blCompile(); build() }

    fun build() {
        val parsed = Core.parseTr(s.tr, t0(), srcLen())
        segs = parsed.segs; approx = parsed.approx
        val n = s.wn.coerceAtLeast(1)
        var nBl = 0
        val bl = ArrayList<Iv>()
        val pad = if (approx || asrW.isEmpty()) 0.12 else 0.05
        val cs = ArrayList<Cue>()
        val E = env
        for (sg in segs) {
            val wt = Core.wordTimes(sg, asrW, E)
            nBl += Core.markBlur(wt, s.bon, blT)
            if (s.bbp) wt.filter { it.bl }.forEach { bl.add(Iv(max(0.0, it.s - pad), it.e + pad)) }
            var i = 0
            while (i < wt.size) { val g = wt.subList(i, min(i + n, wt.size)).toList(); cs.add(Cue(g.first().s, g.last().e, g.map { it.t }, g)); i += n }
        }
        bl.sortBy { it.s }
        val merged = ArrayList<Iv>()
        for (x in bl) { val l = merged.lastOrNull(); if (l != null && x.s <= l.e + 0.02) l.e = max(l.e, x.e) else merged.add(Iv(x.s, x.e)) }
        Core.fixCues(cs)
        cues = cs; blIv = merged
        trn = if (segs.isEmpty()) "" else "${segs.size} segments, ${cs.size} caption cues" +
            (if (approx) " · timings approximate (estimated)" else if (asrW.isNotEmpty()) " · word-level timing" else "") +
            (if (nBl > 0) " · $nBl word${if (nBl > 1) "s" else ""} blurred" else "")
        bump(); queueAuto()
    }

    fun loadTranscriptFile(uri: Uri) = viewModelScope.launch(safe) {
        val t = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() }
        if (t != null) { s.tr = t; build() }
    }

    /** before transcribing: model downloaded? then give the speech-model process as much RAM as possible */
    private suspend fun engine(note: (String) -> Unit) {
        if (!ModelManager(ctx).isReady()) { needModel = true; throw IOException("Download the Parakeet model first (one time, about 670 MB).") }
        preview.stop()
        preview.resetSource()          // closes the video decoders (they hold a lot of memory)
        Library.dropDecoded()          // decoded music
        mixData = null; mixKey = null  // pre-mixed preview audio
        System.gc()
        note("Loading the speech model…")
    }

    fun transcribe() = viewModelScope.launch(safe) {
        if (srcUri == null) { asrn = "Load a source video first."; return@launch }
        CrashLog.mark("transcribe"); taskBegin("Transcribing", "asr")
        val note = { t: String -> asrn = t; taskMsg(t) }
        try {
            note("Reading audio from video...")
            val a = t0(); val L = srcLen()
            val pcm = pcmRange(a, a + L)
            if (pcm.isEmpty()) { note("Start time is past the end of the video."); taskClose("asr"); return@launch }
            engine(note)
            note("Transcribing ${L.roundToInt()}s of audio with Parakeet...")
            val words = com.jare.autoclipper.asr.AsrClient.transcribe(ctx, pcm) { p -> note("Transcribing with Parakeet… ${(p * 100).roundToInt()}%") }
            val w = Core.wseg(words.map { Triple(it.text, it.s, it.e) }, a)
            asrW = w.asrW
            if (w.segs.isEmpty()) { note("No speech found in that range."); taskClose("asr"); return@launch }
            s.tr = Core.toSrt(w.segs)
            getEnv()
            build()
            asrn = "Done: ${w.segs.size} segments. Edit the text box to fix any wrong words."
            taskEnd("Done · transcript ready", "asr")
        } catch (e: CancellationException) { taskClose("asr"); throw e }
        catch (e: Throwable) { CrashLog.warn("transcribe", e); asrn = "Failed: " + (e.message ?: e.toString()); taskFail(asrn, "asr") }
    }

    /* ---------- smart start: hook finder ---------- */
    fun findHooks() = viewModelScope.launch(safe) {
        if (srcUri == null) { hkn = "Load a source video first."; return@launch }
        taskBegin("Finding hook lines", "hook")
        val note = { t: String -> hkn = t; taskMsg(t) }
        try {
            note("Reading audio from video...")
            val pcm = pcmRange(0.0, 180.0)
            if (pcm.isEmpty()) { note("Could not read audio."); taskClose("hook"); return@launch }
            engine(note)
            note("Listening for the engaging part with Parakeet... this can take a minute.")
            val words = com.jare.autoclipper.asr.AsrClient.transcribe(ctx, pcm) { p -> note("Listening with Parakeet… ${(p * 100).roundToInt()}%") }
            val w = Core.wseg(words.map { Triple(it.text, it.s, it.e) }, 0.0)
            asrW = w.asrW
            val sg = w.segs
            if (sg.isEmpty()) { note("No speech found in the first 3 minutes."); taskClose("hook"); return@launch }
            s.tr = Core.toSrt(sg)
            val want = if (wantLen > 0) wantLen else s.ln
            val d = if (dur > 0) dur else pcm.size / 16000.0
            val fit = sg.filter { it.s + want <= d + 0.5 }
            val pool = fit.ifEmpty { sg }
            val ranked = pool.map { it to Core.hookScore(it.text, sg.indexOf(it)) }
                .sortedWith(compareByDescending<Pair<Seg, Double>> { it.second }.thenBy { it.first.s }).take(3).map { it.first }
            val top = ranked.toSet()
            hooks = (ranked.mapIndexed { i, x -> HookItem(x, if (i == 0) "Best" else "#${i + 1}") } + sg.filter { it !in top }.map { HookItem(it, null) }).take(60)
            hookSel = 0
            applyStartNow(ranked[0])
            taskEnd("Done · hook line found", "hook")
        } catch (e: CancellationException) { taskClose("hook"); throw e }
        catch (e: Throwable) { CrashLog.warn("find hooks", e); hkn = "Failed: " + (e.message ?: e.toString()); taskFail(hkn, "hook") }
    }

    fun applyStart(i: Int) { hookSel = i; hooks.getOrNull(i)?.let { h -> viewModelScope.launch(safe) { applyStartNow(h.seg) } } }

    private suspend fun applyStartNow(c: Seg) {
        val want = if (wantLen > 0) wantLen else s.ln
        var st = max(0.0, c.s - 0.2); var shifted = false
        if (dur > 0 && st + want > dur) { st = max(0.0, dur - want); shifted = true }
        st = (st * 10).roundToInt() / 10.0
        s.st = st
        val len = if (dur > 0) max(1.0, min(want, floor((dur - st) * 10) / 10)) else want
        s.ln = len
        phRel = 0.0
        hkn = "Clip: ${"%.1f".format(st)}s → ${"%.1f".format(st + len)}s (${"%.1f".format(len)}s)" +
            (if (shifted) " · not enough video after that line, so the start was moved earlier to keep your length" else "") + ". \"${c.text.take(60)}\""
        recut(); build(); refreshThumbs()
    }

    /* ================= 4 · B-roll ================= */
    private fun brMax() = if (s.brmax > 0) s.brmax else 2.0

    fun addManualBroll(uris: List<Uri>) = viewModelScope.launch(safe) {
        val got = withContext(Dispatchers.IO) {
            uris.mapNotNull { u ->
                runCatching { ctx.contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                BrAsset.load(ctx, assetSeq++, displayName(u).take(40), u, ctx.contentResolver.getType(u))
            }
        }
        brPool = brPool + got
        stat = "${brPool.size} B-roll asset(s) loaded."
    }

    /** web: autoPlace() — deterministic round-robin over manually added files */
    fun autoPlace() {
        if (segs.isEmpty() || brPool.isEmpty()) { stat = "Need a transcript and B-roll first."; return }
        val L = clipLen(); val pool = brPool; var k = 0; var last = -1
        val out = ArrayList<BrollItem>()
        val inClip = segs.filter { otime(it.e) > otime(it.s) && otime(it.s) < L }
        inClip.forEachIndexed { i, sg ->
            if (i % 2 == 0) return@forEachIndexed
            val st = otime(sg.s); val d = min(brMax(), max(1.5, sg.e - sg.s))
            if (st + d > L) return@forEachIndexed
            var b = pool[k % pool.size]
            if (pool.size > 1 && b.id == last) { k++; b = pool[k % pool.size] }
            k++; last = b.id
            out.add(BrollItem(brSeq++, b, st, d, false))
        }
        broll = out
        bump()
        if (sfxList.isNotEmpty() && s.sfxcut) viewModelScope.launch(safe) { placeSfx(false) }
    }

    fun clearBroll() { selBroll = null; broll = emptyList(); brPool = emptyList(); autoOn.br = false; bump() }
    fun removeBroll(id: Int) { selBroll = null; broll = broll.filter { it.id != id }; if (sfxT.any { it.tr }) placeTrans(); bump() }
    fun removeSfx(i: Int) {
        val x = sfxT.getOrNull(i) ?: return
        forgetAuto(x)
        selSfx = null; sfxT = sfxT.filterIndexed { k, _ -> k != i }; bump()
    }

    /** the user took over this automatic sound: auto runs must not put it back */
    private fun forgetAuto(x: SfxPlacement) {
        if (x.manual) return
        if (x.tr) { if (x.brId >= 0) transSkip.add(x.brId) } else autoOn.sfx = false
    }

    /** move (dt seconds) or change the volume (dv) of a sound on the timeline */
    fun nudgeSfx(i: Int, dt: Double, dv: Double) {
        val x = sfxT.getOrNull(i) ?: return
        forgetAuto(x)
        val nt = (x.t + dt).coerceAtLeast(0.0)
        val y = x.copy(t = nt, hit = x.hit + (nt - x.t), v = (x.v + dv).coerceIn(0.05, 2.0), manual = true)
        sfxT = sfxT.mapIndexed { k, p -> if (k == i) y else p }
        bump()
    }

    /** Sounds panel: make sure the hook / transition lists are loaded */
    fun ensureSounds() {
        if (sfxList.isNotEmpty() || transList.isNotEmpty()) { sfxTracks = sfxList; transTracks = transList; return }
        viewModelScope.launch(safe) { try { loadLibrary() } catch (e: CancellationException) { throw e } catch (e: Exception) { srvDown(e) } }
    }

    /** listen to a sound (tap again on another one to switch) */
    fun audition(t: Track) {
        runCatching { player?.release() }; player = null
        try {
            preview.stop()
            val mp = android.media.MediaPlayer()
            mp.setDataSource(ctx, t.uri)
            mp.setOnCompletionListener { runCatching { it.release() }; if (player === it) player = null }
            mp.prepare(); mp.start(); player = mp
        } catch (e: Exception) { CrashLog.warn("audition ${t.name}", e); stat = "Could not play ${t.name}" }
    }

    /** put a sound on the timeline at the playhead; its loudest moment lands exactly there */
    fun addSoundAt(t: Track, transition: Boolean) {
        if (!hasVideo) { taskFail("Import a video first"); return }
        val at = phRel.coerceIn(0.0, clipLen())
        val p = if (transition) addSfx(t, at, 0.7, 2.5, true).copy(manual = true) else addSfx(t, at, 1.0).copy(manual = true)
        sfxT = sfxT + p
        selSfx = sfxT.size - 1; selBroll = null
        stat = "Added \"${t.name}\" at ${"%.2f".format(at)}s"
        bump()
    }

    /** Sounds panel button: random transition sounds on the B-roll clips (new pick each press) */
    fun autoTransitions() = launchTask("Transition sounds", "transbtn", "transitions added") {
        try { loadLibrary() } catch (e: CancellationException) { throw e } catch (e: Exception) { if (transList.isEmpty()) { srvDown(e); return@launchTask false } }
        if (transList.isEmpty()) { why = "No sounds in the \"transitionsfx\" folder of your R2 pack."; libNote(why!!); return@launchTask false }
        if (broll.none { it.dur > 0 }) { why = "No B-roll yet. Transition sounds play at the start of each B-roll clip: add B-roll first (B-roll → Auto B-roll)."; stat = why!!; return@launchTask false }
        s.sfxcut = true; transSkip.clear(); trSeed = Random.nextInt(1_000_000_000)
        val n = placeTrans(); bump()
        stat = "$n transition sound(s) on ${broll.count { it.dur > 0 }} B-roll clip(s). Press again for a different mix."
        true
    }

    /** remove every sound (hook + transitions + your own) */
    fun clearSounds() { sfxT = emptyList(); selSfx = null; autoOn.sfx = false; transSkip.clear(); s.sfxcut = false; bump() }

    /* ---------- frames (video shape masks) ---------- */
    class FrameUi(val id: String, val name: String, val custom: Boolean, val thumb: ImageBitmap?, val aspect: Double)
    var frames by mutableStateOf(listOf<FrameUi>()); private set
    fun loadFrames() = viewModelScope.launch(safe + Dispatchers.IO) {
        val l = Frames.list(ctx).map { i ->
            val m = runCatching { Frames.mask(ctx, i.id) }.getOrNull()
            FrameUi(i.id, i.name, i.custom, m?.asImageBitmap(), if (m != null) m.width.toDouble() / m.height else 0.0)
        }.filter { it.thumb != null }
        withContext(Dispatchers.Main) { frames = l }
    }
    fun applyFrame(f: FrameUi?) {
        CrashLog.mark("frame ${f?.name ?: "none"}")
        s.frameId = f?.id ?: "none"; s.frameAspect = f?.aspect ?: 0.0
        stat = if (f == null) "Frame removed" else "Frame: ${f.name}"
        bump()
    }
    fun importFrame(uri: Uri) = viewModelScope.launch(safe) {
        val item = withContext(Dispatchers.IO) { runCatching { Frames.import(ctx, uri) }.getOrNull() }
        if (item == null) { taskFail("That picture has no white shape (use white on black)"); return@launch }
        loadFrames().join()
        frames.firstOrNull { f -> f.id == item.id }?.let { f -> applyFrame(f) }
    }
    fun deleteFrame(f: FrameUi) {
        if (s.frameId == f.id) applyFrame(null)
        viewModelScope.launch(safe + Dispatchers.IO) { Frames.delete(f.id); loadFrames() }
    }

    fun brMaxChanged() {
        val m = brMax()
        broll = broll.map { if (it.dur > m) it.copy(dur = m) else it }
        bump()
        if (autoOn.br) viewModelScope.launch(safe) { autoBrollS(true) }
    }

    fun addRule(words: String, target: String): Boolean {
        val w = words.split(Regex("[,;\\n]+")).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val t = target.trim()
        if (w.isEmpty() || t.isEmpty()) { brn = "Type the caption word(s) and the B-roll name, then press Add word rule."; return false }
        brRules = brRules + BrRule(w, t); App.prefs.brRules = brRules
        if (autoOn.br) viewModelScope.launch(safe) { autoBrollS(true) } else brn = "Rule added. Press \"Auto B-roll from captions\" to apply it."
        return true
    }
    fun removeRule(i: Int) {
        brRules = brRules.filterIndexed { k, _ -> k != i }; App.prefs.brRules = brRules
        if (autoOn.br) viewModelScope.launch(safe) { autoBrollS(true) }
    }

    fun autoBroll() = viewModelScope.launch(safe) {
        if (App.prefs.libSource != "r2") Library.invalidate()
        CrashLog.mark("auto b-roll")
        if (!hasVideo) { taskFail("Import a video first"); return@launch }
        // B-roll is matched to the spoken words: make the captions first if there are none
        if (cues.isEmpty()) {
            brn = "No captions yet: transcribing the video first…"
            transcribe().join()
            if (cues.isEmpty()) { brn = "Could not make captions, so there are no words to match B-roll to. " + asrn; taskFail(brn); return@launch }
        }
        taskBegin("Auto B-roll", "brauto")
        val placed = try { autoBrollS(false) } catch (e: CancellationException) { taskClose("brauto"); throw e } catch (e: Throwable) { CrashLog.warn("auto b-roll", e); brn = e.message ?: e.toString(); false }
        if (placed) taskEnd("Done · B-roll placed", "brauto") else taskFail(brn, "brauto")
    }

    private suspend fun autoBrollS(fromQueue: Boolean): Boolean {
        if (brBusy) { brAgain = true; return true }
        brBusy = true
        try {
            if (cues.isEmpty()) { brn = "Need captions first: use Auto-transcribe or paste a transcript, then press this again."; return false }
            brn = "Scanning the B-roll folder…"
            val lib = try { withContext(Dispatchers.IO) { Library.list(ctx) } } catch (e: Exception) {
                brn = "Could not read the B-roll list (${e.message}). Check Settings → Library source."; return false
            }
            val files = lib.broll
            if (files.isEmpty()) { brn = "No B-roll found. Put your clips in the \"${lib.brollFolder}\" folder of your library (e.g. blood_01.mp4, heart1.mp4)."; return false }
            autoOn.br = true
            brTargets = files.map { BrMatch.key(it.name).joinToString(" ") }.filter { it.isNotEmpty() }.distinct()
            val idx = BrMatch.index(files, brRules)
            val W = cues.flatMap { it.wt }.sortedBy { it.s }
            val ev = BrMatch.events(idx, W, ranges()) { otime(it) }
            val need = LinkedHashSet<BrFile>(); ev.forEach { e -> need.addAll(e.k.files) }
            brn = "Loading ${need.size} matching clip(s)…"
            withContext(Dispatchers.IO) {
                for (f in need) {
                    val key = f.url + "|" + f.version
                    f.asset = brAssets[key] ?: runCatching {
                        val u = Library.resolve(ctx, f)
                        BrAsset.load(ctx, assetSeq++, f.name, u)
                    }.getOrNull()?.also { brAssets[key] = it }
                }
            }
            val plan = BrMatch.plan(ev, brMax(), clipLen())
            broll = plan.map { BrollItem(brSeq++, it.a, it.start, it.dur, true) }
            bump()
            val cnt = LinkedHashMap<String, Int>(); plan.forEach { cnt[it.k.key] = (cnt[it.k.key] ?: 0) + 1 }
            val used = cnt.entries.joinToString(", ") { "${it.key} ×${it.value}" }
            val all = idx.keys.keys.joinToString(", ")
            val miss = if (idx.missing.isNotEmpty()) " No clip found for rule target: " + idx.missing.joinToString(", ") + "." else ""
            brn = (if (plan.isNotEmpty()) "Placed ${plan.size} clip(s), max ${brMax()}s each: $used." else "No caption word matched a B-roll filename.") + " Keywords available: $all.$miss"
            stat = if (plan.isNotEmpty()) "${plan.size} B-roll clip(s) inserted from captions." else "No keyword matches in the captions."
            if (s.sfxcut) {
                try { val LL = withContext(Dispatchers.IO) { Library.list(ctx) }; if (LL.trans.isNotEmpty() || transList.isNotEmpty()) loadLibrary() } catch (e: Exception) {}
                if (autoOn.sfx && sfxList.isNotEmpty()) { if (!fromQueue) placeSfx(false) }
                else { val nt = placeTrans(); bump(); if (nt > 0) stat += " + $nt transition sound(s)." }
            }
            return plan.isNotEmpty() // nothing matched: the toast shows why (brn)
        } finally {
            brBusy = false
            if (brAgain) { brAgain = false; viewModelScope.launch(safe) { autoBrollS(true) } }
        }
    }

    /* ================= 5 · music + hook SFX ================= */
    private fun libNote(t: String) { libn = t; taskMsg(t) }
    private fun srvDown(e: Exception) {
        why = "Your music / SFX / B-roll pack is not ready: " + (e.message ?: e.toString())
        libNote("Could not read your library (${e.message ?: e}). Open Settings → Model & assets and download the pack, or pick a folder on this phone.")
    }
    private fun fillBsel(names: List<String>) {
        bselOptions = names
        if (s.bsel != "auto" && s.bsel !in names) s.bsel = "auto"
    }

    private suspend fun loadLibrary(): LibListing {
        libJob?.let { if (it.isActive) return it.await() }
        val job = viewModelScope.async {
            val L = withContext(Dispatchers.IO) { Library.list(ctx) }
            suspend fun get(f: BrFile, kind: Char, i: Int, n: Int): Track? {
                val key = "$kind|${f.url}|${f.version}"
                anCache[key]?.let { return it }
                libNote("Analysing " + (if (kind == 'b') "music " else "SFX ") + "${i + 1}/$n…")
                return try { withContext(Dispatchers.IO) { Library.analyse(ctx, f, kind) }.also { anCache[key] = it } } catch (e: Exception) { android.util.Log.w("Library", f.name, e); null }
            }
            val nb = L.bgm.mapIndexedNotNull { i, f -> get(f, 'b', i, L.bgm.size) }.toMutableList()
            val ns = L.sfx.mapIndexedNotNull { i, f -> get(f, 's', i, L.sfx.size) }
            val nt = L.trans.mapIndexedNotNull { i, f -> get(f, 't', i, L.trans.size) }
            val len = clipLen(); nb.forEach { Library.scoreBgm(it, len) }; nb.sortByDescending { it.sc }
            bgmList = nb; sfxList = ns; transList = nt
            sfxTracks = ns; transTracks = nt
            fillBsel(nb.map { it.name })
            libNote("${nb.size} music track(s), ${ns.size} hook SFX, ${nt.size} transition SFX ready. Folders are re-scanned each time you use Auto BGM / Auto Hook SFX.")
            L
        }
        libJob = job
        return job.await()
    }

    fun bgmPick(): Track? {
        if (bgmList.isEmpty()) return null
        val v = s.bsel
        var p = bgmList.find { it.name == v }
        if (p == null && bgmChoice != null) p = bgmList.find { it.name == bgmChoice }
        return p ?: bgmList[0]
    }

    private suspend fun autoBgmS(): Boolean {
        autoOn.bgm = true; s.bsel = "auto"
        try { loadLibrary() } catch (e: CancellationException) { throw e } catch (e: Exception) { if (bgmList.isEmpty()) { autoOn.bgm = false; srvDown(e); return false } }
        if (bgmList.isEmpty()) { autoOn.bgm = false; why = "No music in the \"bgm\" folder of your R2 pack."; libNote(why!!); return false }
        val L = clipLen(); bgmList.forEach { Library.scoreBgm(it, L) }; bgmList = bgmList.sortedByDescending { it.sc }
        if (!keepPick || bgmChoice == null || bgmList.none { it.name == bgmChoice }) bgmChoice = Library.pickRand(bgmList, bgmChoice).name
        val P = bgmPick()!!
        Library.scoreBgm(P, L)
        bump(); restartPreview()
        stat = "Auto BGM (random pick): \"${P.name}\" (starting ${"%.1f".format(P.off)}s into the track, ducked under speech). Press Auto BGM again for a different one."
        return true
    }
    fun autoBgm() { CrashLog.mark("auto bgm"); if (App.prefs.libSource != "r2") Library.invalidate(); launchTask("Auto BGM", "bgmbtn", "music applied") { autoBgmS() } }

    private suspend fun autoHookS(): Boolean {
        autoOn.sfx = true
        try { loadLibrary() } catch (e: CancellationException) { throw e } catch (e: Exception) { if (sfxList.isEmpty()) { autoOn.sfx = false; srvDown(e); return false } }
        if (sfxList.isEmpty()) { autoOn.sfx = false; why = "No sounds in the \"best hook\" folder of your R2 pack."; libNote(why!!); return false }
        placeSfx(true)
        stat += " Press Auto Hook SFX again for a different one. Press Preview to hear it."
        return true
    }
    fun autoHook() { CrashLog.mark("auto hook sfx"); if (App.prefs.libSource != "r2") Library.invalidate(); launchTask("Auto Hook SFX", "sfxbtn", "hook SFX applied") { autoHookS() } }

    fun onBselChange(v: String) = viewModelScope.launch(safe) {
        if (v == "auto") { autoBgm(); return@launch }
        autoOn.bgm = false
        if (bgmList.none { it.name == v }) try { loadLibrary() } catch (e: Exception) { srvDown(e) }
        s.bsel = v
        bump(); restartPreview()
    }

    fun sfxcutChanged() { if (autoOn.sfx) viewModelScope.launch(safe) { placeSfx(false) } else { placeTrans(); bump() } }

    private fun addSfx(t: Track, hit: Double, v: Double, md: Double = 4.0, tr: Boolean = false): SfxPlacement {
        val off = max(0.0, t.pk - hit)
        return SfxPlacement(t, max(0.0, hit - t.pk), off, hit, v, min(t.dur - off, md), tr)
    }

    /** transition sounds at the start of B-roll clips: random, ~70% of clips, never the same file twice in a row */
    private fun placeTrans(): Int {
        val keepList = sfxT.filter { !it.tr || it.manual }
        if (!s.sfxcut || transList.isEmpty()) { sfxT = keepList; return 0 }
        val R = Mulberry(trSeed)
        val bs = broll.filter { it.dur > 0 }.sortedBy { it.start }
        var last = -1; var n = 0
        val add = ArrayList<SfxPlacement>()
        for (b in bs) {
            val keep = R.next(); val pk = R.next()
            if (keep > 0.7) continue
            if (b.id in transSkip || keepList.any { it.tr && it.manual && kotlin.math.abs(it.hit - b.start) < 0.5 }) continue
            var i = floor(pk * transList.size).toInt().coerceIn(0, transList.size - 1)
            if (transList.size > 1 && i == last) i = (i + 1 + floor(R.next() * (transList.size - 1)).toInt()) % transList.size
            last = i; add.add(addSfx(transList[i], b.start, 0.7, 2.5, true).copy(brId = b.id)); n++
        }
        sfxT = keepList + add
        return n
    }

    private suspend fun placeSfx(reroll: Boolean) {
        if (sfxList.isEmpty()) { stat = "Set up your library first (Settings → Library source)."; return }
        val E = getEnv()
        var hit = 0.0
        val c0 = cues.firstOrNull()
        if (cutK == null && E != null) {
            var t = t0(); val end = min(t0() + 10, E.lengthSec)
            while (t < end && !E.loudAt(t)) t += E.step
            hit = max(0.0, t - t0() - 0.05)
        } else if (c0 != null) hit = max(0.0, otime(c0.s) - 0.05)
        if (reroll) trSeed = Random.nextInt(1_000_000_000)
        if (reroll || hookChoice == null || sfxList.none { it.name == hookChoice }) hookChoice = Library.pickRand(sfxList, hookChoice).name
        val hk = sfxList.first { it.name == hookChoice }
        val list = ArrayList<SfxPlacement>()
        list.add(addSfx(hk, hit, 1.0))
        if (s.sfxcut && transList.isEmpty()) {
            val T = sfxList.filter { it !== hk }.sortedByDescending { it.ts }
            if (T.isNotEmpty()) broll.filter { it.dur > 0 }.sortedBy { it.start }.forEachIndexed { i, b -> list.add(addSfx(T[i % T.size], b.start, 0.6)) }
        }
        sfxT = sfxT.filter { it.manual } + list
        val nt = placeTrans()
        bump()
        stat = "Hook SFX \"${hk.name}\" lands on the first spoken word at ${"%.2f".format(hit)}s." + (if (nt > 0) " + $nt transition sound(s) on B-roll." else "")
    }

    /** when start, length, silence cut or transcript change, redo whatever was switched to Auto */
    private var qJob: Job? = null
    private fun queueAuto() {
        if (!autoOn.bgm && !autoOn.sfx && !autoOn.br) return
        qJob?.cancel()
        qJob = viewModelScope.launch(safe) {
            delay(200)
            if (autoOn.br) autoBrollS(true)
            if (autoOn.bgm) { keepPick = true; try { autoBgmS() } finally { keepPick = false } }
            if (autoOn.sfx) placeSfx(false)
        }
    }

    /* ================= preview ================= */
    fun togglePlay() {
        if (playing || preview.playing) { preview.stop(); return }
        CrashLog.mark("play (b-roll ${broll.size}, sfx ${sfxT.size}, bgm ${bgmName() ?: "-"})")
        relieveMemory()
        preview.play()
    }

    private fun memInfo() = android.app.ActivityManager.MemoryInfo().also { ctx.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }
    private fun totalRamGb() = memInfo().totalMem / 1e9
    /** before heavy video work: if the phone is short on RAM, drop the speech model (it reloads when needed) */
    private fun relieveMemory() {
        val m = memInfo()
        if (m.lowMemory || m.availMem < 1_200_000_000L) App.freeMemory("only ${m.availMem / 1_000_000} MB free")
    }

    /* ---------- overlay effects ---------- */
    /** where clips start in the output (video start, silence cuts, B-roll in/out) */
    fun clipStarts(): List<Double> = if (!hasVideo) emptyList() else Fx.clipStarts(clipLen(), ranges(), broll)

    private fun refreshAutoFx() {
        val k = fxAuto ?: return
        if (!hasVideo) return
        val keep = overlays.filter { !it.auto }
        overlays = keep + Fx.plan(k, clipStarts(), clipLen(), s.fxLen, s.fxAmt, keep, fxSkip) { fxSeq++ }
    }

    /** put an overlay at the start of every clip (kind = effect id or "mix") */
    fun autoOverlay(kind: String = s.fxKind) = launchTask("Adding overlays", "fxbtn", "overlays on every clip") { autoOverlayS(kind) }

    private fun autoOverlayS(kind: String): Boolean {
        if (!hasVideo) { why = "Import a video first"; return false }
        fxAuto = kind; fxSkip.clear()
        bump() // refreshAutoFx places them
        val n = overlays.count { it.auto }
        fxn = "$n overlay${if (n == 1) "" else "s"} at clip starts. Tap one on the timeline to move, resize or delete it."
        return n > 0
    }

    /** add one overlay at the playhead */
    fun addOverlayAt(kind: String) {
        if (!hasVideo) { taskFail("Import a video first"); return }
        val id = if (kind == "mix") Fx.MIX[(fxSeq) % Fx.MIX.size] else kind
        val k = Fx.byId(id) ?: return
        val t = phRel.coerceIn(0.0, max(0.0, clipLen() - 0.1))
        overlays = overlays + OverlayItem(fxSeq++, id, t, (k.dur * s.fxLen).coerceAtMost(clipLen() - t).coerceAtLeast(0.1), s.fxAmt, false)
        com.jare.autoclipper.ui.UiSound.play(com.jare.autoclipper.ui.Snd.SUCCESS)
        bump()
    }

    fun removeOverlay(id: Int) {
        overlays.firstOrNull { it.id == id && it.auto }?.let { fxSkip.add(it.t) }
        selOv = null; overlays = overlays.filter { it.id != id }; bump()
    }

    /** move (dt), lengthen (dd) or change the strength (da) of one overlay; it becomes "yours" (auto runs leave it alone) */
    fun nudgeOverlay(id: Int, dt: Double, dd: Double, da: Double = 0.0) {
        val L = clipLen()
        overlays.firstOrNull { it.id == id && it.auto }?.let { fxSkip.add(it.t) }
        overlays = overlays.map {
            if (it.id != id) it else {
                val t = (it.t + dt).coerceIn(0.0, max(0.0, L - 0.1))
                it.copy(t = t, dur = (it.dur + dd).coerceIn(0.1, max(0.1, L - t)), amt = (it.amt + da).coerceIn(0.1, 1.0), auto = false)
            }
        }
        bump()
    }

    fun clearOverlays() { fxAuto = null; fxSkip.clear(); selOv = null; overlays = emptyList(); fxn = ""; bump() }

    /** strength / length sliders: apply to every overlay */
    fun fxSettingsChanged() {
        overlays = overlays.map { o ->
            val k = Fx.byId(o.kind)
            if (k == null) o else o.copy(amt = s.fxAmt, dur = (k.dur * s.fxLen).coerceAtMost(max(0.1, clipLen() - o.t)))
        }
        bump()
    }

    var fxn by mutableStateOf("")

    /* ---------- face tracking ---------- */
    /** find the people in the used part of the video (on this phone) and follow the main one */
    fun trackFaces() = launchTask("Tracking faces", "facebtn", "face tracking") {
        val uri = srcUri ?: run { why = "Import a video first"; return@launchTask false }
        val R = ranges()
        val a = R.minOf { it.a }; val b = R.maxOf { it.b }
        CrashLog.mark("face scan ${"%.0f".format(b - a)}s")
        preview.stop()
        val scan = withContext(Dispatchers.Default) {
            FaceTrack.scan(ctx, uri, a, b, srcW.coerceAtLeast(1), srcH.coerceAtLeast(1)) { f -> taskMsg("Tracking faces · ${(f * 100).toInt()}%", f) }
        }
        FaceTrack.unload() // the fingerprint model is only needed while scanning
        scan.setSpeech(speechTimes())
        faces = scan
        if (scan.persons.isEmpty()) {
            s.faceMode = "off"
            why = "No faces found in this part of the video"
            faceNote = why!!
            bump(); return@launchTask false
        }
        // follow whoever is on screen most (and biggest)
        val lead = scan.persons.maxByOrNull { it.coverage * it.size }!!
        s.facePerson = lead.id
        s.faceB = scan.persons.firstOrNull { it.id != lead.id }?.id ?: lead.id
        s.faceMode = if (scan.persons.size >= 2) "speaker" else "person"
        faceNote = if (scan.persons.size >= 2) "Found ${scan.persons.size} people. Following whoever is speaking; tap a person to lock on them."
            else "Found 1 person. The frame follows their face."
        bump(); true
    }

    /** spoken word times (source seconds), used to know when someone is talking */
    private fun speechTimes(): List<Pair<Double, Double>> = cues.flatMap { c -> c.wt.map { it.s to it.e } }

    fun followPerson(id: Int) { s.facePerson = id; if (s.faceMode == "off" || s.faceMode == "speaker") s.faceMode = "person"; if (s.faceB == id) faces?.persons?.firstOrNull { it.id != id }?.let { s.faceB = it.id }; bump() }
    fun setFaceMode(m: String) { s.faceMode = m; bump() }
    fun swapSplit() { val t = s.facePerson; s.facePerson = s.faceB; s.faceB = t; bump() }
    /** the scan covers a different part of the video than the current trim */
    fun facesStale(): Boolean { val f = faces ?: return false; val R = ranges(); return !f.covers(R.minOf { it.a }, R.maxOf { it.b }) }

    /* ---------- timeline selection ---------- */
    var selBroll by mutableStateOf<Int?>(null)
    var selSfx by mutableStateOf<Int?>(null)
    var selOv by mutableStateOf<Int?>(null)
    fun select(broll: Int? = null, sfx: Int? = null, ov: Int? = null) { selBroll = broll; selSfx = sfx; selOv = ov }
    /** move (dStart) or lengthen/shorten (dDur) the selected B-roll clip */
    fun nudgeBroll(id: Int, dStart: Double, dDur: Double) {
        val L = clipLen()
        broll = broll.map {
            if (it.id != id) it else {
                val st = (it.start + dStart).coerceIn(0.0, max(0.0, L - 0.3))
                it.copy(start = st, dur = (it.dur + dDur).coerceIn(0.3, max(0.3, L - st)), auto = false)
            }
        }
        if (sfxT.any { it.tr }) placeTrans()
        bump()
    }

    /** one tap: captions → B-roll → music → hook SFX */
    fun autoEdit() = viewModelScope.launch(safe) {
        if (srcUri == null) { taskFail("Import a video first"); return@launch }
        CrashLog.mark("auto edit")
        busy["magic"] = true
        try {
            if (cues.isEmpty()) transcribe().join()
            if (cues.isEmpty()) return@launch
            taskBegin("Auto edit: B-roll, overlays, music, hook SFX")
            val b = autoBrollS(false); val o = autoOverlayS(s.fxKind); val m = autoBgmS(); val h = autoHookS()
            if (b || o || m || h) taskEnd("Done · auto edit applied") else taskFail("Auto edit: nothing to add · open Settings → Model & assets")
        } finally { busy.remove("magic") }
    }
    private fun restartPreview() {
        if (!preview.playing) return
        preview.stop()
        main.postDelayed({ preview.play() }, 300)
    }
    fun scrubTo(sec: Double) { if (!hasVideo) return; phRel = sec.coerceIn(0.0, clipLen()); preview.requestStill() }
    fun seekFraction(f: Double) { if (!hasVideo) return; phRel = (f.coerceIn(0.0, 1.0) * clipLen()); preview.requestStill() }

    /** exact export mix, used by the preview player (called on its own thread) */
    fun mixBlocking(cancel: () -> Boolean): FloatArray = runBlocking { mixSuspend(cancel) }

    private val mixLock = Mutex()
    private var mixKey: String? = null
    private var mixData: FloatArray? = null
    /** the exact export mix, cached until the edit changes */
    suspend fun mixSuspend(cancel: () -> Boolean): FloatArray = mixLock.withLock {
        val E = getEnv()
        val L = clipLen(); val R = ranges()
        val pick = bgmPick()?.also { Library.scoreBgm(it, L) }
        val key = listOf(srcUri, R, blIv.map { it.s to it.e }, pick?.name, pick?.off, s.bv, L,
            sfxT.map { "${it.s.name}@${it.t}:${it.off}:${it.dur}:${it.v}" }).toString()
        val cached = mixData
        if (key == mixKey && cached != null) return@withLock cached
        val m = withContext(Dispatchers.Default) { AudioMixer.mix(ctx, srcUri!!, R, L, blIv, { otime(it) }, { stime(it) }, E, pick, s.bv, sfxT, cancel) }
        mixKey = key; mixData = m
        m
    }

    /* ---------- timeline helpers: thumbnails + waveform ---------- */
    var thumbs by mutableStateOf<List<Pair<Double, ImageBitmap>>>(emptyList())
    var thumbSpan by mutableDoubleStateOf(1.0)
    private var thumbJob: Job? = null
    fun refreshThumbs() {
        thumbJob?.cancel()
        val uri = srcUri ?: return
        val L = clipLen(); val R = ranges()
        val n = L.toInt().coerceIn(6, 40); val step = L / n
        thumbJob = viewModelScope.launch(safe + Dispatchers.IO) {
            delay(300)
            val r = MediaMetadataRetriever()
            try {
                MediaUtil.setSource(r, ctx, uri)
                val out = ArrayList<Pair<Double, ImageBitmap>>()
                for (i in 0 until n) {
                    if (!isActive) break
                    val rel = i * step
                    val b = r.getScaledFrameAtTime((Core.stime(R, rel) * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 180, 180) ?: continue
                    out.add(rel to b.asImageBitmap())
                    if (i % 6 == 5) { val snap = out.toList(); post { thumbs = snap; thumbSpan = step } }
                }
                val fin = out.toList(); post { thumbs = fin; thumbSpan = step }
            } catch (e: Exception) { android.util.Log.w("Thumbs", "failed", e) } finally { r.release() }
        }
    }

    /** loudness of the finished clip (0..1), n points, for the waveform */
    fun waveform(n: Int): FloatArray {
        val E = env ?: return FloatArray(0)
        val L = clipLen(); val R = ranges()
        return FloatArray(n) { i ->
            val idx = (Core.stime(R, i * L / n) / E.step).toInt()
            if (idx in E.db.indices) ((E.db[idx] + 55f) / 50f).coerceIn(0.03f, 1f) else 0.03f
        }
    }

    /** with the local pack, load B-roll clip info up front so Auto B-roll only has to match words */
    private suspend fun preloadBroll(L: LibListing) {
        withContext(Dispatchers.IO) {
            for (f in L.broll) {
                val key = f.url + "|" + f.version
                if (brAssets.containsKey(key)) { f.asset = brAssets[key]; continue }
                runCatching { BrAsset.load(ctx, assetSeq++, f.name, Library.resolve(ctx, f)) }.getOrNull()?.let { brAssets[key] = it; f.asset = it }
            }
        }
    }

    /* ================= export ================= */
    fun openExport() {
        if (!hasVideo) { taskFail("Load a source video first"); return }
        preview.stop()
        export = ExportUi(0, lastRes, lastFps)
    }
    fun exportSummary(res: Int, fps: Int): String {
        val br = (if (res == 2160) 45e6 else 14e6) * (if (fps > 30) 1.4 else 1.0)
        val L = clipLen(); val mb = (br + 192e3) * L / 8e6
        val wh = if (res == 2160) "2160×3840" else "1080×1920"
        return "$wh · $fps fps · ${"%.1f".format(L)}s · about ${mb.roundToInt()} MB · MP4 (H.264 + AAC)"
    }
    fun setExportOpt(res: Int? = null, fps: Int? = null) { export = export?.copy(res = res ?: export!!.res, fps = fps ?: export!!.fps) }
    fun closeExport() { if (export?.stage == 1) return; export = null }
    fun cancelExport() { exportCancel = true }

    fun runExport() {
        val ui = export ?: return
        CrashLog.mark("export ${ui.res}p ${ui.fps}fps"); relieveMemory()
        lastRes = ui.res; lastFps = ui.fps
        exportCancel = false
        val res = ui.res; val fps = ui.fps
        val w = if (res == 2160) 2160 else 1080; val h = if (res == 2160) 3840 else 1920
        val bitrate = ((if (res == 2160) 45e6 else 14e6) * (if (fps > 30) 1.4 else 1.0)).toInt()
        val name = "dweis-edit-" + (if (res == 2160) "4k" else "1080p") + "-${fps}fps-${System.currentTimeMillis() / 1000}.mp4"
        export = ui.copy(stage = 1, pct = 0f, title = "Exporting ${w}×$h…", note = "Checking this device…")
        fun upd(p: Float?, n: String?) = post { export = export?.let { e -> if (e.stage != 1) e else e.copy(pct = p ?: e.pct, note = n ?: e.note) } }
        viewModelScope.launch(safe + Dispatchers.Default) {
            try {
                if (!Exporter.canEncode(w, h, fps)) throw IOException("This phone cannot encode ${w}×$h at $fps fps. Try Full HD or 30 fps.")
                upd(0.02f, "Mixing audio (speech, music, SFX)…")
                val audio = mixSuspend { exportCancel }
                upd(0.08f, "Encoding audio…")
                val enc = Exporter.encodeAudio(audio) { exportCancel }
                upd(0.10f, "Rendering on the GPU…")
                val out = File(File(ctx.cacheDir, "exports").apply { mkdirs() }, name)
                val t1 = System.currentTimeMillis()
                preview.exportVideo(out, w, h, fps, bitrate, enc, { exportCancel }) { i, n ->
                    val el = (System.currentTimeMillis() - t1) / 1000.0; val eta = el / (i + 1) * (n - i - 1)
                    upd(0.10f + 0.86f * i / n, "Frame ${i + 1} of $n · about " + (if (eta > 60) "${Math.ceil(eta / 60).toInt()} min" else "${Math.ceil(eta).toInt()} s") + " left")
                }
                upd(0.97f, "Saving to your gallery (Movies/AutoClipper)…")
                val uri = saveToGallery(out, name)
                com.jare.autoclipper.ui.UiSound.play(com.jare.autoclipper.ui.Snd.SUCCESS)
                post { export = export?.copy(stage = 2, pct = 1f, title = "✓ Done · export complete", note = "Saved to Movies/AutoClipper/$name", outUri = uri, outName = name); stat = "Exported $name" }
            } catch (e: Throwable) {
                val msg = if (e is Exporter.Cancelled || exportCancel) "Cancelled." else (e.message ?: e.toString())
                android.util.Log.e("Export", "failed", e)
                if (msg != "Cancelled.") com.jare.autoclipper.ui.UiSound.play(com.jare.autoclipper.ui.Snd.ERROR)
                post { export = export?.copy(stage = 3, title = "Export stopped", note = msg) }
            }
        }
    }

    private fun saveToGallery(f: File, name: String): Uri {
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AutoClipper")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val cr = ctx.contentResolver
        val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: throw IOException("Could not create the gallery file")
        cr.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o, 1 shl 16) } }
        cr.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        f.delete()
        return uri
    }

    /* ================= edit report ================= */
    private fun f1(x: Double) = "%.1f".format(x)
    fun report() {
        val L = clipLen()
        val sb = StringBuilder("Source range: ${f1(t0())}s → ${f1(cutK?.last()?.b ?: (t0() + L))}s (${f1(L)}s)\nCaptions: ${cues.size} cues" + (if (approx) " (estimated timings)" else "") + "\n")
        cutK?.let { sb.append("Silence cut: ${it.size - 1} jump cuts, ${f1((it.last().b - t0()) - L)}s removed\n") }
        val used = broll.filter { it.dur > 0 }
        sb.append("B-roll cutaways: ${used.size}\n")
        used.sortedBy { it.start }.forEach { sb.append("  ${f1(it.start)}–${f1(it.start + it.dur)}s  ${it.asset.name}\n") }
        if (segs.isEmpty()) sb.append("Warning: no transcript loaded.\n")
        if (approx) sb.append("Warning: transcript timings were estimated.\n")
        if (s.zm != "none") sb.append("Zoom ${s.zm} ${s.zs.roundToInt()}% over " + (if (s.zw) "the whole clip" else "${f1(s.zd)}s") + "\n")
        bgmPick()?.let { sb.append("Music: ${it.name} (ducks under speech)\n") }
        sfxT.forEach { sb.append("SFX: ${it.s.name} hit at ${"%.2f".format(it.hit)}s\n") }
        rep = sb.toString()
    }

    /** timeline rows for the UI */
    fun sfxRows() = sfxT
    fun bgmName() = bgmPick()?.name

    override fun onCleared() { preview.release(); super.onCleared() }
}
