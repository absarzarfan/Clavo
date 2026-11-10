package com.google.mediapipe.examples.objectdetection.utils

/**
 * Pure geometry (no Android dependency) for the webcam pipeline.
 *
 * Pipeline: source frame F (W x H, the un-rotated TextureView bitmap)
 *   -> rotate clockwise by R (0/90/180/270) = image D, exactly what the user sees
 *   -> centre square crop of D (side = min(Dw, Dh))
 *   -> scale to MODEL_INPUT_SIZE x MODEL_INPUT_SIZE = the bitmap given to the detector.
 * The detector reports boxes in that last space; [modelBoxToFrame] maps them back to F.
 */
object FrameMapping {
    /** Every model in assets/models takes a 448 x 448 input. */
    const val MODEL_INPUT_SIZE = 448

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    data class Square(val x: Int, val y: Int, val side: Int)

    /** Normalises any angle to 0, 90, 180 or 270 (multiples of 90 only). */
    fun normalizeRotation(degrees: Int): Int {
        val r = ((degrees % 360) + 360) % 360
        require(r % 90 == 0) { "rotation must be a multiple of 90, was $degrees" }
        return r
    }

    /** Size of D, the frame after rotating W x H by [rotation]. */
    fun rotatedSize(w: Int, h: Int, rotation: Int): Pair<Int, Int> =
        if (normalizeRotation(rotation) % 180 == 0) Pair(w, h) else Pair(h, w)

    /** Centre square of a w x h image. */
    fun centerSquare(w: Int, h: Int): Square {
        val side = minOf(w, h)
        return Square((w - side) / 2, (h - side) / 2, side)
    }

    /**
     * Maps a box from detector (model input) space to source-frame space.
     * [frameW] x [frameH] is the size of the un-rotated frame F; [rotation] is the clockwise
     * rotation that was applied to F before cropping.
     */
    fun modelBoxToFrame(
        box: Box, frameW: Int, frameH: Int, rotation: Int, modelSize: Int = MODEL_INPUT_SIZE
    ): Box {
        val rot = normalizeRotation(rotation)
        val (dw, dh) = rotatedSize(frameW, frameH, rot)
        val sq = centerSquare(dw, dh)
        val k = sq.side.toFloat() / modelSize

        // model space -> rotated image D
        val dl = sq.x + box.left * k
        val dt = sq.y + box.top * k
        val dr = sq.x + box.right * k
        val db = sq.y + box.bottom * k

        // rotated image D -> source frame F (inverse of the clockwise rotation)
        fun toFrame(x: Float, y: Float): Pair<Float, Float> = when (rot) {
            0 -> Pair(x, y)
            90 -> Pair(y, frameH - x)
            180 -> Pair(frameW - x, frameH - y)
            else -> Pair(frameW - y, x) // 270
        }
        val a = toFrame(dl, dt)
        val b = toFrame(dr, db)
        return Box(
            minOf(a.first, b.first), minOf(a.second, b.second),
            maxOf(a.first, b.first), maxOf(a.second, b.second)
        )
    }
}
