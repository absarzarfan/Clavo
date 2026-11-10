package com.google.mediapipe.examples.objectdetection.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ThresholdScaleTest {
    @Test fun endsOfTheRange() {
        assertEquals(0, ThresholdScale.toProgress(0.05f))
        assertEquals(ThresholdScale.STEPS, ThresholdScale.toProgress(0.90f))
        assertEquals(0.05f, ThresholdScale.fromProgress(0), 0.0001f)
        assertEquals(0.90f, ThresholdScale.fromProgress(ThresholdScale.STEPS), 0.0001f)
    }

    @Test fun defaultIsHalf() {
        assertEquals(0.5f, ThresholdScale.DEFAULT, 0f)
        assertEquals(45, ThresholdScale.toProgress(ThresholdScale.DEFAULT))
        assertEquals(0.5f, ThresholdScale.fromProgress(45), 0.0001f)
    }

    @Test fun valuesOutsideTheRangeAreClamped() {
        assertEquals(0.05f, ThresholdScale.clamp(0f), 0f)
        assertEquals(0.90f, ThresholdScale.clamp(1f), 0f)
        assertEquals(0, ThresholdScale.toProgress(-1f))
        assertEquals(ThresholdScale.STEPS, ThresholdScale.toProgress(5f))
    }

    @Test fun everyProgressStepRoundTrips() {
        for (p in 0..ThresholdScale.STEPS) {
            assertEquals(p, ThresholdScale.toProgress(ThresholdScale.fromProgress(p)))
        }
    }
}
