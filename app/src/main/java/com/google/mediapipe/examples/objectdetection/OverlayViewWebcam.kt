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
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult

/**
 * Draws webcam detections. It is a sibling of the webcam container (not a child), because
 * libausbc empties that container.
 *
 * Boxes arrive in detector space (MODEL_INPUT_SIZE square). [FrameMapping.modelBoxToFrame] maps
 * them to the un-rotated TextureView frame, and [frameToOverlay] (the TextureView's own transform,
 * including the manual rotation and fit scale, expressed in this view's coordinates) maps them to
 * the screen. No scale factor is stored, so there is nothing that can be left at 0.
 */
class OverlayViewWebcam(context: Context?, attrs: AttributeSet?) :
    View(context, attrs) {

    private var results: ObjectDetectorResult? = null
    private var boxPaint = Paint()
    private var defaultBoxColor = 0
    private var textBackgroundPaint = Paint()
    private var textPaint = Paint()
    private var bounds = Rect()

    private var frameWidth = 0
    private var frameHeight = 0
    private var rotation90 = 0
    private val frameToOverlay = Matrix()

    init {
        initPaints()
    }

    /** Clears the last results and redraws an empty overlay. */
    fun clear() {
        results = null
        textPaint.reset()
        textBackgroundPaint.reset()
        boxPaint.reset()
        invalidate()
        initPaints()
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
        try { drawImpl(canvas) } finally { com.google.mediapipe.examples.objectdetection.utils.LatencyRecorder.overlayDrawn("Webcam") } // TEMP-ClavoLat
    }

    private fun drawImpl(canvas: Canvas) {
        super.draw(canvas)
        if (frameWidth <= 0 || frameHeight <= 0) return

        results?.detections()?.forEach { detection ->
            val bb = detection.boundingBox()
            val inFrame = FrameMapping.modelBoxToFrame(
                FrameMapping.Box(bb.left, bb.top, bb.right, bb.bottom),
                frameWidth, frameHeight, rotation90
            )
            val rect = RectF(inFrame.left, inFrame.top, inFrame.right, inFrame.bottom)
            frameToOverlay.mapRect(rect)

            // Label and box colour come from the class NAME (model metadata), not the index.
            val category = detection.categories()[0]
            val label = ClassColors.labelFor(category.categoryName(), category.index())
            boxPaint.color = ClassColors.colorFor(label, defaultBoxColor)
            canvas.drawRect(rect, boxPaint)

            val drawableText = label + " " + String.format("%.2f", category.score())
            textBackgroundPaint.getTextBounds(drawableText, 0, drawableText.length, bounds)
            val textHeight = bounds.height()
            val textWidth = textBackgroundPaint.measureText(drawableText)

            canvas.drawRect(
                rect.left,
                rect.top - textHeight - (BOUNDING_RECT_TEXT_PADDING * 2),
                rect.left + textWidth + (BOUNDING_RECT_TEXT_PADDING * 2),
                rect.top,
                textBackgroundPaint
            )
            canvas.drawText(
                drawableText,
                rect.left + BOUNDING_RECT_TEXT_PADDING,
                rect.top - BOUNDING_RECT_TEXT_PADDING,
                textPaint
            )
        }
    }

    /**
     * @param frameW,frameH size of the un-rotated frame the detector input was cut from
     * @param rotation clockwise manual rotation (0/90/180/270) applied to that frame
     * @param frameToOverlay maps frame coordinates to this view's coordinates
     */
    fun setResults(
        detectionResults: ObjectDetectorResult,
        frameW: Int,
        frameH: Int,
        rotation: Int,
        frameToOverlay: Matrix
    ) {
        results = detectionResults
        frameWidth = frameW
        frameHeight = frameH
        rotation90 = rotation
        this.frameToOverlay.set(frameToOverlay)
        invalidate()
    }

    companion object {
        private const val BOUNDING_RECT_TEXT_PADDING = 8
    }
}
