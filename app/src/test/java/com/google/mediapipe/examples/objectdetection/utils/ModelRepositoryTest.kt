package com.google.mediapipe.examples.objectdetection.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure JVM: only the file-name -> display-name rule of ModelRepository. */
class ModelRepositoryTest {

    @Test
    fun mangoV2() = assertEquals("Mangga (Lite2 v2)", ModelRepository.displayNameFor("efficientdet_lite2_mangga_v2.tflite"))

    @Test
    fun mangoV1() = assertEquals("Mangga (Lite2 v1)", ModelRepository.displayNameFor("efficientdet_lite2_mangga_v1.tflite"))

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
        assertEquals("Mangga (Lite2 v1)", ModelRepository.displayNameFor("efficientdet-lite2-mangga-v1.tflite"))

    @Test
    fun nonMatchingNameFallsBackToTitleCasedFileName() {
        assertEquals("Model Baru", ModelRepository.displayNameFor("model_baru.tflite"))
        assertEquals("Custom", ModelRepository.displayNameFor("custom.tflite"))
    }

    @Test
    fun modelInfoAssetPath() {
        val info = ModelRepository.ModelInfo("efficientdet_lite2_mangga_v1.tflite", "Mangga (Lite2 v1)")
        assertEquals("models/efficientdet_lite2_mangga_v1.tflite", info.assetPath)
    }
}
