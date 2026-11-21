package com.google.mediapipe.examples.objectdetection.utils

/**
 * A screen (Camera or Webcam tab) whose manual rotation is driven by the "Putar" button in the
 * header. The screen keeps its own rotation logic and storage; the header only asks for the next
 * step and shows the stored value.
 */
interface RotatableScreen {
    /** Advances 0 -> 90 -> 180 -> 270 -> 0, applies and stores it, and returns the new value. */
    fun rotateNext(): Int
}
