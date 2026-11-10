package com.google.mediapipe.examples.objectdetection.utils

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** Plain JVM. android.graphics.Color comes from the stand-in in src/test/java/android/graphics. */
class ClassColorsTest {
    private val green = Color.parseColor("#4CAF50")
    private val orange = Color.parseColor("#FF9800")
    private val brown = Color.parseColor("#7B2D1B")
    private val default = Color.parseColor("#123456")

    private fun color(label: String) = ClassColors.colorFor(label, default)

    @Test fun mentahIsGreen() = assertEquals(green, color("mentah"))
    @Test fun matangIsOrange() = assertEquals(orange, color("matang"))
    @Test fun busukIsDarkBrown() = assertEquals(brown, color("busuk"))
    @Test fun unknownNameUsesDefault() = assertEquals(default, color("daun"))
    @Test fun emptyUsesDefault() = assertEquals(default, color(""))
    @Test fun upperCase() {
        assertEquals(green, color("MENTAH"))
        assertEquals(orange, color("MATANG"))
        assertEquals(brown, color("BUSUK"))
    }
    @Test fun mixedCase() = assertEquals(orange, color("MaTaNg"))
    @Test fun prefixedWithCommodity() {
        assertEquals(orange, color("Cengkeh Matang"))
        assertEquals(green, color("Cengkeh Mentah"))
        assertEquals(brown, color("Cengkeh Busuk"))
    }

    @Test fun labelForUsesMetadataName() = assertEquals("matang", ClassColors.labelFor("matang", 2))
    @Test fun labelForNullOrBlankFallsBackToIndex() {
        assertEquals("kelas 3", ClassColors.labelFor(null, 3))
        assertEquals("kelas 0", ClassColors.labelFor("  ", 0))
    }
}
