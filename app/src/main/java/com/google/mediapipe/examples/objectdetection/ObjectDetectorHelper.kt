/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.mediapipe.examples.objectdetection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.camera.core.ImageProxy
import com.google.mediapipe.examples.objectdetection.utils.LatencyRecorder // TEMP-ClavoLat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult

class ObjectDetectorHelper(
    var threshold: Float = THRESHOLD_DEFAULT,
    var maxResults: Int = MAX_RESULTS_DEFAULT,
    var currentDelegate: Int = DELEGATE_CPU,
    var modelPath: String,
    var runningMode: RunningMode = RunningMode.IMAGE,
    val context: Context,
    // The listener is only used when running in RunningMode.LIVE_STREAM
    var objectDetectorListener: DetectorListener? = null,
) {

    // Only touched on the shared DetectorQueue thread (create/close/detect); read elsewhere only
    // for null checks.
    @Volatile
    private var objectDetector: ObjectDetector? = null
    @Volatile
    private var imageRotation = 0
    @Volatile
    private var rotationInitialized = false
    @Volatile
    private var imageProcessingOptions: ImageProcessingOptions? = null

    // Manual clockwise rotation (0/90/180/270) applied to camera frames before the detector sees
    // them (Camera mode). Default 0 = unchanged behaviour. Each frame remembers the value it was
    // sent with, so a late result is mapped with the rotation it was produced under.
    @Volatile
    var manualRotation = 0
    private val manualRotationByFrame = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    // closing: release() was called, so new frames are refused and late results are ignored.
    // closed: the native detector has really been closed on the queue.
    @Volatile
    private var closing = false
    @Volatile
    private var closed = false

    // Frames handed to the native graph and not yet answered (log only; frames the graph drops
    // silently are never answered, so it is reset whenever a detector is created).
    private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)

    // At most one detectAsync is waiting on the queue; extra frames are dropped.
    private val detectPending = java.util.concurrent.atomic.AtomicBoolean(false)

    private val id = System.identityHashCode(this).toString(16)

    private fun dlog(msg: String) { // TEMP-ClavoDet
        Log.i("ClavoDet", "[helper $id] $msg inFlight=${inFlight.get()} thread=${Thread.currentThread().name}") // TEMP-ClavoDet
    } // TEMP-ClavoDet

    // Runs [task] on the shared queue and waits (inline when already on it).
    private fun <T> onQueue(task: () -> T): T =
        if (DetectorQueue.isQueueThread()) task() else DetectorQueue.call(task)

    init {
        setupObjectDetector()
    }

    // Closes the detector (blocking until done). Safe from any worker thread; do not call on Main.
    fun clearObjectDetector() {
        onQueue { doClear() }
    }

    // Same, but only enqueues the close on the shared queue (safe on Main).
    fun clearObjectDetectorAsync() {
        DetectorQueue.execute { doClear() }
    }

    // Permanently shuts the helper down. Call after the frame producer has been stopped. Returns
    // at once; the close is queued behind any create/detect already queued.
    fun release() {
        closing = true
        objectDetectorListener = null
        DetectorQueue.execute {
            doClear()
            closed = true
        }
    }

    private fun doClear() {
        val d = objectDetector ?: return
        dlog("close-start")
        objectDetector = null
        try {
            d.close()
        } catch (e: RuntimeException) {
            Log.e(TAG, "close failed: " + e.message)
        }
        dlog("close-done")
    }

    // Initialize the object detector using current settings, on the shared queue.
    fun setupObjectDetector() {
        onQueue { doSetup() }
    }

    // Changes the threshold and rebuilds the detector as one queued step.
    fun updateThreshold(value: Float) {
        onQueue {
            threshold = value
            doClear()
            doSetup()
        }
    }

    private fun doSetup() {
        if (closing || closed) return
        // Set general detection options, including number of used threads
        if (modelPath.isEmpty()) {
            objectDetectorListener?.onError("Model path is empty.")
            return
        }
        val baseOptionsBuilder = BaseOptions.builder()

        // Use the specified hardware for running the model. Default to CPU
        when (currentDelegate) {
            DELEGATE_CPU -> {
                baseOptionsBuilder.setDelegate(Delegate.CPU)
            }

            DELEGATE_GPU -> {
                // Is there a check for GPU being supported?
                baseOptionsBuilder.setDelegate(Delegate.GPU)
            }
        }

        baseOptionsBuilder.setModelAssetPath(modelPath)
//        baseOptionsBuilder.setModelFilePath(currentModelPath)

        // Check if runningMode is consistent with objectDetectorListener
        when (runningMode) {
            RunningMode.LIVE_STREAM -> {
                if (objectDetectorListener == null) {
                    throw IllegalStateException(
                        "objectDetectorListener must be set when runningMode is LIVE_STREAM."
                    )
                }
            }

            RunningMode.IMAGE, RunningMode.VIDEO -> {
                // no-op
            }
        }

        try {
            val optionsBuilder = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptionsBuilder.build())
                .setScoreThreshold(threshold).setRunningMode(runningMode)
                .setMaxResults(maxResults)

            imageProcessingOptions = ImageProcessingOptions.builder()
                .setRotationDegrees(imageRotation).build()

            when (runningMode) {
                RunningMode.IMAGE, RunningMode.VIDEO -> optionsBuilder.setRunningMode(
                    runningMode
                )

                RunningMode.LIVE_STREAM -> optionsBuilder.setRunningMode(
                    runningMode
                ).setResultListener(this::returnLivestreamResult)
                    .setErrorListener(this::returnLivestreamError)
            }

            val options = optionsBuilder.build()
            dlog("create-start model=$modelPath")
            val newDetector = ObjectDetector.createFromOptions(context, options)
            if (closing || closed) {
                newDetector.close()
                dlog("create-done but released meanwhile: closed again")
            } else {
                doClear()
                objectDetector = newDetector
                inFlight.set(0)
                dlog("create-done")
            }
        } catch (e: IllegalStateException) {
            objectDetectorListener?.onError(
                "Model gagal dimuat ($modelPath): ${e.message}"
            )
            Log.e(TAG, "TFLite failed to load model with error: " + e.message)
        } catch (e: RuntimeException) {
            objectDetectorListener?.onError(
                "Model gagal dimuat ($modelPath): ${e.message}",
                GPU_ERROR
            )
            Log.e(
                TAG,
                "Object detector failed to load model with error: " + e.message
            )
        }
    }

    fun changeModel(newModelPath: String) {
        onQueue {
            modelPath = newModelPath
            doClear()
            doSetup()
        }
    }

    // Return running status of recognizer helper
    fun isClosed(): Boolean {
        return objectDetector == null
    }

    // Accepts the URI for a video file loaded from the user's gallery and attempts to run
    // object detection inference on the video. This process will evaluate every frame in
    // the video and attach the results to a bundle that will be returned.
    fun detectVideoFile(
        videoUri: Uri, inferenceIntervalMs: Long
    ): ResultBundle? {

        if (runningMode != RunningMode.VIDEO) {
            throw IllegalArgumentException(
                "Attempting to call detectVideoFile" + " while not using RunningMode.VIDEO"
            )
        }

        if (objectDetector == null) return null

        // Inference time is the difference between the system time at the start and finish of the
        // process
        val startTime = SystemClock.uptimeMillis()

        var didErrorOccurred = false
        // Load frames from the video and run the object detection model.
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, videoUri)
        val videoLengthMs =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLong()

        // Note: We need to read width/height from frame instead of getting the width/height
        // of the video directly because MediaRetriever returns frames that are smaller than the
        // actual dimension of the video file.
        val firstFrame = retriever.getFrameAtTime(0)
        val width = firstFrame?.width
        val height = firstFrame?.height

        // If the video is invalid, returns a null detection result
        if ((videoLengthMs == null) || (width == null) || (height == null)) return null

        // Next, we'll get one frame every frameInterval ms, then run detection on these frames.
        val resultList = mutableListOf<ObjectDetectorResult>()
        val numberOfFrameToRead = videoLengthMs.div(inferenceIntervalMs)

        for (i in 0..numberOfFrameToRead) {
            val timestampMs = i * inferenceIntervalMs // ms

            retriever.getFrameAtTime(
                timestampMs * 1000, // convert from ms to micro-s
                MediaMetadataRetriever.OPTION_CLOSEST
            )?.let { frame ->
                // Convert the video frame to ARGB_8888 which is required by the MediaPipe
                val argb8888Frame =
                    if (frame.config == Bitmap.Config.ARGB_8888) frame
                    else frame.copy(Bitmap.Config.ARGB_8888, false)

                // Convert the input Bitmap object to an MPImage object to run inference
                val mpImage = BitmapImageBuilder(argb8888Frame).build()

                // Run object detection using MediaPipe Object Detector API
                objectDetector?.detectForVideo(mpImage, timestampMs)
                    ?.let { detectionResult ->
                        resultList.add(detectionResult)
                    } ?: {
                    didErrorOccurred = true
                    objectDetectorListener?.onError(
                        "ResultBundle could not be returned" + " in detectVideoFile"
                    )
                }
            } ?: run {
                didErrorOccurred = true
                objectDetectorListener?.onError(
                    "Frame at specified time could not be" + " retrieved when detecting in video."
                )
            }
        }

        retriever.release()

        val inferenceTimePerFrameMs =
            (SystemClock.uptimeMillis() - startTime).div(numberOfFrameToRead)

        return if (didErrorOccurred) {
            null
        } else {
            ResultBundle(resultList, inferenceTimePerFrameMs, height, width)
        }
    }

    // Runs object detection on live streaming cameras frame-by-frame and returns the results
    // asynchronously to the caller.
    fun detectLivestreamFrame(imageProxy: ImageProxy) {

        if (runningMode != RunningMode.LIVE_STREAM) {
            imageProxy.close()
            throw IllegalArgumentException(
                "Attempting to call detectLivestreamFrame" + " while not using RunningMode.LIVE_STREAM"
            )
        }

        LatencyRecorder.frameReceived("Camera") // TEMP-ClavoLat
        // Drop the frame if the detector is closing/closed (e.g. while the fragment is stopping),
        // before any bitmap is copied.
        if (closing || closed || objectDetector == null) {
            imageProxy.close()
            return
        }

        val frameTime = SystemClock.uptimeMillis()

        // Read everything needed from the proxy before it is closed
        val frameRotation = imageProxy.imageInfo.rotationDegrees

        // Copy out RGB bits from the frame to a bitmap buffer
        val bitmapBuffer = Bitmap.createBitmap(
            imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
        )
        imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(it.planes[0].buffer) }

        // The rotation lives in the per-frame ImageProcessingOptions, so a new value only needs
        // new options, not a new detector. The first frame takes its rotation here, so the
        // detector is not rebuilt on the first frame.
        if (!rotationInitialized || frameRotation != imageRotation) {
            imageRotation = frameRotation
            rotationInitialized = true
            imageProcessingOptions = ImageProcessingOptions.builder()
                .setRotationDegrees(frameRotation).build()
        }

        // Manual rotation: the detector sees the same rotated picture as the preview.
        val manual = manualRotation
        val input = if (manual == 0) bitmapBuffer else Bitmap.createBitmap(
            bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
            android.graphics.Matrix().apply { postRotate(manual.toFloat()) }, true
        )
        if (manualRotationByFrame.size > 50) manualRotationByFrame.clear()
        manualRotationByFrame[frameTime] = manual

        com.google.mediapipe.examples.objectdetection.utils.GeoLog.setFrame(bitmapBuffer.width, bitmapBuffer.height, input.width, input.height, imageRotation, manual) // TEMP-ClavoGeo

        // Convert the input Bitmap object to an MPImage object to run inference
        val mpImage = BitmapImageBuilder(input).build()

        detectAsync(mpImage, frameTime)
    }

    // Runs detection on a Bitmap frame (used by WebcamFragment for USB/UVC webcam frames)
    fun detectFromBitmap(bitmap: Bitmap, rotationDegrees: Int = 0) {
        if (runningMode != RunningMode.LIVE_STREAM) return

        // Preprocessing: Konversi ke RGB dan normalisasi
        val inputSize = com.google.mediapipe.examples.objectdetection.utils.FrameMapping.MODEL_INPUT_SIZE
        val normalizedBitmap = normalizeBitmap(bitmap, inputSize)

        // Update rotasi
        if (closing || closed) return
        if (!rotationInitialized || rotationDegrees != imageRotation) {
            imageRotation = rotationDegrees
            rotationInitialized = true
            imageProcessingOptions = ImageProcessingOptions.builder()
                .setRotationDegrees(imageRotation)
                .build()
        }

        val mpImage = BitmapImageBuilder(normalizedBitmap).build()
        detectAsync(mpImage, SystemClock.uptimeMillis())
    }

    private fun normalizeBitmap(bitmap: Bitmap, size: Int): Bitmap {
        // Resize to MODEL_INPUT_SIZE x MODEL_INPUT_SIZE (no-op when the caller already did)
        return Bitmap.createScaledBitmap(bitmap, size, size, true)
    }


    // Run object detection using MediaPipe Object Detector API
    @VisibleForTesting
    fun detectAsync(mpImage: MPImage, frameTime: Long) {
        // As we're using running mode LIVE_STREAM, the detection result will be returned in
        // returnLivestreamResult function
        // The frame is handed to the native graph on the shared queue, the same thread that creates
        // and closes detectors, so a close always runs after the submission queued before it.
        if (closing || closed) return
        if (!detectPending.compareAndSet(false, true)) return // one waiting frame is enough
        DetectorQueue.execute {
            try {
                if (closing || closed) return@execute
                val detector = objectDetector ?: return@execute
                val options = imageProcessingOptions ?: return@execute
                inFlight.incrementAndGet()
                try {
                    LatencyRecorder.inferStart(frameTime) // TEMP-ClavoLat
                    detector.detectAsync(mpImage, options, frameTime)
                } catch (e: RuntimeException) {
                    inFlight.updateAndGet { if (it > 0) it - 1 else 0 }
                    Log.e(TAG, "detectAsync failed: " + e.message)
                }
            } finally {
                detectPending.set(false)
            }
        }
    }

    // Return the detection result to this ObjectDetectorHelper's caller
    private fun returnLivestreamResult(
        result: ObjectDetectorResult, input: MPImage
    ) {
        inFlight.updateAndGet { if (it > 0) it - 1 else 0 }
        if (closing || closed) {
            manualRotationByFrame.remove(result.timestampMs())
            return
        }
        LatencyRecorder.inferEnd(result.timestampMs()) // TEMP-ClavoLat
        val finishTimeMs = SystemClock.uptimeMillis()
        val inferenceTime = finishTimeMs - result.timestampMs()

        objectDetectorListener?.onResults(
            ResultBundle(
                listOf(result),
                inferenceTime,
                input.height,
                input.width,
                imageRotation,
                manualRotationByFrame.remove(result.timestampMs()) ?: 0
            )
        )
    }

    // Return errors thrown during detection to this ObjectDetectorHelper's caller
    private fun returnLivestreamError(error: RuntimeException) {
        inFlight.updateAndGet { if (it > 0) it - 1 else 0 }
        if (closing || closed) return
        objectDetectorListener?.onError(
            error.message ?: "An unknown error has occurred"
        )
    }

    // Accepted a Bitmap and runs object detection inference on it to return results back
    // to the caller
    fun detectImage(image: Bitmap): ResultBundle? {

        if (runningMode != RunningMode.IMAGE) {
            throw IllegalArgumentException(
                "Attempting to call detectImage" + " while not using RunningMode.IMAGE"
            )
        }

        if (objectDetector == null) return null

        // Inference time is the difference between the system time at the start and finish of the
        // process
        val startTime = SystemClock.uptimeMillis()

        // Convert the input Bitmap object to an MPImage object to run inference
        val mpImage = BitmapImageBuilder(image).build()

        // Run object detection using MediaPipe Object Detector API
        objectDetector?.detect(mpImage)?.also { detectionResult ->
            val inferenceTimeMs = SystemClock.uptimeMillis() - startTime
            return ResultBundle(
                listOf(detectionResult),
                inferenceTimeMs,
                image.height,
                image.width
            )
        }

        // If objectDetector?.detect() returns null, this is likely an error. Returning null
        // to indicate this.
        return null
    }

    // Wraps results from inference, the time it takes for inference to be performed, and
    // the input image and height for properly scaling UI to return back to callers
    data class ResultBundle(
        val results: List<ObjectDetectorResult>,
        val inferenceTime: Long,
        val inputImageHeight: Int,
        val inputImageWidth: Int,
        val inputImageRotation: Int = 0,
        /** Manual clockwise rotation (Camera mode) applied to the frame before detection. */
        val manualRotation: Int = 0
    )

    companion object {
        const val DELEGATE_CPU = 0
        const val DELEGATE_GPU = 1
        const val efficientdet_lite2_cengkeh_V2 = 0
        const val MAX_RESULTS_DEFAULT = 3
        const val THRESHOLD_DEFAULT = 0.5F
        const val OTHER_ERROR = 0
        const val GPU_ERROR = 1

        const val TAG = "ObjectDetectorHelper"
    }

    // Used to pass results or errors back to the calling class
    interface DetectorListener {
        fun onError(error: String, errorCode: Int = OTHER_ERROR)
        fun onResults(resultBundle: ResultBundle)
    }

}

/**
 * One process-wide single thread for every detector create / detectAsync / close. Because Camera
 * and Webcam share it, a detector of one tab is always fully closed before the next tab's detector
 * is created, and a close runs after any submission queued before it.
 */
object DetectorQueue {
    @Volatile
    private var queueThread: Thread? = null

    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "clavo-det-queue").also { queueThread = it }
    }

    fun isQueueThread(): Boolean = Thread.currentThread() === queueThread

    fun execute(task: () -> Unit) {
        executor.execute {
            try {
                task()
            } catch (t: Throwable) {
                Log.e("ClavoDet", "queue task failed: $t")
            }
        }
    }

    fun <T> call(task: () -> T): T {
        try {
            return executor.submit(java.util.concurrent.Callable { task() }).get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }
}
