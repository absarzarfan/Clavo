package com.google.mediapipe.examples.objectdetection

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.tasks.vision.core.RunningMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Golden-file regression for the Cengkeh model on the fixed test image.
 *
 * Golden file: androidTest/assets/cengkeh_golden.txt, one line per detection:
 *   class|score(3 decimals)|left,top,right,bottom
 * An instrumented test cannot write into the source tree. When the golden is absent (first run)
 * it is written to the app's external files dir and the path plus the adb command are logged
 * (tag ClavoTest); copy it into app/src/androidTest/assets/ and later runs compare against it.
 */
@RunWith(AndroidJUnit4::class)
class CengkehRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext = instrumentation.targetContext
    private val testContext = instrumentation.context

    private fun currentResult(): List<String> {
        val bitmap = testContext.assets.open("test_image.jpg").use { BitmapFactory.decodeStream(it) }
        val helper = ObjectDetectorHelper(
            threshold = 0.3f,
            maxResults = 10,
            modelPath = "models/$MODEL",
            runningMode = RunningMode.IMAGE,
            context = appContext
        )
        try {
            val bundle = helper.detectImage(bitmap)
            assertNotNull("inference returned null", bundle)
            return bundle!!.results.first().detections()
                .map { d ->
                    val c = d.categories().first()
                    val b = d.boundingBox()
                    String.format(
                        Locale.US, "%s|%.3f|%d,%d,%d,%d", c.categoryName(), c.score(),
                        Math.round(b.left), Math.round(b.top), Math.round(b.right), Math.round(b.bottom)
                    )
                }
                .sorted()
        } finally {
            helper.release()
        }
    }

    @Test
    fun cengkehResultMatchesGolden() {
        val actual = currentResult()
        Log.i(TAG, "cengkeh result (${actual.size}): $actual")

        val hasGolden = (testContext.assets.list("") ?: emptyArray()).contains(GOLDEN)
        if (!hasGolden) {
            val out = File(appContext.getExternalFilesDir(null), GOLDEN)
            out.writeText(actual.joinToString("\n") + "\n")
            Log.w(
                TAG,
                "GOLDEN CREATED (first run): ${out.absolutePath}\n" +
                    "adb pull ${out.absolutePath} app/src/androidTest/assets/$GOLDEN"
            )
            return
        }

        val golden = testContext.assets.open(GOLDEN).bufferedReader().readLines().filter { it.isNotBlank() }.sorted()
        assertEquals("number of detections", golden.size, actual.size)
        golden.zip(actual).forEachIndexed { i, (g, a) ->
            val gp = g.split("|")
            val ap = a.split("|")
            assertEquals("detection $i class", gp[0], ap[0])
            assertTrue(
                "detection $i score golden=${gp[1]} actual=${ap[1]}",
                abs(gp[1].toFloat() - ap[1].toFloat()) <= SCORE_TOL
            )
            val gb = gp[2].split(",").map { it.toInt() }
            val ab = ap[2].split(",").map { it.toInt() }
            gb.zip(ab).forEach { (x, y) ->
                assertTrue("detection $i box golden=${gp[2]} actual=${ap[2]}", abs(x - y) <= BOX_TOL_PX)
            }
        }
    }

    companion object {
        private const val TAG = "ClavoTest"
        private const val MODEL = "efficientdet_lite2_cengkeh_V2.tflite"
        private const val GOLDEN = "cengkeh_golden.txt"
        private const val SCORE_TOL = 0.005f // tolerate float jitter across CPUs
        private const val BOX_TOL_PX = 2
    }
}
