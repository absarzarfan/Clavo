package com.google.mediapipe.examples.objectdetection.utils

import android.content.Context
import android.util.Log
import java.io.IOException
import java.util.Locale

/**
 * Offline model catalogue. Every model is a .tflite file bundled in assets/models/.
 * No network access is involved.
 */
object ModelRepository {
    private const val TAG = "ModelRepository"
    private const val MODEL_ASSET_DIR = "models"
    private const val MODEL_EXTENSION = ".tflite"
    private const val PREFS_NAME = "clavo_prefs"
    private const val KEY_SELECTED_MODEL = "selected_model_file"

    data class ModelInfo(
        /** File name inside assets/models/, e.g. "efficientdet_lite2_mangga_v2.tflite". */
        val fileName: String,
        /** Human readable name, e.g. "Mangga (Lite2 v2)". */
        val displayName: String
    ) {
        /** Path to hand to MediaPipe (relative to the assets root). */
        val assetPath: String get() = "$MODEL_ASSET_DIR/$fileName"
    }

    // efficientdet_<variant>_<name>[_<version>].tflite
    private val NAME_PATTERN =
        Regex("^efficientdet[_-](lite\\d+)[_-](.+?)(?:[_-](v\\d+))?$", RegexOption.IGNORE_CASE)

    /** Lists bundled models, sorted by display name. Empty if the folder is missing or empty. */
    fun listModels(context: Context): List<ModelInfo> {
        val files = try {
            context.assets.list(MODEL_ASSET_DIR)?.toList() ?: emptyList()
        } catch (e: IOException) {
            Log.e(TAG, "Cannot list assets/$MODEL_ASSET_DIR", e)
            emptyList()
        }
        return files
            .filter { it.endsWith(MODEL_EXTENSION, ignoreCase = true) }
            .map { ModelInfo(it, displayNameFor(it)) }
            .sortedBy { it.displayName.lowercase(Locale.ROOT) }
    }

    /**
     * The model to start with: the last one the user picked (shared by Camera and Webcam),
     * or the first available model if nothing was saved or the saved one is no longer bundled.
     */
    fun resolveInitialModel(context: Context, available: List<ModelInfo>): ModelInfo? {
        val saved = try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SELECTED_MODEL, null)
        } catch (e: Exception) {
            null
        }
        val picked = pickInitialModel(saved, available)
        if (saved != null && picked?.fileName != saved) {
            Log.w(TAG, "Saved model '$saved' is no longer bundled; using ${picked?.fileName}")
        }
        return picked
    }

    /**
     * Pure part of [resolveInitialModel]: the saved model if it is still bundled, otherwise the
     * first available model (e.g. a saved "efficientdet_lite2_mangga_v1.tflite" that was moved
     * to models_archive/), or null if nothing is bundled.
     */
    fun pickInitialModel(savedFileName: String?, available: List<ModelInfo>): ModelInfo? =
        available.firstOrNull { it.fileName == savedFileName } ?: available.firstOrNull()

    fun saveSelectedModel(context: Context, fileName: String) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_SELECTED_MODEL, fileName).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot save selected model", e)
        }
    }

    /** "efficientdet_lite2_mangga_v2.tflite" -> "Mangga (Lite2 v2)". */
    fun displayNameFor(fileName: String): String {
        val base = fileName.removeSuffix(MODEL_EXTENSION).removeSuffix(MODEL_EXTENSION.uppercase())
        val match = NAME_PATTERN.matchEntire(base)
        if (match != null) {
            val (variant, name, version) = match.destructured
            val variantLabel = variant.replaceFirstChar { it.titlecase(Locale.ROOT) }
            val detail = listOf(variantLabel, version.lowercase(Locale.ROOT))
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            return "${titleCase(name)} ($detail)"
        }
        return titleCase(base)
    }

    private fun titleCase(raw: String): String =
        raw.replace('_', ' ').replace('-', ' ').trim()
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase(Locale.ROOT) } }
}
