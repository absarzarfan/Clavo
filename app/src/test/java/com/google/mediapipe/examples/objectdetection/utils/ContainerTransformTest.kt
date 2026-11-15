package com.google.mediapipe.examples.objectdetection.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Container 1080 x 1976, of which the top 1080 x 1365 is visible (the bottom sheet covers the rest). */
class ContainerTransformTest {
    private val cw = 1080
    private val ch = 1976
    private val vh = 1365
    private val eps = 0.01f

    private fun t(rot: Int) = ContainerTransform.compute(cw, ch, vh, rot)

    private fun corners() = listOf(
        Pair(0f, 0f), Pair(cw.toFloat(), 0f), Pair(cw.toFloat(), vh.toFloat()), Pair(0f, vh.toFloat())
    )

    @Test fun scaleIsOneForZeroAnd180AndFitsFor90And270() {
        assertEquals(1f, t(0).scale, 0f)
        assertEquals(1f, t(180).scale, 0f)
        assertEquals(1080f / 1365f, t(90).scale, 1e-5f)
        assertEquals(1080f / 1365f, t(270).scale, 1e-5f)
    }

    @Test fun pivotIsCentreOfVisibleAreaForEveryAngle() {
        for (rot in listOf(0, 90, 180, 270)) {
            val r = t(rot)
            assertEquals(540f, r.pivotX, eps)
            assertEquals(682.5f, r.pivotY, eps)
            assertEquals(0f, r.translationX, 0f)
            assertEquals(0f, r.translationY, 0f)
        }
    }

    @Test fun clipIsTheVisibleArea() {
        for (rot in listOf(0, 90, 180, 270)) {
            val r = t(rot)
            assertEquals(listOf(0, 0, 1080, 1365), listOf(r.clipLeft, r.clipTop, r.clipRight, r.clipBottom))
        }
    }

    @Test fun rotatedVisibleAreaStaysInsideVisibleAreaAndIsCentred() {
        for (rot in listOf(0, 90, 180, 270)) {
            val r = t(rot)
            val pts = corners().map { ContainerTransform.mapPoint(r, it.first, it.second) }
            val minX = pts.minOf { it.first }
            val maxX = pts.maxOf { it.first }
            val minY = pts.minOf { it.second }
            val maxY = pts.maxOf { it.second }
            assertTrue("rot $rot minX=$minX", minX >= -eps)
            assertTrue("rot $rot maxX=$maxX", maxX <= 1080f + eps)
            assertTrue("rot $rot minY=$minY", minY >= -eps)
            assertTrue("rot $rot maxY=$maxY", maxY <= 1365f + eps)
            assertEquals("rot $rot cx", 540f, (minX + maxX) / 2f, eps)
            assertEquals("rot $rot cy", 682.5f, (minY + maxY) / 2f, eps)
        }
    }

    @Test fun rotatedVisibleAreaSizeForEachAngle() {
        fun size(rot: Int): Pair<Float, Float> {
            val pts = corners().map { ContainerTransform.mapPoint(t(rot), it.first, it.second) }
            return Pair(pts.maxOf { it.first } - pts.minOf { it.first }, pts.maxOf { it.second } - pts.minOf { it.second })
        }
        assertEquals(1080f, size(0).first, eps); assertEquals(1365f, size(0).second, eps)
        assertEquals(1080f, size(180).first, eps); assertEquals(1365f, size(180).second, eps)
        // 90/270: 1365 x 1080 scaled by 1080/1365 -> 1080 x 854.1 (fills the width, no cropping)
        assertEquals(1080f, size(90).first, eps); assertEquals(1080f * 1080f / 1365f, size(90).second, eps)
        assertEquals(1080f, size(270).first, eps); assertEquals(1080f * 1080f / 1365f, size(270).second, eps)
    }

    @Test fun mapPointKnownValues() {
        val s = 1080f / 1365f
        // 0: identity
        assertPoint(100f, 200f, ContainerTransform.mapPoint(t(0), 100f, 200f))
        // 180 about (540, 682.5): (x, y) -> (1080 - x, 1365 - y)
        assertPoint(980f, 1165f, ContainerTransform.mapPoint(t(180), 100f, 200f))
        // 90 clockwise about the pivot with scale s: top-left -> top-right of the shrunk area
        assertPoint(1080f, 682.5f - 540f * s, ContainerTransform.mapPoint(t(90), 0f, 0f))
        // 270: top-left -> bottom-left of the shrunk area
        assertPoint(0f, 682.5f + 540f * s, ContainerTransform.mapPoint(t(270), 0f, 0f))
    }

