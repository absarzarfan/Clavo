package com.google.mediapipe.examples.objectdetection

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.tasks.vision.core.RunningMode
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/**
 * For every model in assets/models: it loads, its metadata carries class labels, and inference on
 * the fixed test image (androidTest/assets/test_image.jpg) finishes without error.
 */
@RunWith(AndroidJUnit4::class)
class ModelAssetsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext = instrumentation.targetContext // app assets: models/
    private val testContext = instrumentation.context // androidTest assets: test_image.jpg

    private fun modelFiles(): List<String> =
        (appContext.assets.list("models") ?: emptyArray()).filter { it.endsWith(".tflite") }.sorted()

    /** A .tflite with metadata is also a zip; the label map is one of its entries. */
    private fun metadataLabels(fileName: String): List<String> {
        val tmp = File(appContext.cacheDir, "meta_$fileName")
        appContext.assets.open("models/$fileName").use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        try {
            ZipFile(tmp).use { zip ->
                val entry = zip.entries().asSequence()
                    .firstOrNull { !it.isDirectory && it.name.contains("label", ignoreCase = true) }
                    ?: zip.entries().asSequence().firstOrNull { it.name.endsWith(".txt") }
                    ?: return emptyList()
                return zip.getInputStream(entry).bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
            }
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun everyBundledModelLoadsHasLabelsAndRunsInference() {
        val models = modelFiles()
        assertTrue("assets/models is empty", models.isNotEmpty())
        Log.i(TAG, "models=${models.size}")

        val bitmap = testContext.assets.open("test_image.jpg").use { BitmapFactory.decodeStream(it) }
        assertNotNull("test_image.jpg not decodable", bitmap)

        for (file in models) {
            val labels = metadataLabels(file)
            Log.i(TAG, "model=$file labels=$labels")
            assertTrue("$file: no labels in metadata", labels.isNotEmpty())

            val helper = ObjectDetectorHelper(
                threshold = 0.0f,
                maxResults = 10,
                modelPath = "models/$file",
                runningMode = RunningMode.IMAGE,
                context = appContext
            )
            try {
                assertTrue("$file: detector did not load", !helper.isClosed())
                val bundle = helper.detectImage(bitmap)
                assertNotNull("$file: inference returned null", bundle)
                val names = bundle!!.results.first().detections()
                    .flatMap { d -> d.categories().map { it.categoryName() } }.toSet()
                Log.i(TAG, "model=$file inference=${bundle.inferenceTime}ms detectedClasses=$names")
                assertTrue("$file: detected class $names not in metadata labels $labels", labels.containsAll(names))
            } finally {
                helper.release()
            }
        }
    }

    companion object {
        private const val TAG = "ClavoTest"
    }
}
