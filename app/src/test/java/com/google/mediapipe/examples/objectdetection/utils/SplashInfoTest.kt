package com.google.mediapipe.examples.objectdetection.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class SplashInfoTest {
    @Test fun line1() = assertEquals("Clavo versi 1.3.0-ta+abc1234", SplashInfo.line1("1.3.0-ta+abc1234"))

    @Test fun line2() =
        assertEquals("commit abc1234-dirty - debug - build 2026-11-22 10:00", SplashInfo.line2("abc1234-dirty", "debug", "2026-11-22 10:00"))

    @Test fun line3ListsModels() =
        assertEquals(
            "Model (2): Cengkeh v2, Mangga v2",
            SplashInfo.line3(listOf("efficientdet_lite2_mangga_v2.tflite", "efficientdet_lite2_cengkeh_V2.tflite", "readme.txt"))
        )

    @Test fun line3Empty() = assertEquals("Model (0): -", SplashInfo.line3(emptyList()))

    @Test fun footerExact() = assertEquals("made by @absarz software", SplashInfo.FOOTER)
}