    @Test fun overlayPointMapsLikeTheViewTransformForEveryAngle() {
        // Independent formula, written out per angle: p' = pivot + R90k * (s * (p - pivot)).
        val px = 540f
        val py = 682.5f
        val p = Pair(321f, 456f)
        for (rot in listOf(0, 90, 180, 270)) {
            val r = t(rot)
            val dx = (p.first - px) * r.scale
            val dy = (p.second - py) * r.scale
            val (rx, ry) = when (rot) {
                0 -> Pair(dx, dy)
                90 -> Pair(-dy, dx)
                180 -> Pair(-dx, -dy)
                else -> Pair(dy, -dx)
            }
            assertPoint(px + rx, py + ry, ContainerTransform.mapPoint(r, p.first, p.second))
        }
    }

    @Test fun invalidVisibleHeightFallsBackToWholeContainer() {
        for (bad in listOf(0, -5, 5000)) {
            val r = ContainerTransform.compute(cw, ch, bad, 0)
            assertEquals(988f, r.pivotY, eps)
            assertEquals(ch, r.clipBottom)
        }
    }

    @Test fun zeroSizedContainerIsHarmless() {
        val r = ContainerTransform.compute(0, 0, 0, 90)
        assertEquals(1f, r.scale, 0f)
    }

    @Test fun rotationIsNormalised() {
        assertEquals(270f, ContainerTransform.compute(cw, ch, vh, -90).rotation, 0f)
        assertEquals(90f, ContainerTransform.compute(cw, ch, vh, 450).rotation, 0f)
    }

    // ---- which visible height gets used (the wiring that went wrong in Build 2) ----
    @Test fun expandedSheetGivesTopOfSheetMinusContainerTop() {
        assertEquals(ContainerTransform.Visible(1365, false), ContainerTransform.resolveVisibleHeight(1976, 0, 1365, 611))
    }

    @Test fun collapsedSheetGivesLargerVisibleArea() {
        assertEquals(ContainerTransform.Visible(1838, false), ContainerTransform.resolveVisibleHeight(1976, 0, 1838, 138))
    }

    @Test fun containerTopIsSubtracted() {
        assertEquals(ContainerTransform.Visible(1300, false), ContainerTransform.resolveVisibleHeight(1976, 65, 1365, 611))
    }

    @Test fun sheetNotLaidOutFallsBackToContainerAndIsFlagged() {
        assertEquals(ContainerTransform.Visible(1976, true), ContainerTransform.resolveVisibleHeight(1976, 0, 0, 0))
    }

    @Test fun sheetReadMidLayoutAtRawTopZeroIsDetectedAsFallback() {
        // The Build 2 bug: a layout listener read sheet.top while it was still the raw layout
        // position (0) although the sheet was already 611 px tall. visibleH became the whole
        // container (pivot 988, scale 0.5466) and nothing noticed. It must now be flagged.
        val v = ContainerTransform.resolveVisibleHeight(1976, 0, 0, 611)
        assertTrue(v.fallback)
        assertEquals(1976, v.height)
        val wrong = ContainerTransform.compute(1080, 1976, v.height, 90)
        assertEquals(988f, wrong.pivotY, eps)
        assertEquals(1080f / 1976f, wrong.scale, 1e-5f)
    }

    @Test fun sheetTopBelowContainerIsFallback() {
        assertTrue(ContainerTransform.resolveVisibleHeight(1976, 0, 2500, 611).fallback)
    }

    @Test fun sheetTopExactlyAtContainerBottomIsNotFallback() {
        assertEquals(ContainerTransform.Visible(1976, false), ContainerTransform.resolveVisibleHeight(1976, 0, 1976, 138))
    }

    @Test fun resolvedVisibleHeightFeedsTheExpectedTransform() {
        val v = ContainerTransform.resolveVisibleHeight(1976, 0, 1365, 611)
        val r = ContainerTransform.compute(1080, 1976, v.height, 270)
        assertEquals(682.5f, r.pivotY, eps)
        assertEquals(1080f / 1365f, r.scale, 1e-5f)
        assertEquals(1365, r.clipBottom)
    }

    private fun assertPoint(ex: Float, ey: Float, actual: Pair<Float, Float>) {
        assertEquals("x", ex, actual.first, eps)
        assertEquals("y", ey, actual.second, eps)
    }
}
