package com.google.mediapipe.examples.objectdetection

import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.tasks.vision.core.RunningMode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import kotlin.math.ceil

/**
 * Official model latency benchmark. For every .tflite in the APK's assets/models, one helper in
 * RunningMode.IMAGE with the same input image (first image of <externalFilesDir>/uji_cengkeh by
 * name, else androidTest/assets/test_image.jpg), threshold 0.3, maxResults 10:
 * 10 warm-up inferences, then 100 timed ones. CPU is mandatory (failure fails the test, after the
 * CSV is written); GPU is optional (failure is recorded as delegasi=GPU_GAGAL, test continues).
 *
 * Timing: System.nanoTime() around ObjectDetectorHelper.detectImage(), which wraps
 * ObjectDetector.detect() (the helper does not expose the detector; the extra cost is creating
 * the BitmapImageBuilder wrapper, no pixel copy). It runs on DetectorQueue, the thread that
 * created the detector, as in the app (the GPU delegate needs the same thread).
 *
 * Output: <externalFilesDir>/latensi_<label>.csv
 *   label,model,delegasi,n,rata2_ms,median_ms,p95_ms,min_ms,maks_ms,thread
 * thread is always "default": MediaPipe's thread count cannot be set. p95 = nearest rank.
 * <label> comes from "-e label <A|B1|B2>"; without it "tanpa-label".
 * The same CSV lines, device model, SDK, battery % and temperature (before/after) are also logged
 * with tag ClavoTest as a backup in case the file is lost.
 */
@RunWith(AndroidJUnit4::class)
class LatencyBenchmarkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext = instrumentation.targetContext
    private val testContext = instrumentation.context

    private class Battery(val percent: Int, val tempC: Double) {
        override fun toString() = String.format(Locale.US, "baterai=%d%% suhu=%.1fC", percent, tempC)
    }

    private fun battery(): Battery {
        val i: Intent? = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val temp = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        return Battery(pct, if (temp == Int.MIN_VALUE) Double.NaN else temp / 10.0)
    }

    private fun loadInput(): Pair<Bitmap, String> {
        val dir = File(appContext.getExternalFilesDir(null), "uji_cengkeh")
        val first = (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension.lowercase(Locale.US) in setOf("jpg", "jpeg", "png") }
            .minByOrNull { it.name }
        if (first != null) {
            BitmapFactory.decodeFile(first.absolutePath)?.let { return it to "uji_cengkeh/${first.name}" }
        }
        val bmp = testContext.assets.open("test_image.jpg").use { BitmapFactory.decodeStream(it) }
        return bmp to "test_image.jpg"
    }

    private fun f(v: Double) = String.format(Locale.US, "%.3f", v)

    @Test
    fun measureLatencyForEveryModel() {
        val label = (InstrumentationRegistry.getArguments().getString("label") ?: "")
            .replace(Regex("[^A-Za-z0-9_-]"), "")
            .ifEmpty { "tanpa-label" }
        val models = (appContext.assets.list("models") ?: emptyArray())
            .filter { it.endsWith(".tflite") }.sorted()
        assertTrue("assets/models kosong", models.isNotEmpty())
        val (bitmap, inputName) = loadInput()

        val before = battery()
        Log.i(
            TAG,
            "latensi mulai: label=$label perangkat=${Build.MANUFACTURER} ${Build.MODEL} " +
                "SDK_INT=${Build.VERSION.SDK_INT} input=$inputName ${bitmap.width}x${bitmap.height} " +
                "model=$models sebelum: $before"
        )

        val csv = mutableListOf("label,model,delegasi,n,rata2_ms,median_ms,p95_ms,min_ms,maks_ms,thread")
        val cpuFailures = mutableListOf<String>()

        for (model in models) {
            for (delegate in listOf(ObjectDetectorHelper.DELEGATE_CPU, ObjectDetectorHelper.DELEGATE_GPU)) {
                val isGpu = delegate == ObjectDetectorHelper.DELEGATE_GPU
                val name = if (isGpu) "GPU" else "CPU"
                var error: String? = null
                val helper = try {
                    ObjectDetectorHelper(
                        threshold = 0.3f,
                        maxResults = 10,
                        currentDelegate = delegate,
                        modelPath = "models/$model",
                        runningMode = RunningMode.IMAGE,
                        context = appContext,
                        objectDetectorListener = object : ObjectDetectorHelper.DetectorListener {
                            override fun onError(error2: String, errorCode: Int) {
                                error = error2
                            }

                            override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle) {}
                        }
                    )
                } catch (t: Throwable) {
                    error = t.toString()
                    null
                }

                val times = DoubleArray(MEASURED)
                var ok = helper != null && !helper.isClosed()
                if (ok) {
                    try {
                        repeat(WARMUP) { check(DetectorQueue.call { helper!!.detectImage(bitmap) } != null) { "hasil null" } }
                        for (i in 0 until MEASURED) {
                            times[i] = DetectorQueue.call {
                                val t0 = System.nanoTime()
                                val r = helper!!.detectImage(bitmap)
                                val t1 = System.nanoTime()
                                check(r != null) { "hasil null" }
                                (t1 - t0) / 1_000_000.0
                            }
                        }
                    } catch (t: Throwable) {
                        ok = false
                        error = t.toString()
                    }
                }
                try {
                    helper?.release()
                } catch (t: Throwable) {
                    Log.w(TAG, "release gagal: $t")
                }

                if (ok) {
                    val s = times.sorted()
                    val p95 = s[ceil(0.95 * s.size).toInt() - 1]
                    val mid = (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
                    csv += listOf(
                        label, model, name, s.size, f(s.average()), f(mid), f(p95), f(s.first()), f(s.last()), "default"
                    ).joinToString(",")
                } else if (isGpu) {
                    Log.w(TAG, "GPU_GAGAL model=$model: $error")
                    csv += listOf(label, model, "GPU_GAGAL", 0, "", "", "", "", "", "default").joinToString(",")
                } else {
                    cpuFailures += "$model: $error"
                    csv += listOf(label, model, "CPU_GAGAL", 0, "", "", "", "", "", "default").joinToString(",")
                }
                Log.i(TAG, "latensi ${csv.last()} (suhu/baterai kini: ${battery()})")
            }
        }
        bitmap.recycle()

        val after = battery()
        val outFile = File(appContext.getExternalFilesDir(null), "latensi_$label.csv")
        outFile.writeText(csv.joinToString("\n") + "\n")
        Log.i(
            TAG,
            "latensi selesai: label=$label sebelum: $before sesudah: $after berkas=${outFile.absolutePath}\n" +
                "adb pull ${outFile.absolutePath}"
        )
        csv.forEach { Log.i(TAG, "CSV $it") }
        assertTrue("CPU gagal: $cpuFailures", cpuFailures.isEmpty())
    }

    companion object {
        private const val TAG = "ClavoTest"
        private const val WARMUP = 10
        private const val MEASURED = 100
    }
}
