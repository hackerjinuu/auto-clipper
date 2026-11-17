package com.jare.autoclipper.editor

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.jare.autoclipper.CrashLog
import com.jare.autoclipper.media.MediaUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One person found in the video. x/y = face centre per sample (0..1 of the picture), NaN where not seen. */
class Person(
    val id: Int, val label: String, val coverage: Float,
    val x: FloatArray, val y: FloatArray, val mouth: FloatArray, val size: Float, val thumb: Bitmap?,
) {
    /** smoothed camera paths, one per smoothness level, built on first use */
    private val paths = HashMap<String, Pair<FloatArray, FloatArray>>()
    @Synchronized
    fun path(smooth: String): Pair<FloatArray, FloatArray> = paths.getOrPut(smooth) { FaceTrack.camera(x, y, smooth) }
}

/** Result of a face scan: samples at source times t0, t0+dt, … */
class FaceScan(val uri: Uri, val t0: Double, val dt: Double, val n: Int, val persons: List<Person>) {
    fun person(id: Int) = persons.firstOrNull { it.id == id }
    fun covers(a: Double, b: Double) = a >= t0 - dt && b <= t0 + dt * n + dt

    private fun idx(t: Double): Double = ((t - t0) / dt).coerceIn(0.0, (n - 1).toDouble())

    /** where the camera should centre (0..1 of the picture) for this person at source time t: [x, y, face height] */
    fun cam(id: Int, smooth: String, t: Double): FloatArray? {
        val p = person(id) ?: return null
        if (n == 0) return null
        val (px, py) = p.path(smooth)
        val f = idx(t); val i = f.toInt(); val j = min(n - 1, i + 1); val u = (f - i).toFloat()
        return floatArrayOf(px[i] + (px[j] - px[i]) * u, py[i] + (py[j] - py[i]) * u, p.size)
    }

    /* ---------- who is speaking ---------- */
    @Volatile private var speakers: IntArray? = null
    private var speechKey = -1

    /** speech = (start, end) source times of spoken words; recomputes only when they change */
    @Synchronized
    fun setSpeech(speech: List<Pair<Double, Double>>) {
        val key = speech.hashCode()
        if (key == speechKey && speakers != null) return
        speechKey = key
        speakers = FaceTrack.speakers(this, speech)
    }

    /** person who is talking at source time t (holds the last speaker through pauses) */
    fun speakerAt(t: Double): Int? {
        val s = speakers ?: return persons.firstOrNull()?.id
        if (s.isEmpty()) return null
        return s[idx(t).toInt()].takeIf { it > 0 }
    }
}

/**
 * Local face tracking, all on the phone:
 * - ML Kit (accurate mode) finds faces + 5 landmarks in each sampled frame,
 * - SFace (OpenCV Zoo, Apache-2.0) turns each face into a 128-number fingerprint,
 *   so people are recognised by their face, not just by where they sit,
 * - mouth landmarks give mouth movement, used to tell who is speaking.
 */
object FaceTrack {
    private class Det(
        val x: Float, val y: Float, val w: Float, val h: Float,
        val mouth: Float, val emb: FloatArray?, var thumb: Bitmap? = null,
    )
    private class Track(var lx: Float, var ly: Float, var lw: Float, var li: Int, var le: FloatArray?) {
        val pts = HashMap<Int, Det>()
        fun mean(): FloatArray? = meanEmb(pts.values.mapNotNull { it.emb })
    }

