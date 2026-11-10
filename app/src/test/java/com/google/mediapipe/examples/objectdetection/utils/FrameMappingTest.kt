package com.google.mediapipe.examples.objectdetection.utils

import com.google.mediapipe.examples.objectdetection.utils.FrameMapping.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frame used throughout: 640 x 480 (landscape webcam). Model space is 448 x 448, so the centre
 * square crop (side 480 -> 448) scales by 480/448; the quarter box (0,0)-(224,224) is 240 px wide.
 */
class FrameMappingTest {
    private val delta = 0.01f

    private fun assertBox(expected: Box, actual: Box) {
        assertEquals("left", expected.left, actual.left, delta)
        assertEquals("top", expected.top, actual.top, delta)
        assertEquals("right", expected.right, actual.right, delta)
        assertEquals("bottom", expected.bottom, actual.bottom, delta)
    }

    private fun map(box: Box, w: Int = 640, h: Int = 480, rot: Int) =
        FrameMapping.modelBoxToFrame(box, w, h, rot, FrameMapping.MODEL_INPUT_SIZE)

    private val quarter = Box(0f, 0f, 224f, 224f)
    private val full = Box(0f, 0f, 448f, 448f)

    @Test fun modelInputSizeIs448() = assertEquals(448, FrameMapping.MODEL_INPUT_SIZE)

    // ---- centre crop ----
    @Test fun centerSquareLandscape() = assertEquals(FrameMapping.Square(80, 0, 480), FrameMapping.centerSquare(640, 480))
    @Test fun centerSquarePortrait() = assertEquals(FrameMapping.Square(0, 80, 480), FrameMapping.centerSquare(480, 640))
    @Test fun centerSquareAlreadySquare() = assertEquals(FrameMapping.Square(0, 0, 300), FrameMapping.centerSquare(300, 300))

    @Test fun noRotationFullModelBoxIsTheCroppedSquare() = assertBox(Box(80f, 0f, 560f, 480f), map(full, rot = 0))
    @Test fun noRotationQuarterBoxIsTopLeftOfCrop() = assertBox(Box(80f, 0f, 320f, 240f), map(quarter, rot = 0))
    @Test fun noRotationCentreOfModelIsCentreOfFrame() =
        assertBox(Box(320f, 240f, 320f, 240f), map(Box(224f, 224f, 224f, 224f), rot = 0))
    @Test fun portraitFrameCropsVerticallyAroundCentre() =
        assertBox(Box(0f, 80f, 480f, 560f), map(full, w = 480, h = 640, rot = 0))

    // ---- rotation: the model sees the frame rotated clockwise; boxes come back to the original frame ----
    @Test fun rotation90TopLeftOfModelIsBottomLeftOfFrame() = assertBox(Box(80f, 240f, 320f, 480f), map(quarter, rot = 90))
    @Test fun rotation180TopLeftOfModelIsBottomRightOfFrame() = assertBox(Box(320f, 240f, 560f, 480f), map(quarter, rot = 180))
    @Test fun rotation270TopLeftOfModelIsTopRightOfFrame() = assertBox(Box(320f, 0f, 560f, 240f), map(quarter, rot = 270))
    @Test fun rotation360IsSameAsZero() = assertBox(map(quarter, rot = 0), map(quarter, rot = 360))
    @Test fun negativeRotationNormalises() = assertBox(map(quarter, rot = 270), map(quarter, rot = -90))

    @Test fun fullBoxCoversSameRegionForEveryRotation() {
        // A 90/270 rotated 640x480 frame is 480x640; its centre square is still the centre 480x480 of the frame.
        for (rot in listOf(0, 90, 180, 270)) {
            assertBox(Box(80f, 0f, 560f, 480f), map(full, rot = rot))
        }
    }

    @Test fun centreStaysCentreForEveryRotation() {
        for (rot in listOf(0, 90, 180, 270)) {
            assertBox(Box(320f, 240f, 320f, 240f), map(Box(224f, 224f, 224f, 224f), rot = rot))
        }
    }

    @Test fun resultIsAlwaysNormalised() {
        for (rot in listOf(0, 90, 180, 270)) {
            val b = map(Box(100f, 50f, 300f, 400f), rot = rot)
            assertTrue(b.left <= b.right && b.top <= b.bottom)
        }
    }

    // ---- helpers ----
    @Test fun rotatedSizeSwapsOnlyFor90And270() {
        assertEquals(Pair(640, 480), FrameMapping.rotatedSize(640, 480, 0))
        assertEquals(Pair(480, 640), FrameMapping.rotatedSize(640, 480, 90))
        assertEquals(Pair(640, 480), FrameMapping.rotatedSize(640, 480, 180))
        assertEquals(Pair(480, 640), FrameMapping.rotatedSize(640, 480, 270))
    }

    @Test fun normalizeRotation() {
        assertEquals(0, FrameMapping.normalizeRotation(0))
        assertEquals(90, FrameMapping.normalizeRotation(450))
        assertEquals(270, FrameMapping.normalizeRotation(-90))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonRightAngleIsRejected() {
        FrameMapping.normalizeRotation(45)
    }
}
