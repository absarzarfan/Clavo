/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.mediapipe.examples.objectdetection

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.mediapipe.examples.objectdetection.utils.ClassColors
import com.google.mediapipe.examples.objectdetection.utils.FrameMapping
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import kotlin.math.max
import kotlin.math.min

class OverlayViewCamera(context: Context?, attrs: AttributeSet?) :
    View(context, attrs) {

    private var results: ObjectDetectorResult? = null
    private var boxPaint = Paint()
    private var defaultBoxColor = 0
    private var textBackgroundPaint = Paint()
    private var textPaint = Paint()
    private var scaleFactor: Float = 1f
    private var bounds = Rect()
    private var outputWidth = 0
    private var outputHeight = 0
    private var outputRotate = 0
    private var manualRotation = 0
    private var rotatedWidth = 0
    private var rotatedHeight = 0
    private var runningMode: RunningMode = RunningMode.IMAGE
    private var previewWidth = 0
    private var previewHeight = 0
    private var previewLeft = 0f
    private var previewTop = 0f


    init {
        initPaints()
    }

    fun clear() {
        results = null
        textPaint.reset()
        textBackgroundPaint.reset()
        boxPaint.reset()
        invalidate()
        initPaints()
    }

    fun setRunningMode(runningMode: RunningMode) {
        this.runningMode = runningMode
    }

    private fun initPaints() {
        textBackgroundPaint.color = Color.BLACK
        textBackgroundPaint.style = Paint.Style.FILL
        textBackgroundPaint.textSize = 50f

        textPaint.color = Color.WHITE
        textPaint.style = Paint.Style.FILL
        textPaint.textSize = 50f

        defaultBoxColor = ContextCompat.getColor(context!!, R.color.mp_primary)
        boxPaint.color = defaultBoxColor
        boxPaint.strokeWidth = 8F
        boxPaint.style = Paint.Style.STROKE
    }

    override fun draw(canvas: Canvas) {
        try { drawImpl(canvas) } finally { com.google.mediapipe.examples.objectdetection.utils.LatencyRecorder.overlayDrawn("Camera") } // TEMP-ClavoLat
    }

    private fun drawImpl(canvas: Canvas) {
        super.draw(canvas)
        val geoOn = com.google.mediapipe.examples.objectdetection.utils.GeoLog.let { it.enabled && it.boxRequested } && results?.detections()?.isNotEmpty() == true // TEMP-ClavoGeo
        if (geoOn) com.google.mediapipe.examples.objectdetection.utils.GeoLog.boxRequested = false // TEMP-ClavoGeo
        var geoTop = geoOn // TEMP-ClavoGeo
        results?.detections()?.map {
            val boxRect = RectF(
                it.boundingBox().left,
                it.boundingBox().top,
                it.boundingBox().right,
                it.boundingBox().bottom
            )
            val geoThis = geoTop // TEMP-ClavoGeo
            geoTop = false // TEMP-ClavoGeo
            if (geoThis) com.google.mediapipe.examples.objectdetection.utils.GeoLog.box("1/5 detektor (koordinat input ${outputWidth}x${outputHeight}, belum R)", boxRect) // TEMP-ClavoGeo
            val matrix = Matrix()
            matrix.postTranslate(-outputWidth / 2f, -outputHeight / 2f)

            // Rotate box.
            matrix.postRotate(outputRotate.toFloat())

            // If the outputRotate is 90 or 270 degrees, the translation is
            // applied after the rotation. This is because a 90 or 270 degree rotation
            // flips the image vertically or horizontally, respectively.
            if (outputRotate == 90 || outputRotate == 270) {
                matrix.postTranslate(outputHeight / 2f, outputWidth / 2f)
            } else {
                matrix.postTranslate(outputWidth / 2f, outputHeight / 2f)
            }
            matrix.mapRect(boxRect)
            if (geoThis) com.google.mediapipe.examples.objectdetection.utils.GeoLog.box("2/5 setelah matriks R (ruang ${rotatedWidth}x${rotatedHeight})", boxRect) // TEMP-ClavoGeo
            if (manualRotation != 0) {
                // The detector saw the picture rotated by manualRotation; the preview container is
                // rotated the same way, so draw in the un-rotated picture's coordinates.
                val (dw, dh) = FrameMapping.rotatedSize(rotatedWidth, rotatedHeight, -manualRotation)
                val b = FrameMapping.unrotateBox(
                    FrameMapping.Box(boxRect.left, boxRect.top, boxRect.right, boxRect.bottom),
                    dw, dh, manualRotation
                )
                boxRect.set(b.left, b.top, b.right, b.bottom)
            }
            if (geoThis) com.google.mediapipe.examples.objectdetection.utils.GeoLog.box("3/5 setelah unrotateBox (M=$manualRotation${if (manualRotation == 0) ", dilewati" else ""})", boxRect) // TEMP-ClavoGeo
            boxRect
        }?.forEachIndexed { index, floats ->

            val top = floats.top * scaleFactor
            val bottom = floats.bottom * scaleFactor
            val left = floats.left * scaleFactor
            val right = floats.right * scaleFactor

            // Draw bounding box around detected objects
//            val drawableRect = RectF(left, top, right, bottom)
            val drawableRect = RectF(
                previewLeft + left,
                previewTop + top,
                previewLeft + right,
                previewTop + bottom
            )

            if (geoOn && index == 0) { // TEMP-ClavoGeo
                com.google.mediapipe.examples.objectdetection.utils.GeoLog.box("4/5 koordinat overlay (x scaleFactor $scaleFactor + offset $previewLeft,$previewTop)", drawableRect) // TEMP-ClavoGeo
                com.google.mediapipe.examples.objectdetection.utils.GeoLog.box("5/5 layar akhir (setelah transform container)", com.google.mediapipe.examples.objectdetection.utils.GeoLog.toScreen(this, drawableRect)) // TEMP-ClavoGeo
            } // TEMP-ClavoGeo
            // Label and box colour come from the class NAME (model metadata), not the index.
            val category = results?.detections()!![index].categories()[0]
            val label = ClassColors.labelFor(category.categoryName(), category.index())
            boxPaint.color = ClassColors.colorFor(label, defaultBoxColor)
            canvas.drawRect(drawableRect, boxPaint)

            // Create text to display alongside detected objects
            val drawableText =
                label + " " + String.format(
                    "%.2f",
                    category.score()
                )

            // Draw rect behind display text
            textBackgroundPaint.getTextBounds(
                drawableText,
                0,
                drawableText.length,
                bounds
            )
            val textWidth = bounds.width()
            val textHeight = bounds.height()
            canvas.drawRect(
                left,
                top,
                left + textWidth + BOUNDING_RECT_TEXT_PADDING,
                top + textHeight + BOUNDING_RECT_TEXT_PADDING,
                textBackgroundPaint
            )

            // Draw text for detected object
            canvas.drawText(
                drawableText,
                left,
                top + bounds.height(),
                textPaint
            )
        }
    }

    fun setResults(
        detectionResults: ObjectDetectorResult,
        outputHeight: Int,
        outputWidth: Int,
        imageRotation: Int,
        manualRotation: Int = 0
    ) {
        results = detectionResults
        this.outputWidth = outputWidth
        this.outputHeight = outputHeight
        this.outputRotate = imageRotation
        this.manualRotation = FrameMapping.normalizeRotation(manualRotation)

        // Calculates the new width and height of an image after it has been rotated.
        // If `imageRotation` is 0 or 180, the new width and height are the same
        // as the original width and height.
        // If `imageRotation` is 90 or 270, the new width and height are swapped.
        val rotatedWidthHeight = when (imageRotation) {
            0, 180 -> Pair(outputWidth, outputHeight)
            90, 270 -> Pair(outputHeight, outputWidth)
            else -> return
        }
        rotatedWidth = rotatedWidthHeight.first
        rotatedHeight = rotatedWidthHeight.second

        // With a manual rotation the preview shows the picture un-rotated (its container is
        // rotated instead), so the scale is computed on the un-rotated size.
        val shown = FrameMapping.rotatedSize(rotatedWidth, rotatedHeight, -this.manualRotation)

        // Images, videos are displayed in FIT_START mode.
        // Camera live streams is displayed in FILL_START mode. So we need to scale
        // up the bounding box to match with the size that the images/videos/live streams being
        // displayed.
        scaleFactor = when (runningMode) {
            RunningMode.IMAGE,
            RunningMode.VIDEO -> {
                min(
                    width * 1f / shown.first,
                    height * 1f / shown.second
                )
            }
            RunningMode.LIVE_STREAM -> {
                max(
                    width * 1f / shown.first,
                    height * 1f / shown.second
                )
            }
        }
        invalidate()
    }

    companion object {
        private const val BOUNDING_RECT_TEXT_PADDING = 8
    }

    /** TEMP-ClavoGeo: overlay state for the diagnosis log. */
    fun geoState(): String = "overlay state: outputWxH=${outputWidth}x${outputHeight} R=$outputRotate M=$manualRotation " + // TEMP-ClavoGeo
        "rotatedWxH=${rotatedWidth}x${rotatedHeight} scaleFactor=$scaleFactor previewLeft/Top=$previewLeft/$previewTop " + // TEMP-ClavoGeo
        "viewWxH=${width}x${height} runningMode=$runningMode" // TEMP-ClavoGeo

    fun setPreviewLayout(left: Int, top: Int, width: Int, height: Int) {
        previewLeft = left.toFloat()
        previewTop = top.toFloat()
        previewWidth = width
        previewHeight = height
    }

}