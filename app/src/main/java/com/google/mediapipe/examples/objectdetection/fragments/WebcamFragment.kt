package com.google.mediapipe.examples.objectdetection.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.usb.UsbManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.mediapipe.examples.objectdetection.MainViewModel
import com.google.mediapipe.examples.objectdetection.ObjectDetectorHelper
import com.google.mediapipe.examples.objectdetection.OverlayViewWebcam
import com.google.mediapipe.examples.objectdetection.R
import com.google.mediapipe.examples.objectdetection.databinding.FragmentWebcamBinding
import com.google.mediapipe.examples.objectdetection.utils.ModelRepository
import com.google.mediapipe.examples.objectdetection.utils.ModelSpinnerController
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.IAspectRatio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WebcamFragment : CameraFragment(), ObjectDetectorHelper.DetectorListener {
    private val TAG = "ObjectDetection"
    private var _binding: FragmentWebcamBinding? = null
    private val binding get() = _binding!!

    private lateinit var overlay: OverlayViewWebcam
    private lateinit var objectDetectorHelper: ObjectDetectorHelper
    private val viewModel: MainViewModel by activityViewModels()

    private var initialModelPath: String = ""
    private var modelSpinnerController: ModelSpinnerController? = null

    private val DEFAULT_PREVIEW_WIDTH = 640
    private val DEFAULT_PREVIEW_HEIGHT = 480
    private val detectionInterval = 150L
    private val STATUS_POLL_MS = 1000L
    private var detectionJob: Job? = null

    // True only while the UVC camera is actually open and rendering frames.
    @Volatile
    private var isCameraOpened = false

    // What the user sees when the webcam is not streaming. Only UI feedback: USB permission and
    // device handling stay in libausbc.
    private enum class WebcamUiState { CHECKING, NO_DEVICE, PERMISSION_DENIED, ERROR, OPEN }

    private var uiState = WebcamUiState.CHECKING
    private var deniedTicks = 0
    private var lastErrorMessage: String? = null

    private val isDetectorReady: Boolean
        get() = this::objectDetectorHelper.isInitialized

    // Replace your existing onCreateView with this:
    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View? {
        _binding = FragmentWebcamBinding.inflate(inflater, container, false)
        return binding.root
    }
    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                Log.d(TAG, "USB Camera Opened")
                isCameraOpened = true
                // Start detection only now that frames can be read from the camera.
                activity?.runOnUiThread {
                    deniedTicks = 0
                    showWebcamState(WebcamUiState.OPEN)
                    if (_binding != null && isDetectorReady) startRealTimeDetection()
                }
            }
            ICameraStateCallBack.State.CLOSED -> {
                Log.d(TAG, "USB Camera Closed")
                isCameraOpened = false
                activity?.runOnUiThread {
                    detectionJob?.cancel()
                    _binding?.overlay?.clear()
                    // The status monitor decides between "no webcam" and "no permission".
                    showWebcamState(WebcamUiState.CHECKING)
                }
            }
            ICameraStateCallBack.State.ERROR -> {
                Log.e(TAG, "USB Camera Error: $msg")
                isCameraOpened = false
                activity?.runOnUiThread {
                    detectionJob?.cancel()
                    lastErrorMessage = msg
                    showWebcamState(WebcamUiState.ERROR)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        overlay = binding.overlay

        // Setup MediaPipe Object Detector
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            loadModelAndStartDetection()
        }

        initBottomSheetControls()
        setupRotationLayout()

        binding.webcamRetryButton.setOnClickListener { retryWebcam() }
        showWebcamState(WebcamUiState.CHECKING)
        // Runs only while the fragment is resumed, so it pauses while the system USB dialog is up.
        @Suppress("DEPRECATION")
        viewLifecycleOwner.lifecycleScope.launchWhenResumed {
            while (isActive) {
                evaluateWebcamStatus()
                delay(STATUS_POLL_MS)
            }
        }
    }

    // ==================== Webcam status feedback ====================

    private fun evaluateWebcamStatus() {
        if (_binding == null) return
        if (isCameraOpened) {
            deniedTicks = 0
            showWebcamState(WebcamUiState.OPEN)
            return
        }
        // null = libausbc has not registered its USB client yet; say nothing until it has.
        val devices = try { getDeviceList() } catch (e: Exception) { null } ?: return
        if (devices.isEmpty()) {
            deniedTicks = 0
            showWebcamState(WebcamUiState.NO_DEVICE)
            return
        }
        if (uiState == WebcamUiState.ERROR) return // keep the error until the user retries

        val ctx = context ?: return
        val usbManager = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        if (devices.any { usbManager.hasPermission(it) }) {
            deniedTicks = 0
            showWebcamState(WebcamUiState.CHECKING) // permission granted, camera is opening
            return
        }
        // A webcam is plugged in, there is no USB permission and we are resumed (so the system
        // permission dialog is not on screen): the user dismissed or denied it. Require two
        // consecutive ticks so a dialog that is about to appear is not reported as a denial.
        deniedTicks++
        if (deniedTicks >= 2) showWebcamState(WebcamUiState.PERMISSION_DENIED)
    }

    private fun retryWebcam() {
        deniedTicks = 0
        val device = try { getDeviceList()?.firstOrNull() } catch (e: Exception) { null }
        if (device == null) {
            showWebcamState(WebcamUiState.NO_DEVICE)
            return
        }
        showWebcamState(WebcamUiState.CHECKING)
        requestPermission(device) // libausbc: shows the system dialog or reopens the camera
    }

    private fun showWebcamState(state: WebcamUiState) {
        uiState = state
        val b = _binding ?: return
        val message: String? = when (state) {
            WebcamUiState.NO_DEVICE ->
                "Webcam belum terdeteksi. Colok webcam USB (OTG) lalu izinkan akses USB."
            WebcamUiState.PERMISSION_DENIED ->
                "Izin USB untuk webcam ditolak, jadi webcam tidak bisa dibuka. " +
                    "Tekan \"Coba lagi\" lalu pilih Izinkan."
            WebcamUiState.ERROR ->
                "Webcam bermasalah: ${lastErrorMessage ?: "kesalahan tidak diketahui"}. " +
                    "Periksa kabel/adaptor OTG lalu tekan \"Coba lagi\"."
            WebcamUiState.CHECKING, WebcamUiState.OPEN -> null
        }
        b.webcamStatusPanel.visibility = if (message == null) View.GONE else View.VISIBLE
        if (message != null) b.webcamStatusText.text = message
        b.webcamRetryButton.visibility =
            if (state == WebcamUiState.PERMISSION_DENIED || state == WebcamUiState.ERROR) View.VISIBLE
            else View.GONE
    }

    private suspend fun loadModelAndStartDetection() {
        try {
            val ctx = context ?: return
            val available = withContext(Dispatchers.IO) { ModelRepository.listModels(ctx) }
            if (_binding == null) return
            val initial = ModelRepository.resolveInitialModel(ctx, available)
            modelSpinnerController?.bind(available, initial)
            if (initial == null) {
                Log.e(TAG, "No .tflite models found in assets/models/")
                return
            }
            initialModelPath = initial.assetPath

            detectionJob?.cancel()
            if (isDetectorReady) objectDetectorHelper.release()

            objectDetectorHelper = withContext(Dispatchers.IO) {
                ObjectDetectorHelper(
                    context = ctx,
                    threshold = viewModel.currentThreshold,
                    currentDelegate = viewModel.currentDelegate,
                    modelPath = initialModelPath,
                    maxResults = viewModel.currentMaxResults,
                    objectDetectorListener = this@WebcamFragment,
                    runningMode = RunningMode.LIVE_STREAM
                )
            }

            if (_binding == null) {
                // View was destroyed while the model was loading.
                objectDetectorHelper.release()
                return
            }
            // Only run detection if the UVC camera is already open; otherwise
            // onCameraState(OPENED) will start it.
            if (isCameraOpened) startRealTimeDetection()
        } catch (e: Exception) {
            Log.e(TAG, "Gagal load model", e)
            context?.let {
                Toast.makeText(it, "Gagal memuat model: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ==================== CameraFragment Override ====================

    override fun getCameraView(): IAspectRatio? {
        return binding.textureView
    }

    override fun getCameraViewContainer(): ViewGroup? {
        return binding.webcamContainer
    }

    override fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(DEFAULT_PREVIEW_WIDTH)
            .setPreviewHeight(DEFAULT_PREVIEW_HEIGHT)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
            .setAspectRatioShow(true)
            .create()
    }

    // ==================== Detection ====================

    private fun startRealTimeDetection() {
        detectionJob?.cancel()
        if (_binding == null || !isDetectorReady || !isCameraOpened) return
        detectionJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                if (isCameraOpened) detectFromWebcam()
                delay(detectionInterval)
            }
        }
    }

    private fun detectFromWebcam() {
        val b = _binding ?: return
        if (!isAdded || !isCameraOpened || !isDetectorReady) return

        try {
            val frameBitmap = b.textureView.getBitmap() ?: return
            val cropped = cropBitmapToSquare(frameBitmap)
            val inputBitmap = Bitmap.createScaledBitmap(cropped, 384, 384, true)

            objectDetectorHelper.detectFromBitmap(inputBitmap, getDeviceRotation())
        } catch (e: Exception) {
            Log.e(TAG, "Error detectFromWebcam", e)
        }
    }

    private fun cropBitmapToSquare(bitmap: Bitmap): Bitmap {
        val side = minOf(bitmap.width, bitmap.height)
        val x = (bitmap.width - side) / 2
        val y = (bitmap.height - side) / 2
        return Bitmap.createBitmap(bitmap, x, y, side, side)
    }

    private fun getDeviceRotation(): Int {
        return if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 90 else 0
    }

    // ==================== UI & Model Spinner ====================

    private fun setupRotationLayout() {
        view?.viewTreeObserver?.addOnGlobalLayoutListener {
            // Rotation handling code (bisa kamu sesuaikan lagi)
        }
    }

    // Wires the shared model picker; models are bound once they are loaded.
    private fun initBottomSheetControls() {
        val sheet = binding.bottomSheetLayout
        modelSpinnerController = ModelSpinnerController(
            requireContext().applicationContext,
            sheet.spinnerModel,
            sheet.modelStatus
        ) { model ->
            if (!isDetectorReady) return@ModelSpinnerController
            detectionJob?.cancel()
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO + NonCancellable) {
                    objectDetectorHelper.changeModel(model.assetPath)
                }
                val b = _binding ?: return@launch
                b.overlay.clear()
                Toast.makeText(context, "Model diubah ke ${model.displayName}", Toast.LENGTH_SHORT).show()
                startRealTimeDetection()
            }
        }
    }

    // ==================== Detector Listener ====================

    override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle) {
        activity?.runOnUiThread {
            // The view may already be destroyed when this runs; never touch binding via !!.
            val b = _binding ?: return@runOnUiThread
            if (!isAdded || view == null) return@runOnUiThread

            val detections = resultBundle.results.firstOrNull()?.detections() ?: emptyList()
            if (detections.isNotEmpty()) {
                b.overlay.setResults(
                    resultBundle.results.first(),
                    DEFAULT_PREVIEW_HEIGHT,
                    DEFAULT_PREVIEW_WIDTH,
                    resultBundle.inputImageRotation
                )
            } else {
                b.overlay.clear()
            }
            b.overlay.invalidate()
        }
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            if (!isAdded) return@runOnUiThread
            context?.let { Toast.makeText(it, error, Toast.LENGTH_SHORT).show() }
        }
    }

    // ==================== Lifecycle ====================

    override fun onDestroyView() {
        // Stop the producer, detach the callback, then close the detector.
        detectionJob?.cancel()
        detectionJob = null
        isCameraOpened = false
        if (isDetectorReady) {
            objectDetectorHelper.objectDetectorListener = null
            objectDetectorHelper.release()
        }
        modelSpinnerController = null
        _binding = null
        super.onDestroyView()
    }
}