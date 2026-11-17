package com.jare.autoclipper.model

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jare.autoclipper.App
import com.jare.autoclipper.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Keeps the Parakeet model inside the app (internal storage). It is downloaded from your
 * Cloudflare R2 bucket exactly once (resumable), then always loaded from the phone.
 */
class ModelManager(private val ctx: Context) {
    companion object {
        /** your Cloudflare Worker in front of the R2 bucket (fast, see r2-worker.js) */
        const val DEFAULT_BASE = "https://clipper-files.enterprisejare.workers.dev"
        /** the bucket's own r2.dev address: slower, used only if the Worker fails */
        const val FALLBACK_BASE = "https://pub-cffd9b31b5de4a139c407d69c24d4654.r2.dev"
        const val ENCODER = "encoder-model.int8.onnx"
        const val DECODER = "decoder_joint-model.int8.onnx"
        const val VOCAB = "vocab.txt"
        val FILES = listOf(ENCODER, DECODER, VOCAB)
        const val WORK = "parakeet-download"
        const val CHANNEL = "model_download"
    }

    val dir = File(ctx.filesDir, "models/parakeet-tdt-0.6b-v2-int8").apply { mkdirs() }
    /** true when the music / SFX / B-roll pack from R2 is on the phone */
    fun packReady(): Boolean = AssetPack.manifest(ctx) != null
    private val marker get() = File(dir, "ready.json")

    fun isReady(): Boolean = marker.exists() && FILES.all { File(dir, it).length() > 0 }

    fun sizeOnDisk(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun start(wifiOnly: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, req)
    }

    fun cancel() = WorkManager.getInstance(ctx).cancelUniqueWork(WORK)

    fun progress(): Flow<List<WorkInfo>> = WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(WORK)

    fun delete() {
        com.jare.autoclipper.asr.ParakeetEngine.unload()
        dir.listFiles()?.forEach { it.delete() }
    }

    internal fun markReady(sizes: Map<String, Long>) {
        marker.writeText(JSONObject(sizes as Map<*, *>).toString())
    }
}

class ModelDownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val nid = 4107

    private fun notif(text: String, pct: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(ModelManager.CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(ModelManager.CHANNEL, "Model download", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, ModelManager.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Downloading Parakeet model")
            .setContentText(text)
            .setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(100, pct.coerceIn(0, 100), pct < 0)
            .build()
        return ForegroundInfo(nid, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = notif("Starting…", -1)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val mm = ModelManager(applicationContext)
        runCatching { setForeground(notif("Starting…", -1)) }
        val primary = App.prefs.modelBase.trim().trimEnd('/').ifEmpty { ModelManager.DEFAULT_BASE }
        val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
        var lastUi = 0L
        suspend fun report(done: Long, total: Long, label: String, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastUi < 500) return
            lastUi = now
            setProgress(workDataOf("done" to done, "total" to total, "file" to label))
            runCatching { setForeground(notif("$label · ${done / 1_048_576} / ${total / 1_048_576} MB", (done * 100 / total.coerceAtLeast(1)).toInt())) }
        }
        suspend fun attempt(base: String) {
            // ---- what is needed: model files (unless already here) + changed R2 assets ----
            val modelNeeded = !mm.isReady()
            val sizes = LinkedHashMap<String, Long>()
            if (modelNeeded) for (f in ModelManager.FILES) {
                sizes[f] = try {
                    http.newCall(Request.Builder().url("$base/$f").head().build()).execute().use { r ->
                        if (r.code == 404) throw IOException("$f not found at $base (check the model address in Settings)")
                        if (r.isSuccessful) r.header("Content-Length")?.toLongOrNull() ?: -1L else -1L
                    }
                } catch (e: IOException) { if (e.message?.contains("not found") == true) throw e else -1L }
            }
            val remote = AssetPack.remote(http, base)
            val todo = remote?.let { AssetPack.missing(applicationContext, it.second) } ?: emptyList()
            val total = (sizes.values.sumOf { if (it > 0) it else 0L } + todo.sumOf { if (it.size > 0) it.size else 0L }).coerceAtLeast(1L)
            var done = 0L

            // ---- model ----
            for (f in sizes.keys) {
                val target = File(mm.dir, f)
                val expect = sizes[f] ?: -1L
                if (target.exists() && expect > 0 && target.length() == expect) { done += expect; continue }
                target.delete()
                val part = File(mm.dir, "$f.part")
                var have = if (part.exists()) part.length() else 0L
                if (expect > 0 && have > expect) { part.delete(); have = 0 }
                val rb = Request.Builder().url("$base/$f")
                if (have > 0) rb.header("Range", "bytes=$have-")
                val before = done
                http.newCall(rb.build()).execute().use { r ->
                    if (r.code == 416 && expect > 0 && have == expect) return@use
                    if (!r.isSuccessful) throw IOException("$f: HTTP ${r.code}")
                    if (have > 0 && r.code != 206) have = 0 // server ignored the range: restart this file
                    RandomAccessFile(part, "rw").use { raf ->
                        raf.setLength(have); raf.seek(have)
                        val inp = r.body!!.byteStream()
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            if (isStopped) throw IOException("stopped")
                            val n = inp.read(buf); if (n < 0) break
                            raf.write(buf, 0, n); have += n
                            report(before + have, total, f)
                        }
                    }
                }
                if (expect > 0 && part.length() != expect) throw IOException("$f: incomplete (${part.length()} of $expect bytes)")
                if (!part.renameTo(target)) throw IOException("Could not save $f")
                done = before + target.length()
            }
            if (modelNeeded) mm.markReady(ModelManager.FILES.associateWith { File(mm.dir, it).length() })

            // ---- music / SFX / B-roll pack from R2 ----
            if (remote != null) {
                // 4 files at a time, progress updated while bytes arrive
                val bytes = AtomicLong(done)
                val slots = Semaphore(4)
                coroutineScope {
                    val jobs = todo.map { e ->
                        async(Dispatchers.IO) {
                            slots.withPermit { AssetPack.download(applicationContext, http, base, e, { isStopped }) { n -> bytes.addAndGet(n) } }
                        }
                    }
                    while (jobs.any { it.isActive }) { report(bytes.get(), total, "Music, SFX & B-roll"); delay(300) }
                    jobs.awaitAll()
                }
                done = bytes.get()
                AssetPack.commit(applicationContext, remote.first, remote.second)
                // measure the audio now (2 at a time), so Auto BGM / Auto Hook are instant in the editor
                com.jare.autoclipper.editor.Library.invalidate()
                val audio = remote.second.filter { it.kind == "bgm" || it.kind == "sfx" || it.kind == "trans" }
                val ready = AtomicInteger(0)
                val cpu = Semaphore(2)
                coroutineScope {
                    val jobs = audio.map { e ->
                        async(Dispatchers.Default) {
                            cpu.withPermit {
                                if (!isStopped) runCatching {
                                    val f = AssetPack.file(applicationContext, e)
                                    com.jare.autoclipper.editor.Library.analyse(applicationContext,
                                        com.jare.autoclipper.editor.BrFile(f.name, android.net.Uri.fromFile(f).toString(), f.length(), e.sha256),
                                        when (e.kind) { "bgm" -> 'b'; "sfx" -> 's'; else -> 't' })
                                }
                            }
                            ready.incrementAndGet()
                        }
                    }
                    while (jobs.any { it.isActive }) {
                        setProgress(workDataOf("done" to total, "total" to total, "file" to "Preparing audio ${ready.get()}/${audio.size}"))
                        delay(300)
                    }
                    jobs.awaitAll()
                }
                if (isStopped) throw IOException("stopped")
            }
            setProgress(workDataOf("done" to total, "total" to total, "file" to "done"))
        }
        // try the Worker first; if it fails (down, daily limit), resume from the r2.dev address
        var err: Exception? = null
        for (base in listOf(primary, ModelManager.FALLBACK_BASE).distinct()) {
            try { attempt(base); return@withContext Result.success() }
            catch (e: Exception) { err = e; if (isStopped) break; com.jare.autoclipper.CrashLog.warn("download from $base", e) }
        }
        val e = err ?: IOException("download failed")
        run {
            if (isStopped) Result.failure(workDataOf("error" to "Cancelled"))
            else if (runAttemptCount < 6) Result.retry()
            else Result.failure(workDataOf("error" to (e.message ?: e.toString())))
        }
    }
}
