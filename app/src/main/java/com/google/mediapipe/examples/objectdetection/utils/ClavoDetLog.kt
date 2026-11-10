package com.google.mediapipe.examples.objectdetection.utils // TEMP-ClavoDet

import android.os.SystemClock // TEMP-ClavoDet
import android.util.Log // TEMP-ClavoDet
import com.google.mediapipe.examples.objectdetection.ObjectDetectorHelper // TEMP-ClavoDet

// TEMP-ClavoDet: diagnostic logging only. Delete this file and every line marked TEMP-ClavoDet.
object ClavoDetLog { // TEMP-ClavoDet
    private const val TAG = "ClavoDet" // TEMP-ClavoDet
    private const val INTERVAL_MS = 2000L // TEMP-ClavoDet

    private val lastLogMs = HashMap<String, Long>() // TEMP-ClavoDet
    private val frameInfo = HashMap<String, String>() // TEMP-ClavoDet

    // Remember the size/rotation of the bitmap that was last sent to the detector.
    @Synchronized
    fun frame(source: String, w: Int, h: Int, rotation: Int, note: String = "") { // TEMP-ClavoDet
        frameInfo[source] = "bitmap=${w}x$h rot=$rotation $note" // TEMP-ClavoDet
    } // TEMP-ClavoDet

    // Called from onResults; prints at most once every 2 s per source.
    @Synchronized
    fun result(source: String, bundle: ObjectDetectorHelper.ResultBundle, threshold: Float) { // TEMP-ClavoDet
        val now = SystemClock.uptimeMillis() // TEMP-ClavoDet
        if (now - (lastLogMs[source] ?: 0L) < INTERVAL_MS) return // TEMP-ClavoDet
        lastLogMs[source] = now // TEMP-ClavoDet
        val detections = bundle.results.firstOrNull()?.detections() ?: emptyList() // TEMP-ClavoDet
        val maxPerClass = detections
            .mapNotNull { it.categories().firstOrNull() }
            .groupBy { it.categoryName().ifEmpty { "#${it.index()}" } }
            .mapValues { e -> e.value.maxOf { it.score() } }
            .entries.joinToString { "${it.key}=${"%.2f".format(it.value)}" } // TEMP-ClavoDet
        Log.d(
            TAG,
            "[$source] ${frameInfo[source] ?: "frame=?"} mpInput=${bundle.inputImageWidth}x${bundle.inputImageHeight} " +
                "bundleRot=${bundle.inputImageRotation} inference=${bundle.inferenceTime}ms " +
                "detections=${detections.size} maxScore[$maxPerClass] scoreThreshold=$threshold" +
                (if (threshold <= 0.1f) " RAW(threshold<=0.1: scores of all detections above it, at most maxResults per frame)" else "")
        ) // TEMP-ClavoDet
    } // TEMP-ClavoDet
} // TEMP-ClavoDet
