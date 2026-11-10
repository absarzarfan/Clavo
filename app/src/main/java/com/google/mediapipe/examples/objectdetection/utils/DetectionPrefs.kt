package com.google.mediapipe.examples.objectdetection.utils

import android.content.Context
import android.util.Log

/** Score-threshold slider maths (pure): 0.05..0.90 in steps of 0.01 on a 0..85 SeekBar. */
object ThresholdScale {
    const val MIN = 0.05f
    const val MAX = 0.90f
    const val DEFAULT = 0.5f
    const val STEPS = 85 // SeekBar.max

    fun clamp(value: Float): Float = value.coerceIn(MIN, MAX)

    fun toProgress(threshold: Float): Int = Math.round((clamp(threshold) - MIN) * 100f).coerceIn(0, STEPS)

    fun fromProgress(progress: Int): Float = Math.round((MIN + progress.coerceIn(0, STEPS) / 100f) * 100f) / 100f
}

/** User settings kept in SharedPreferences (same file as the selected model). */
object DetectionPrefs {
    private const val TAG = "DetectionPrefs"
    private const val PREFS_NAME = "clavo_prefs"
    private const val KEY_WEBCAM_ROTATION = "webcam_rotation"

    const val MODE_CAMERA = "camera"
    const val MODE_WEBCAM = "webcam"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getThreshold(context: Context, mode: String): Float = try {
        ThresholdScale.clamp(prefs(context).getFloat("threshold_$mode", ThresholdScale.DEFAULT))
    } catch (e: Exception) {
        ThresholdScale.DEFAULT
    }

    fun setThreshold(context: Context, mode: String, value: Float) {
        try {
            prefs(context).edit().putFloat("threshold_$mode", ThresholdScale.clamp(value)).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot save threshold", e)
        }
    }

    /** Manual webcam rotation: 0, 90, 180 or 270. Default 0. */
    fun getWebcamRotation(context: Context): Int = try {
        val r = prefs(context).getInt(KEY_WEBCAM_ROTATION, 0)
        if (r == 90 || r == 180 || r == 270) r else 0
    } catch (e: Exception) {
        0
    }

    fun setWebcamRotation(context: Context, degrees: Int) {
        try {
            prefs(context).edit().putInt(KEY_WEBCAM_ROTATION, FrameMapping.normalizeRotation(degrees)).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot save rotation", e)
        }
    }
}
