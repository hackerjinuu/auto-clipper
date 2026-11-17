package com.jare.autoclipper.asr

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.jare.autoclipper.CrashLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Runs Parakeet in its OWN process (":asr", see AndroidManifest). The speech model needs about
 * 1 GB of RAM; if the phone runs out, Android stops only this process and the editor stays open.
 * When a job is done the process becomes "cached", so Android frees it first whenever memory is
 * needed, and keeps it (instant next time) when there is room.
 */
class AsrService : Service() {
    companion object {
        const val MSG_RUN = 1
        const val MSG_PROGRESS = 2
        const val MSG_DONE = 3
        const val MSG_ERROR = 4
    }

    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg -> handle(msg); true })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun handle(msg: Message) {
        if (msg.what != MSG_RUN) return
        val reply = msg.replyTo ?: return
        val inPath = msg.data?.getString("in") ?: return
        val outPath = msg.data?.getString("out") ?: return
        Thread({
            try {
                val pcm = readFloats(File(inPath))
                var last = -1
                val words = ParakeetEngine.transcribeSafe(applicationContext, pcm, { p ->
                    val pc = (p * 100).toInt()
                    if (pc != last) { last = pc; send(reply, MSG_PROGRESS, pc, null) }
                })
                File(outPath).bufferedWriter().use { w ->
                    for (x in words) w.write("${x.s}\t${x.e}\t${x.text.replace('\t', ' ').replace('\n', ' ')}\n")
                }
                send(reply, MSG_DONE, 0, null)
            } catch (t: Throwable) {
                send(reply, MSG_ERROR, 0, t.message ?: t.javaClass.simpleName)
            }
        }, "asr-run").start()
    }

    private fun send(to: Messenger, what: Int, arg: Int, err: String?) {
        runCatching {
            val m = Message.obtain(null, what, arg, 0)
            if (err != null) m.data = Bundle().apply { putString("err", err) }
            to.send(m)
        }
    }

    private fun readFloats(f: File): FloatArray = RandomAccessFile(f, "r").use { raf ->
        val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        FloatArray(buf.remaining()).also { buf.get(it) }
    }
}

/** Editor side: hands the audio to the :asr process and waits for the words. */
object AsrClient {
    const val DIED = "The phone ran out of memory while transcribing, so Android stopped the speech model. " +
        "The editor kept running. Close other apps (or restart the phone) and try again."

    suspend fun transcribe(ctx: Context, pcm: FloatArray, progress: (Float) -> Unit): List<ParakeetEngine.Word> {
        val app = ctx.applicationContext
        val dir = File(app.cacheDir, "asr").apply { mkdirs() }
        val inF = File(dir, "in.bin"); val outF = File(dir, "out.tsv")
        withContext(Dispatchers.IO) {
            outF.delete()
            RandomAccessFile(inF, "rw").use { raf ->
                raf.setLength(0)
                val bb = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
                var i = 0
                while (i < pcm.size) {
                    bb.clear()
                    while (i < pcm.size && bb.remaining() >= 4) bb.putFloat(pcm[i++])
                    bb.flip(); raf.channel.write(bb)
                }
            }
        }
        val result = CompletableDeferred<Unit>()
        val replies = Messenger(Handler(Looper.getMainLooper()) { m ->
            when (m.what) {
                AsrService.MSG_PROGRESS -> progress(m.arg1 / 100f)
                AsrService.MSG_DONE -> result.complete(Unit)
                AsrService.MSG_ERROR -> result.completeExceptionally(IOException(m.data?.getString("err") ?: "Transcription failed"))
            }
            true
        })
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
                try {
                    b!!.linkToDeath({ result.completeExceptionally(IOException(DIED)) }, 0)
                    val msg = Message.obtain(null, AsrService.MSG_RUN)
                    msg.replyTo = replies
                    msg.data = Bundle().apply { putString("in", inF.path); putString("out", outF.path) }
                    Messenger(b).send(msg)
                } catch (e: Exception) { result.completeExceptionally(e) }
            }
            override fun onServiceDisconnected(name: ComponentName?) { result.completeExceptionally(IOException(DIED)) }
        }
        try {
            if (!app.bindService(Intent(app, AsrService::class.java), conn, Context.BIND_AUTO_CREATE))
                throw IOException("Could not start the transcription process")
            try { result.await() } catch (e: IOException) { if (e.message == DIED) CrashLog.mark("speech model process was stopped by Android (out of memory)"); throw e }
            return withContext(Dispatchers.IO) {
                outF.readLines().mapNotNull { l ->
                    val p = l.split('\t', limit = 3)
                    if (p.size < 3) null else ParakeetEngine.Word(p[2], p[0].toDouble(), p[1].toDouble())
                }
            }
        } finally {
            runCatching { app.unbindService(conn) }
            inF.delete()
        }
    }
}
