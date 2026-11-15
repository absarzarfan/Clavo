package com.google.mediapipe.examples.objectdetection.utils

/**
 * Pure geometry (no Android dependency) of the Camera-mode preview container.
 *
 * The container (preview + overlay) is rotated clockwise by the manual rotation. Only its top
 * part is actually visible: the bottom sheet covers the rest. So the container is rotated about
 * the centre of the VISIBLE area and, for 90/270, shrunk so the rotated visible area still fits
 * in the visible area. [clipRight]/[clipBottom] confine drawing to the visible area.
 *
 * Coordinates are container-local pixels (origin = container top-left, y down).
 */
object ContainerTransform {
    data class Result(
        val rotation: Float,
        val scale: Float,
        val pivotX: Float,
        val pivotY: Float,
        val translationX: Float,
        val translationY: Float,
        val clipLeft: Int,
        val clipTop: Int,
        val clipRight: Int,
        val clipBottom: Int
    )

    /** [height] is the visible height to use; [fallback] is true when it had to fall back to the whole container. */
    data class Visible(val height: Int, val fallback: Boolean)

    /**
     * Visible height = bottom-sheet top minus container top (both in the same parent's
     * coordinates). The whole container height is used, and [Visible.fallback] set, when the sheet
     * is not laid out (height <= 0) or its top is not a usable value (<= container top, or below
     * the container). A fallback while the sheet is on screen means the caller read the sheet
     * mid-layout, before the behaviour moved it into place.
     */
    fun resolveVisibleHeight(containerH: Int, containerTop: Int, sheetTop: Int, sheetHeight: Int): Visible {
        if (sheetHeight <= 0) return Visible(containerH, true)
        val v = sheetTop - containerTop
        return if (v in 1..containerH) Visible(v, false) else Visible(containerH, true)
    }

    /**
     * @param containerW container width in px
     * @param containerH container height in px
     * @param visibleH height of the visible area (bottom-sheet top minus container top); values
     *        outside 1..containerH fall back to the whole container
     * @param rotation manual clockwise rotation, a multiple of 90
     */
    fun compute(containerW: Int, containerH: Int, visibleH: Int, rotation: Int): Result {
        val rot = FrameMapping.normalizeRotation(rotation)
        if (containerW <= 0 || containerH <= 0) {
            return Result(rot.toFloat(), 1f, 0f, 0f, 0f, 0f, 0, 0, maxOf(containerW, 0), maxOf(containerH, 0))
        }
        val vh = if (visibleH in 1..containerH) visibleH else containerH
        val w = containerW.toFloat()
        val h = vh.toFloat()
        val scale = if (rot % 180 != 0) minOf(w / h, h / w) else 1f
        return Result(rot.toFloat(), scale, w / 2f, h / 2f, 0f, 0f, 0, 0, containerW, vh)
    }

    /**
     * Where a container-local point lands (in the container's parent, relative to the container's
     * un-transformed top-left): the same formula Android applies for rotation + scale about a
     * pivot, i.e. p' = pivot + R * S * (p - pivot) + translation.
     */
    fun mapPoint(t: Result, x: Float, y: Float): Pair<Float, Float> {
        val dx = (x - t.pivotX) * t.scale
        val dy = (y - t.pivotY) * t.scale
        val rad = Math.toRadians(t.rotation.toDouble())
        val c = Math.cos(rad).toFloat()
        val s = Math.sin(rad).toFloat()
        return Pair(t.pivotX + t.translationX + dx * c - dy * s, t.pivotY + t.translationY + dx * s + dy * c)
    }
}