    /* ---------- SFace face fingerprints ---------- */
    private val env by lazy { OrtEnvironment.getEnvironment() }
    @Volatile private var sface: OrtSession? = null
    private fun sface(ctx: Context): OrtSession? = sface ?: synchronized(this) {
        sface ?: runCatching {
            val bytes = ctx.assets.open("models/sface.onnx").use { it.readBytes() }
            env.createSession(bytes, OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) })
        }.onFailure { CrashLog.warn("sface load", it) }.getOrNull().also { sface = it }
    }
    /** free the fingerprint model (about 40 MB) */
    fun unload() = synchronized(this) { runCatching { sface?.close() }; sface = null }

    // where the 5 points sit in SFace's 112×112 input (eye on the image left, other eye, nose, mouth left, mouth right)
    private val TEMPLATE = floatArrayOf(38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f)

    private fun embed(sess: OrtSession, bmp: Bitmap, f: Face): FloatArray? {
        // ML Kit "left" is the person's left = the image right
        val order = intArrayOf(FaceLandmark.RIGHT_EYE, FaceLandmark.LEFT_EYE, FaceLandmark.NOSE_BASE, FaceLandmark.MOUTH_RIGHT, FaceLandmark.MOUTH_LEFT)
        val pts = order.map { f.getLandmark(it)?.position ?: return null }
        val m = similarity(pts) ?: return null
        val face = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
        try {
            Canvas(face).drawBitmap(bmp, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            val px = IntArray(112 * 112); face.getPixels(px, 0, 112, 0, 0, 112, 112)
            val fb = FloatBuffer.allocate(3 * 112 * 112)
            val plane = 112 * 112
            for (i in 0 until plane) { val c = px[i]; fb.put(i, ((c shr 16) and 255).toFloat()); fb.put(plane + i, ((c shr 8) and 255).toFloat()); fb.put(2 * plane + i, (c and 255).toFloat()) }
            OnnxTensor.createTensor(env, fb, longArrayOf(1, 3, 112, 112)).use { x ->
                sess.run(mapOf("data" to x)).use { r ->
                    @Suppress("UNCHECKED_CAST")
                    val out = (r[0].value as Array<FloatArray>)[0]
                    return normalize(out)
                }
            }
        } catch (t: Throwable) {
            return null
        } finally { face.recycle() }
    }

    /** least-squares rotation + scale + shift mapping the 5 points onto the template */
    private fun similarity(src: List<PointF>): Matrix? {
        var sx = 0f; var sy = 0f; var dx = 0f; var dy = 0f
        for (i in 0 until 5) { sx += src[i].x; sy += src[i].y; dx += TEMPLATE[2 * i]; dy += TEMPLATE[2 * i + 1] }
        sx /= 5; sy /= 5; dx /= 5; dy /= 5
        var a = 0f; var b = 0f; var v = 0f
        for (i in 0 until 5) {
            val px = src[i].x - sx; val py = src[i].y - sy
            val qx = TEMPLATE[2 * i] - dx; val qy = TEMPLATE[2 * i + 1] - dy
            a += px * qx + py * qy; b += px * qy - py * qx; v += px * px + py * py
        }
        if (v < 1e-3f) return null
        a /= v; b /= v
        val tx = dx - (a * sx - b * sy); val ty = dy - (b * sx + a * sy)
        return Matrix().apply { setValues(floatArrayOf(a, -b, tx, b, a, ty, 0f, 0f, 1f)) }
    }

    private fun normalize(v: FloatArray): FloatArray { var s = 0f; for (x in v) s += x * x; val k = 1f / sqrt(max(s, 1e-12f)); return FloatArray(v.size) { v[it] * k } }
    private fun cos(a: FloatArray?, b: FloatArray?): Float? { if (a == null || b == null) return null; var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s }
    private fun meanEmb(l: List<FloatArray>): FloatArray? { if (l.isEmpty()) return null; val m = FloatArray(l[0].size); for (e in l) for (i in m.indices) m[i] += e[i]; return normalize(m) }

    /** mouth opening relative to face height (changes while talking) */
    private fun mouth(f: Face): Float {
        val l = f.getLandmark(FaceLandmark.MOUTH_LEFT)?.position; val r = f.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
        val b = f.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
        if (l == null || r == null || b == null) return Float.NaN
        return hypot(b.x - (l.x + r.x) / 2, b.y - (l.y + r.y) / 2) / max(1, f.boundingBox.height())
    }

    /* ---------- fast frame reading ---------- */

    /**
     * Decodes [from, to) once, front to back, with the hardware decoder (no seeking per frame), and
     * hands every sample (at from + i·dt) to [onFrame] as an upright ARGB picture about [long] px wide.
     * Returns false if this phone's decoder can't give YUV frames (caller then uses the slow way).
     */
    private fun decodeRange(
        ctx: Context, uri: Uri, from: Double, to: Double, dt: Double, firstIdx: Int, lastIdx: Int, long: Int,
        cancelled: () -> Boolean, onFrame: (i: Int, px: IntArray, w: Int, h: Int) -> Unit,
    ): Boolean {
        val ex = android.media.MediaExtractor()
        var codec: android.media.MediaCodec? = null
        try {
            MediaUtil.setSource(ex, ctx, uri)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return false
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val rot = if (fmt.containsKey(android.media.MediaFormat.KEY_ROTATION)) fmt.getInteger(android.media.MediaFormat.KEY_ROTATION) else 0
            val mime = fmt.getString(android.media.MediaFormat.KEY_MIME)!!
            fmt.setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val c = android.media.MediaCodec.createDecoderByType(mime); codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            val startT = from + firstIdx * dt
            ex.seekTo((startT * 1e6).toLong().coerceAtLeast(0), android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val info = android.media.MediaCodec.BufferInfo()
            var next = firstIdx
            var inDone = false
            var px: IntArray? = null
            while (next <= lastIdx && !cancelled()) {
                if (!inDone) {
                    val ii = c.dequeueInputBuffer(5_000)
                    if (ii >= 0) {
                        val buf = c.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { c.queueInputBuffer(ii, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { c.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 5_000)
                if (oi >= 0) {
                    val t = info.presentationTimeUs / 1e6
                    // this frame is the one for sample `next` (or later): use it for every sample it covers
                    if (info.size > 0 && t + 0.02 >= from + next * dt) {
                        val img = c.getOutputImage(oi)
                        if (img != null) {
                            val w0 = img.cropRect.width(); val h0 = img.cropRect.height()
                            val uw = if (rot % 180 == 0) w0 else h0; val uh = if (rot % 180 == 0) h0 else w0
                            val k = min(1f, long.toFloat() / max(uw, uh))
                            val ow = max(1, (uw * k).toInt()); val oh = max(1, (uh * k).toInt())
                            val arr = px?.takeIf { it.size == ow * oh } ?: IntArray(ow * oh).also { px = it }
                            yuvToArgb(img, rot, ow, oh, arr)
                            img.close()
                            while (next <= lastIdx && t + 0.02 >= from + next * dt) { onFrame(next, arr, ow, oh); next++ }
                        } else return false
                    }
                    c.releaseOutputBuffer(oi, false)
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return true
        } catch (t: Throwable) {
            CrashLog.warn("face decode", t)
            return false
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; runCatching { ex.release() }
        }
    }

    /** YUV_420_888 → upright, scaled ARGB (nearest pixel; plenty for face detection) */
    private fun yuvToArgb(img: android.media.Image, rot: Int, ow: Int, oh: Int, out: IntArray) {
        val cr = img.cropRect
        val W = cr.width(); val H = cr.height()
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yb = yP.buffer; val ub = uP.buffer; val vb = vP.buffer
        val y = ByteArray(yb.remaining()).also { yb.get(it) }
        val u = ByteArray(ub.remaining()).also { ub.get(it) }
        val v = ByteArray(vb.remaining()).also { vb.get(it) }
        val yr = yP.rowStride; val ys = yP.pixelStride
        val ur = uP.rowStride; val us = uP.pixelStride; val vr = vP.rowStride; val vs = vP.pixelStride
        val uw = if (rot % 180 == 0) W else H; val uh = if (rot % 180 == 0) H else W
        var o = 0
        for (oy in 0 until oh) {
            val dy = oy * uh / oh
            for (ox in 0 until ow) {
                val dx = ox * uw / ow
                // upright (dx, dy) → coded picture (cx, cy)
                val cx: Int; val cy: Int
                when (rot) {
                    90 -> { cx = dy; cy = H - 1 - dx }
                    180 -> { cx = W - 1 - dx; cy = H - 1 - dy }
                    270 -> { cx = W - 1 - dy; cy = dx }
                    else -> { cx = dx; cy = dy }
                }
                val px = cr.left + cx; val py = cr.top + cy
                val Y = (y[py * yr + px * ys].toInt() and 255) - 16
                val U = (u[(py shr 1) * ur + (px shr 1) * us].toInt() and 255) - 128
                val V = (v[(py shr 1) * vr + (px shr 1) * vs].toInt() and 255) - 128
                val yy = 1192 * max(0, Y)
                val r = ((yy + 1634 * V) shr 10).coerceIn(0, 255)
                val g = ((yy - 833 * V - 400 * U) shr 10).coerceIn(0, 255)
                val b = ((yy + 2066 * U) shr 10).coerceIn(0, 255)
                out[o++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /* ---------- scan ---------- */

    suspend fun scan(
        ctx: Context, uri: Uri, from: Double, to: Double, dispW: Int, dispH: Int,
        progress: (Float) -> Unit,
    ): FaceScan = coroutineScope {
        val span = max(0.5, to - from)
        // 5 samples a second (enough to see mouths move); very long spans are thinned
        val dt = max(0.2, span / 900.0)
        val n = (span / dt).toInt() + 1
        val dets = arrayOfNulls<List<Det>>(n)
        val done = AtomicInteger(0)
        val sess = sface(ctx)
        // accurate detector once a second, the fast one in between (tracking fills any gap)
        val accurate = FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL).setMinFaceSize(0.04f).build()
        val fast = FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL).setMinFaceSize(0.04f).build()
        val everyAcc = max(1, (1.0 / dt).toInt())
        val cpus = Runtime.getRuntime().availableProcessors()

        // decoded frames wait here for a free detector (small queue: memory stays flat)
        val queue = kotlinx.coroutines.channels.Channel<Triple<Int, Bitmap, Boolean>>(capacity = 4)

        fun analyse(i: Int, bmp: Bitmap, detAcc: com.google.mlkit.vision.face.FaceDetector, detFast: com.google.mlkit.vision.face.FaceDetector) {
            val det = if (i % everyAcc == 0) detAcc else detFast
            val faces = runCatching { Tasks.await(det.process(InputImage.fromBitmap(bmp, 0))) }.getOrNull().orEmpty()
            val W = bmp.width.toFloat(); val H = bmp.height.toFloat()
            dets[i] = faces.map { f ->
                val b = f.boundingBox
                Det(b.exactCenterX() / W, b.exactCenterY() / H, b.width() / W, b.height() / H, mouth(f),
                    // a face fingerprint every 3rd sample is enough to keep identities straight
                    if (i % 3 == 0) sess?.let { embed(it, bmp, f) } else null,
                    if (i % 10 == 0 || i < 3) thumb(bmp, b) else null)
            }
            progress(done.incrementAndGet().toFloat() / n)
        }

        // detectors: one per worker (ML Kit clients are not shared between threads)
        val nDet = max(2, min(4, cpus - 2))
        val detJobs = (0 until nDet).map {
            async(Dispatchers.Default) {
                val a = FaceDetection.getClient(accurate); val f = FaceDetection.getClient(fast)
                try { for ((i, bmp, _) in queue) { try { analyse(i, bmp, a, f) } finally { bmp.recycle() } } }
                finally { runCatching { a.close() }; runCatching { f.close() } }
            }
        }
        // readers: 2 hardware decoders, each reading one half front to back
        val halves = if (n >= 20) listOf(0 to n / 2 - 1, n / 2 to n - 1) else listOf(0 to n - 1)
        val fastOk = halves.map { (a, b) ->
            async(Dispatchers.IO) {
                decodeRange(ctx, uri, from, to, dt, a, b, 1280, { !isActive }) { i, px, w, h ->
                    val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
                    // wait for a free detector, but give up at once if the scan was cancelled
                    while (true) {
                        val r = queue.trySend(Triple(i, bmp, true))
                        if (r.isSuccess) break
                        if (r.isClosed || !isActive) { bmp.recycle(); break }
                        Thread.sleep(4)
                    }
                }
            }
        }.awaitAll().all { it }

        if (!fastOk) {
            // this phone's decoder can't hand out YUV frames: fill whatever is missing the slow way
            CrashLog.mark("face scan: slow frame path")
            val missing = (0 until n).filter { dets[it] == null }
            val r = MediaMetadataRetriever()
            try {
                MediaUtil.setSource(r, ctx, uri)
                val long = 1280
                val short = (long.toLong() * min(dispW, dispH) / max(dispW, dispH).coerceAtLeast(1)).toInt().coerceAtLeast(1)
                for (i in missing) {
                    ensureActive()
                    val us = ((from + i * dt) * 1_000_000).toLong()
                    val bmp = runCatching {
                        if (dispW >= dispH) r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, long, short)
                        else r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, short, long)
                    }.getOrNull() ?: continue
                    queue.send(Triple(i, bmp, false))
                }
            } finally { runCatching { r.release() } }
        }
        queue.close()
        detJobs.awaitAll()

        val persons = people(dets, n)
        val keep = persons.mapNotNull { it.thumb }.toSet()
        dets.forEach { l -> l?.forEach { d -> d.thumb?.let { if (it !in keep) it.recycle() } } }
        FaceScan(uri, from, dt, n, persons)
    }

    /** link faces frame to frame (position + face fingerprint), then join tracks of the same face into people */
    private fun people(dets: Array<List<Det>?>, n: Int): List<Person> {
        val tracks = ArrayList<Track>()
        for (i in 0 until n) {
            val ds = dets[i] ?: continue
            val free = tracks.filter { i - it.li <= 8 }
            val pairs = ArrayList<Triple<Float, Det, Track>>()
            for (d in ds) for (t in free) {
                val dist = hypot(d.x - t.lx, (d.y - t.ly) * 0.6f)
                if (dist > max(0.15f, 1.5f * max(d.w, t.lw))) continue
                val c = cos(d.emb, t.le)
                if (c != null && c < 0.15f) continue // clearly a different face
                pairs.add(Triple(dist + (if (c != null) (1f - c) * 0.25f else 0.1f), d, t))
            }
            pairs.sortBy { it.first }
            val usedD = HashSet<Det>(); val usedT = HashSet<Track>()
            for ((_, d, t) in pairs) {
                if (d in usedD || t in usedT) continue
                usedD.add(d); usedT.add(t)
                t.pts[i] = d; t.lx = d.x; t.ly = d.y; t.lw = d.w; t.li = i
                if (d.emb != null) t.le = t.le?.let { e -> normalize(FloatArray(e.size) { e[it] * 0.7f + d.emb[it] * 0.3f }) } ?: d.emb
            }
            for (d in ds) if (d !in usedD) tracks.add(Track(d.x, d.y, d.w, i, d.emb).also { it.pts[i] = d })
        }

        // join tracks into people: same face fingerprint (or, without one, same place), never two faces in one frame
        class Group(val tracks: MutableList<Track>) {
            val keys = HashSet<Int>(); var emb: FloatArray? = null; var cx = 0f; var cy = 0f
            fun refresh() {
                keys.clear(); tracks.forEach { keys.addAll(it.pts.keys) }
                emb = meanEmb(tracks.flatMap { t -> t.pts.values.mapNotNull { it.emb } })
                val all = tracks.flatMap { it.pts.values }; cx = all.map { it.x }.average().toFloat(); cy = all.map { it.y }.average().toFloat()
            }
        }
        val groups = ArrayList<Group>()
        for (t in tracks.sortedByDescending { it.pts.size }) {
            if (t.pts.size < 2) continue
            val te = t.mean()
            val tx = t.pts.values.map { it.x }.average().toFloat(); val ty = t.pts.values.map { it.y }.average().toFloat()
            var best: Group? = null; var bestScore = 0f
            for (g in groups) {
                if (t.pts.keys.count { it in g.keys } > 2) continue
                val c = cos(te, g.emb)
                val score = if (c != null) (if (c >= 0.36f) c else -1f) // SFace: about 0.36+ = same person
                else (if (hypot(tx - g.cx, (ty - g.cy) * 0.6f) < 0.16f) 0.3f else -1f)
                if (score > bestScore) { bestScore = score; best = g }
            }
            if (best != null) { best.tracks.add(t); best.refresh() } else groups.add(Group(mutableListOf(t)).also { it.refresh() })
        }

        val minCover = max(3, n / 12)
        val kept = groups.map { it to it.tracks.sumOf { t -> t.pts.size } }.filter { it.second >= minCover }
            .sortedByDescending { it.second }.take(4).sortedBy { it.first.cx }
        return kept.mapIndexed { idx, (g, cnt) ->
            val xs = FloatArray(n) { Float.NaN }; val ys = FloatArray(n) { Float.NaN }; val ms = FloatArray(n) { Float.NaN }
            var best: Det? = null; var sz = 0f
            for (t in g.tracks) for ((i, d) in t.pts) {
                xs[i] = d.x; ys[i] = d.y; ms[i] = d.mouth; sz += d.h
                if (d.thumb != null && (best == null || d.w > best.w)) best = d
            }
            val label = when {
                kept.size == 2 -> if (idx == 0) "Left person" else "Right person"
                kept.size == 3 -> listOf("Left person", "Middle person", "Right person")[idx]
                else -> "Person ${idx + 1}"
            }
            Person(idx + 1, label, cnt.toFloat() / n, xs, ys, ms, sz / cnt, best?.thumb)
        }
    }

    /**
     * Who is talking at each sample: during speech, the person whose mouth moves most (compared with
     * how much their mouth usually moves). Switches only after the new speaker leads for ~1 s and
     * every shot lasts at least ~2 s, so the cuts feel edited, not twitchy.
     */
    fun speakers(scan: FaceScan, speech: List<Pair<Double, Double>>): IntArray {
        val n = scan.n; val ps = scan.persons
        if (ps.isEmpty() || n == 0) return IntArray(n)
        if (ps.size == 1) return IntArray(n) { ps[0].id }
        val win = max(1, (0.6 / scan.dt).toInt())
        val act = ps.map { p ->
            val raw = FloatArray(n) { i -> if (i == 0 || p.mouth[i].isNaN() || p.mouth[i - 1].isNaN()) 0f else abs(p.mouth[i] - p.mouth[i - 1]) }
            val sm = FloatArray(n) { i -> var s = 0f; var c = 0; for (k in max(0, i - win)..min(n - 1, i + win)) { s += raw[k]; c++ }; s / c }
            val med = sm.filter { it > 0f }.sorted().let { if (it.isEmpty()) 1f else it[it.size / 2] }.coerceAtLeast(1e-4f)
            FloatArray(n) { sm[it] / med }
        }
        val talking = BooleanArray(n) { i ->
            if (speech.isEmpty()) true
            else { val t = scan.t0 + i * scan.dt; speech.any { (a, b) -> t >= a - 0.15 && t <= b + 0.15 } }
        }
        // raw winner per sample (-1 = nobody clearly)
        val raw = IntArray(n) { i ->
            if (!talking[i]) -1 else {
                var bi = -1; var bv = 0f; var second = 0f
                ps.indices.forEach { k -> if (!ps[k].x[i].isNaN()) { val v = act[k][i]; if (v > bv) { second = bv; bv = v; bi = k } else if (v > second) second = v } }
                if (bi >= 0 && bv > 0.6f && bv > second * 1.15f) bi else -1
            }
        }
        // hysteresis: a new speaker must lead for ~1 s
        val need = max(2, (1.0 / scan.dt).toInt())
        val out = IntArray(n)
        var cur = raw.firstOrNull { it >= 0 } ?: ps.indices.maxByOrNull { ps[it].coverage }!!
        var cand = -1; var run = 0
        for (i in 0 until n) {
            val r = raw[i]
            if (r >= 0 && r != cur) { if (r == cand) run++ else { cand = r; run = 1 }; if (run >= need) { cur = r; cand = -1; run = 0 } }
            else if (r == cur) { cand = -1; run = 0 }
            out[i] = cur
        }
        // shots shorter than ~2 s go to the speaker before them
        val minShot = max(3, (2.0 / scan.dt).toInt())
        var i = 0
        while (i < n) {
            var j = i; while (j < n && out[j] == out[i]) j++
            if (j - i < minShot && i > 0) for (k in i until j) out[k] = out[i - 1]
            i = j
        }
        return IntArray(n) { ps[out[it]].id }
    }

    private fun thumb(src: Bitmap, b: android.graphics.Rect): Bitmap? = runCatching {
        val s = (max(b.width(), b.height()) * 1.7f).toInt()
        val l = (b.centerX() - s / 2).coerceIn(0, max(0, src.width - 1)); val t = (b.centerY() - s / 2).coerceIn(0, max(0, src.height - 1))
        val w = min(s, src.width - l); val h = min(s, src.height - t)
        val c = Bitmap.createBitmap(src, l, t, max(1, w), max(1, h))
        val out = Bitmap.createScaledBitmap(c, 160, (160f * h / max(1, w)).toInt().coerceAtLeast(1), true)
        // never hand back the frame itself: it is recycled right after analysis
        val safe = if (out === src) out.copy(Bitmap.Config.ARGB_8888, false) else out
        if (c !== src && c !== safe) c.recycle()
        safe
    }.getOrNull()

    /**
     * Face positions → camera path: fill the gaps, drop jitter (median), hold still while the face
     * stays near the centre (dead zone), then ease (zero-lag smoothing both ways).
     */
    fun camera(x: FloatArray, y: FloatArray, smooth: String): Pair<FloatArray, FloatArray> {
        val (dz, a) = when (smooth) { "fast" -> 0.015f to 0.5f; "smooth" -> 0.05f to 0.12f; else -> 0.03f to 0.22f }
        return path(x, dz, a) to path(y, dz * 1.5f, a * 0.7f)
    }

    private fun path(src: FloatArray, dz: Float, a: Float): FloatArray {
        val n = src.size
        if (n == 0) return src
        val v = src.copyOf()
        val first = v.indexOfFirst { !it.isNaN() }
        if (first < 0) return FloatArray(n) { 0.5f }
        for (i in 0 until first) v[i] = v[first]
        for (i in first + 1 until n) if (v[i].isNaN()) v[i] = v[i - 1]
        val m = FloatArray(n) { i -> (max(0, i - 2)..min(n - 1, i + 2)).map { v[it] }.sorted().let { it[it.size / 2] } }
        val h = FloatArray(n); h[0] = m[0]
        for (i in 1 until n) { val d = m[i] - h[i - 1]; h[i] = if (abs(d) > dz) m[i] - dz * kotlin.math.sign(d) else h[i - 1] }
        val f = FloatArray(n); f[0] = h[0]
        for (i in 1 until n) f[i] = f[i - 1] + a * (h[i] - f[i - 1])
        for (i in n - 2 downTo 0) f[i] = f[i + 1] + a * (f[i] - f[i + 1])
        return f
    }
}
