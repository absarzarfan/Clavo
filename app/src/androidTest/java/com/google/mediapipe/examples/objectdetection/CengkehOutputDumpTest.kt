package com.google.mediapipe.examples.objectdetection

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.tasks.vision.core.RunningMode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * Dumps the raw output of the Cengkeh model (efficientdet_lite2_cengkeh_V2.tflite) for every image
 * in <externalFilesDir>/uji_cengkeh, so the output of different APK variants (A, B1, B2) can be
 * compared offline with tools/bandingkan_cengkeh.ps1. The images are NOT in Git (HANDOFF.md); push
 * them with adb. See tools/README_evaluasi.md.
 *
 * Settings: threshold 0.3, maxResults 10, RunningMode.IMAGE, CPU delegate.
 * Output: <externalFilesDir>/cengkeh_out_<label>.txt, one block per image:
 *   FILE: <name>
 *   class|score(3 decimals)|left,top,right,bottom      (one line per detection, sorted)
 *   <blank line>
 * <label> comes from the runner argument "-e label <A|B1|B2>"; without it "tanpa-label".
 * The test FAILS when the folder is missing or holds no .jpg/.jpeg/.png.
 */
@RunWith(AndroidJUnit4::class)
class CengkehOutputDumpTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext = instrumentation.targetContext

    @Test
    fun dumpCengkehOutputForAllImages() {
        val label = (InstrumentationRegistry.getArguments().getString("label") ?: "")
            .replace(Regex("[^A-Za-z0-9_-]"), "")
            .ifEmpty { "tanpa-label" }

        val baseDir = appContext.getExternalFilesDir(null)
        assertTrue("getExternalFilesDir tidak tersedia", baseDir != null)
        val imageDir = File(baseDir, IMAGE_DIR)
        assertTrue(
            "Folder gambar uji tidak ada: ${imageDir.absolutePath}. " +
                "Isi dengan: adb push <folder gambar>/. ${imageDir.absolutePath}/",
            imageDir.isDirectory
        )
        val images = (imageDir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension.lowercase(Locale.US) in EXTENSIONS }
            .sortedBy { it.name }
        assertTrue(
            "Folder ${imageDir.absolutePath} kosong (tidak ada .jpg/.jpeg/.png)",
            images.isNotEmpty()
        )

        val helper = ObjectDetectorHelper(
            threshold = 0.3f,
            maxResults = 10,
            currentDelegate = ObjectDetectorHelper.DELEGATE_CPU,
            modelPath = "models/$MODEL",
            runningMode = RunningMode.IMAGE,
            context = appContext
        )
        val out = StringBuilder()
        val failures = mutableListOf<String>()
        var totalDetections = 0
        try {
            assertTrue("Model $MODEL gagal dimuat (ada di assets/models APK ini?)", !helper.isClosed())
            Log.i(TAG, "dump label=$label images=${images.size} dir=${imageDir.absolutePath}")
            for (file in images) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap == null) {
                    failures += "${file.name}: tidak bisa di-decode"
                    continue
                }
                try {
                    // Same thread that created the detector (DetectorQueue), like the app.
                    val bundle = DetectorQueue.call { helper.detectImage(bitmap) }
                    if (bundle == null) {
                        failures += "${file.name}: inferensi mengembalikan null"
                        continue
                    }
                    val lines = bundle.results.first().detections().map { d ->
                        val c = d.categories().first()
                        val b = d.boundingBox()
                        String.format(
                            Locale.US, "%s|%.3f|%d,%d,%d,%d", c.categoryName(), c.score(),
                            Math.round(b.left), Math.round(b.top), Math.round(b.right), Math.round(b.bottom)
                        )
                    }.sorted()
                    totalDetections += lines.size
                    out.append("FILE: ").append(file.name).append('\n')
                    lines.forEach { out.append(it).append('\n') }
                    out.append('\n')
                    Log.i(TAG, "dump ${file.name}: ${lines.size} deteksi ${lines}")
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            helper.release()
        }

        val outFile = File(baseDir, "cengkeh_out_$label.txt")
        outFile.writeText(out.toString())
        Log.i(
            TAG,
            "dump selesai: label=$label gambar=${images.size} gagal=${failures.size} " +
                "totalDeteksi=$totalDetections berkas=${outFile.absolutePath}\n" +
                "adb pull ${outFile.absolutePath}"
        )
        assertTrue("Gambar gagal diproses: $failures", failures.isEmpty())
    }

    companion object {
        private const val TAG = "ClavoTest"
        private const val MODEL = "efficientdet_lite2_cengkeh_V2.tflite"
        private const val IMAGE_DIR = "uji_cengkeh"
        private val EXTENSIONS = setOf("jpg", "jpeg", "png")
    }
}
