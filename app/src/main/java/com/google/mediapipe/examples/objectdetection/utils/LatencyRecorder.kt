package com.google.mediapipe.examples.objectdetection.utils // TEMP-ClavoLat

import android.content.Context // TEMP-ClavoLat
import android.os.SystemClock // TEMP-ClavoLat
import android.util.Log // TEMP-ClavoLat
import com.google.mediapipe.examples.objectdetection.BuildConfig // TEMP-ClavoLat
import com.google.mediapipe.examples.objectdetection.ObjectDetectorHelper // TEMP-ClavoLat
import java.io.BufferedWriter // TEMP-ClavoLat
import java.io.File // TEMP-ClavoLat
import java.text.SimpleDateFormat // TEMP-ClavoLat
import java.util.ArrayDeque // TEMP-ClavoLat
import java.util.Date // TEMP-ClavoLat
import java.util.Locale // TEMP-ClavoLat
import java.util.concurrent.ConcurrentHashMap // TEMP-ClavoLat
import java.util.concurrent.ConcurrentLinkedQueue // TEMP-ClavoLat
import java.util.concurrent.Executors // TEMP-ClavoLat
import java.util.concurrent.TimeUnit // TEMP-ClavoLat

/**
 * TEMP-ClavoLat: thesis (Tugas Akhir) measurement instrumentation. Debug builds only
 * (BuildConfig.DEBUG); every public function is a no-op in release. No permission, no network:
 * the CSV goes to getExternalFilesDir(null). To remove: delete this file and every line that
 * contains "TEMP-ClavoLat" (hooks in ObjectDetectorHelper, both fragments, both overlays,
 * MainActivity).
 *
 * Per-frame timeline (SystemClock.elapsedRealtimeNanos):
 *   t_frame          frameReceived(): frame handed to the app (Camera: analyzer callback entry;
 *                    Webcam: right before TextureView.getBitmap())
 *   t_infer_mulai    inferStart(): immediately before detector.detectAsync()
 *   t_infer_selesai  inferEnd(): first line of the MediaPipe result callback
 *   t_overlay        overlayDrawn(): end of OverlayView.draw() for the result's frame
 * infer_ms = selesai - mulai ; e2e_ms = t_overlay - t_frame.
 */
object LatencyRecorder { // TEMP-ClavoLat
    private const val TAG = "ClavoLat" // TEMP-ClavoLat
    private const val TAG_ACT = "ClavoAct" // TEMP-ClavoLat
    private const val SUMMARY_MS = 2000L // TEMP-ClavoLat
    private const val FLUSH_S = 5L // TEMP-ClavoLat
    private const val WINDOW = 100 // TEMP-ClavoLat
    private const val HEADER =
        "sesi,waktu_ms,mode,model,lebar,tinggi,infer_ms,e2e_ms,jumlah_deteksi,skor_tertinggi,tipe,keterangan" // TEMP-ClavoLat

    const val MODE_CAMERA = "Camera" // TEMP-ClavoLat
    const val MODE_WEBCAM = "Webcam" // TEMP-ClavoLat
    const val MODE_APP = "App" // TEMP-ClavoLat

    private class Pending(val mode: String, val tFrame: Long) { // TEMP-ClavoLat
        @Volatile var tStart = 0L
        @Volatile var tEnd = 0L
    }

    private class Row( // TEMP-ClavoLat
        val mode: String, val model: String, val w: Int, val h: Int, val tFrame: Long,
        val inferMs: Double, val detections: Int, val maxScore: Float
    )

    private class Stats { // TEMP-ClavoLat
        val infer = ArrayDeque<Double>()
        val e2e = ArrayDeque<Double>()
        var lastLogMs = 0L
    }

    private val pending = ConcurrentHashMap<Long, Pending>() // TEMP-ClavoLat
    private val awaitingDraw = ConcurrentHashMap<String, Row>() // TEMP-ClavoLat
    private val currentFrame = ThreadLocal<Pair<String, Long>?>() // TEMP-ClavoLat
    private val models = ConcurrentHashMap<String, String>() // TEMP-ClavoLat
    private val lastState = ConcurrentHashMap<String, String>() // TEMP-ClavoLat
    private val stats = HashMap<String, Stats>() // TEMP-ClavoLat
    private val queue = ConcurrentLinkedQueue<String>() // TEMP-ClavoLat

    @Volatile private var dir: File? = null // TEMP-ClavoLat
    @Volatile private var started = false // TEMP-ClavoLat
    private var session = "" // TEMP-ClavoLat
    private var writer: BufferedWriter? = null // TEMP-ClavoLat (writer thread only)

    private val enabled: Boolean get() = BuildConfig.DEBUG // TEMP-ClavoLat

