package com.google.mediapipe.examples.objectdetection.utils

import android.graphics.Color
import java.util.Locale

/**
 * Picks the bounding-box colour from the class NAME (read from the model metadata), never from
 * a fixed class index, so it keeps working if a model orders its classes differently.
 */
object ClassColors {
    private val GREEN = Color.parseColor("#4CAF50")      // mentah (unripe)
    private val ORANGE = Color.parseColor("#FF9800")     // matang (ripe)
    private val DARK_BROWN = Color.parseColor("#7B2D1B") // busuk (rotten)

    /** Class name from metadata, or "kelas <index>" when the model carries no label. */
    fun labelFor(categoryName: String?, categoryIndex: Int): String =
        if (categoryName.isNullOrBlank()) "kelas $categoryIndex" else categoryName

    /** Case-insensitive "contains" match; unknown names get [defaultColor]. */
    fun colorFor(label: String, defaultColor: Int): Int {
        val name = label.lowercase(Locale.ROOT)
        return when {
            name.contains("mentah") -> GREEN
            name.contains("matang") -> ORANGE
            name.contains("busuk") -> DARK_BROWN
            else -> defaultColor
        }
    }
}
