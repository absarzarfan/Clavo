package com.google.mediapipe.examples.objectdetection.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure JVM: the file-name -> display-name rule and the initial-model choice of ModelRepository. */
class ModelRepositoryTest {

    @Test
    fun mangoV2() = assertEquals("Mangga (Lite2 v2)", ModelRepository.displayNameFor("efficientdet_lite2_mangga_v2.tflite"))

    @Test
    fun uppercaseVersionIsLowercased() =
        assertEquals("Cengkeh (Lite2 v2)", ModelRepository.displayNameFor("efficientdet_lite2_cengkeh_V2.tflite"))

    @Test
    fun withoutVersion() = assertEquals("Cengkeh (Lite0)", ModelRepository.displayNameFor("efficientdet_lite0_cengkeh.tflite"))

    @Test
    fun multiWordCommodity() =
        assertEquals("Nanas Madu (Lite2 v3)", ModelRepository.displayNameFor("efficientdet_lite2_nanas_madu_v3.tflite"))

    @Test
    fun hyphenSeparators() =
        assertEquals("Mangga (Lite2 v2)", ModelRepository.displayNameFor("efficientdet-lite2-mangga-v2.tflite"))

    @Test
    fun nonMatchingNameFallsBackToTitleCasedFileName() {
        assertEquals("Model Baru", ModelRepository.displayNameFor("model_baru.tflite"))
        assertEquals("Custom", ModelRepository.displayNameFor("custom.tflite"))
    }

    @Test
    fun modelInfoAssetPath() {
        val info = ModelRepository.ModelInfo("efficientdet_lite2_mangga_v2.tflite", "Mangga (Lite2 v2)")
        assertEquals("models/efficientdet_lite2_mangga_v2.tflite", info.assetPath)
    }

    // Bundled models as listModels() would return them (sorted by display name).
    private val bundled = listOf(
        "efficientdet_lite2_cengkeh_V2.tflite",
        "efficientdet_lite2_mangga_v2.tflite"
    ).map { ModelRepository.ModelInfo(it, ModelRepository.displayNameFor(it)) }

    @Test
    fun savedModelStillBundledIsKept() =
        assertEquals(bundled[1], ModelRepository.pickInitialModel("efficientdet_lite2_mangga_v2.tflite", bundled))

    @Test
    fun savedModelNoLongerBundledFallsBackToFirstAvailable() =
        // v1 was moved to models_archive/; a stale preference must not crash or select nothing.
        assertEquals(bundled[0], ModelRepository.pickInitialModel("efficientdet_lite2_mangga_v1.tflite", bundled))

    @Test
    fun nothingSavedUsesFirstAvailable() =
        assertEquals(bundled[0], ModelRepository.pickInitialModel(null, bundled))

    @Test
    fun noBundledModelsGivesNull() =
        assertNull(ModelRepository.pickInitialModel("efficientdet_lite2_mangga_v1.tflite", emptyList()))
}