    /** Call once from MainActivity.onCreate. Creates the writer thread (debug only). */
    @Synchronized
    fun init(context: Context) { // TEMP-ClavoLat
        if (!enabled || started) return
        started = true
        dir = context.applicationContext.getExternalFilesDir(null)
        session = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ClavoLatWriter").apply { isDaemon = true }
        }.scheduleWithFixedDelay({ drain() }, FLUSH_S, FLUSH_S, TimeUnit.SECONDS)
    }

    // ---------------------------------------------------------------- per-frame timeline

    /** t_frame. Must run on the same thread that later calls ObjectDetectorHelper.detectAsync. */
    fun frameReceived(mode: String) { // TEMP-ClavoLat
        if (!enabled) return
        currentFrame.set(Pair(mode, SystemClock.elapsedRealtimeNanos()))
    }

    /** t_infer_mulai, keyed by the frame timestamp passed to detectAsync (ms). */
    fun inferStart(key: Long) { // TEMP-ClavoLat
        if (!enabled) return
        val f = currentFrame.get() ?: return
        currentFrame.set(null)
        if (pending.size > 50) pending.clear() // frames whose result never came back
        pending[key] = Pending(f.first, f.second).also { it.tStart = SystemClock.elapsedRealtimeNanos() }
    }

    /** t_infer_selesai: first line of the MediaPipe result callback. */
    fun inferEnd(key: Long) { // TEMP-ClavoLat
        if (!enabled) return
        pending[key]?.tEnd = SystemClock.elapsedRealtimeNanos()
    }

    /** Called from the fragment's onResults: pairs the result with its frame, awaits the draw. */
    fun resultDelivered(mode: String, bundle: ObjectDetectorHelper.ResultBundle) { // TEMP-ClavoLat
        if (!enabled) return
        val result = bundle.results.firstOrNull() ?: return
        val p = pending.remove(result.timestampMs()) ?: return
        if (p.tEnd == 0L || p.tStart == 0L) return
        val cats = result.detections().mapNotNull { it.categories().firstOrNull() }
        awaitingDraw[mode] = Row(
            mode, models[mode] ?: "?", bundle.inputImageWidth, bundle.inputImageHeight, p.tFrame,
            (p.tEnd - p.tStart) / 1e6, cats.size, cats.maxOfOrNull { it.score() } ?: 0f
        )
    }

    /** t_overlay: call as the last statement of OverlayView.draw(). */
    fun overlayDrawn(mode: String) { // TEMP-ClavoLat
        if (!enabled) return
        val row = awaitingDraw.remove(mode) ?: return
        val e2eMs = (SystemClock.elapsedRealtimeNanos() - row.tFrame) / 1e6
        queue.add(
            "$session,${System.currentTimeMillis()},${row.mode},${csv(row.model)},${row.w},${row.h}," +
                "${"%.2f".format(Locale.US, row.inferMs)},${"%.2f".format(Locale.US, e2eMs)}," +
                "${row.detections},${"%.3f".format(Locale.US, row.maxScore)},frame,"
        )
        summarize(row.mode, row.inferMs, e2eMs)
    }

    // ---------------------------------------------------------------- activity events

    fun setModel(mode: String, modelName: String) { // TEMP-ClavoLat
        if (!enabled) return
        models[mode] = modelName
        activity(mode, "model_dipilih", modelName)
    }

    fun activity(mode: String, event: String, detail: String = "") { // TEMP-ClavoLat
        if (!enabled) return
        val model = models[mode] ?: models.values.firstOrNull() ?: "?"
        val now = System.currentTimeMillis()
        Log.d(TAG_ACT, "t=$now mode=$mode model=$model event=$event${if (detail.isEmpty()) "" else "($detail)"}")
        queue.add("$session,$now,$mode,${csv(model)},,,,,,,aktivitas,${csv(if (detail.isEmpty()) event else "$event($detail)")}")
    }

    /** Logs only when [value] differs from the last one reported under [key]. */
    fun activityOnChange( // TEMP-ClavoLat
        mode: String, key: String, value: String, event: String, detail: String = "",
        skipInitial: Boolean = false // true: the first observation only records the state, no event
    ) {
        if (!enabled) return
        val previous = lastState.put("$mode/$key", value)
        if (previous == value || (previous == null && skipInitial)) return
        activity(mode, event, detail)
    }

    // ---------------------------------------------------------------- internals

    private fun csv(s: String) = s.replace(',', ';').replace('\n', ' ').replace('\r', ' ').replace('"', '\'') // TEMP-ClavoLat

    @Synchronized
    private fun summarize(mode: String, inferMs: Double, e2eMs: Double) { // TEMP-ClavoLat
        val s = stats.getOrPut(mode) { Stats() }
        s.infer.addLast(inferMs); if (s.infer.size > WINDOW) s.infer.removeFirst()
        s.e2e.addLast(e2eMs); if (s.e2e.size > WINDOW) s.e2e.removeFirst()
        val now = SystemClock.uptimeMillis()
        if (now - s.lastLogMs < SUMMARY_MS) return
        s.lastLogMs = now
        Log.d(TAG, "[$mode] n=${s.infer.size} infer ${fmt(s.infer)} | e2e ${fmt(s.e2e)}")
    }

    private fun fmt(values: Collection<Double>): String { // TEMP-ClavoLat
        val sorted = values.sorted()
        val n = sorted.size
        val avg = sorted.average()
        val median = if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
        val p95 = sorted[minOf(n - 1, Math.ceil(0.95 * n).toInt() - 1).coerceAtLeast(0)]
        return "avg=%.1f med=%.1f p95=%.1f ms".format(Locale.US, avg, median, p95)
    }

    /** Runs on the writer thread every 5 s. The file is created once the first model is known. */
    private fun drain() { // TEMP-ClavoLat
        try {
            if (writer == null) {
                val d = dir ?: return
                val modelTag = models.values.firstOrNull() ?: return // wait for the first model
                val tag = modelTag.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')
                val f = File(d, "latency_${tag}_$session.csv")
                writer = f.bufferedWriter().also { it.write(HEADER); it.newLine() }
                Log.i(TAG, "CSV: ${f.absolutePath}")
            }
            val w = writer ?: return
            while (true) {
                val line = queue.poll() ?: break
                w.write(line); w.newLine()
            }
            w.flush()
        } catch (e: Exception) {
            Log.w(TAG, "CSV write failed: ${e.message}")
        }
    }
} // TEMP-ClavoLat
