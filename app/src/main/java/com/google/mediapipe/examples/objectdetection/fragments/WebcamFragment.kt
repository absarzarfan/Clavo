package com.google.mediapipe.examples.objectdetection.fragments

import android.annotation.SuppressLint
import android.content.BroadcastReceiver // TEMP-ClavoUSB
import android.content.Context
import android.hardware.usb.UsbManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.View.OnLayoutChangeListener
import android.widget.SeekBar
import com.google.mediapipe.examples.objectdetection.databinding.InfoBottomSheetBinding
import com.google.mediapipe.examples.objectdetection.utils.DetectionPrefs
import com.google.mediapipe.examples.objectdetection.utils.FrameMapping
import com.google.mediapipe.examples.objectdetection.utils.ThresholdScale
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import androidx.lifecycle.whenStarted
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.mediapipe.examples.objectdetection.MainViewModel
import com.google.mediapipe.examples.objectdetection.ObjectDetectorHelper
import com.google.mediapipe.examples.objectdetection.OverlayViewWebcam
import com.google.mediapipe.examples.objectdetection.R
import com.google.mediapipe.examples.objectdetection.databinding.FragmentWebcamBinding
import com.google.mediapipe.examples.objectdetection.utils.ModelRepository
import com.google.mediapipe.examples.objectdetection.utils.ModelSpinnerController
import com.google.mediapipe.examples.objectdetection.utils.UsbDebugLog // TEMP-ClavoUSB
import com.google.mediapipe.examples.objectdetection.utils.LatencyRecorder // TEMP-ClavoLat
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

class WebcamFragment : CameraFragment(), ObjectDetectorHelper.DetectorListener,
    com.google.mediapipe.examples.objectdetection.utils.RotatableScreen {
    private val TAG = "ObjectDetection"
    private var _binding: FragmentWebcamBinding? = null
    private val binding get() = _binding!!

    private lateinit var overlay: OverlayViewWebcam
    private lateinit var objectDetectorHelper: ObjectDetectorHelper
    private val viewModel: MainViewModel by activityViewModels()

    private var initialModelPath: String = ""
    private var modelSpinnerController: ModelSpinnerController? = null

    private val DEFAULT_PREVIEW_WIDTH = 640 // requested UVC size (not used for overlay scaling)
    private val DEFAULT_PREVIEW_HEIGHT = 480
    private val detectionInterval = 150L
    private val STATUS_POLL_MS = 1000L
    private var detectionJob: Job? = null

    // True only while the UVC camera is actually open and rendering frames.
    @Volatile
    private var isCameraOpened = false

    // What the user sees when the webcam is not streaming. Only UI feedback: USB permission and
    // device handling stay in libausbc.
    private enum class WebcamUiState { CHECKING, NO_DEVICE, PERMISSION_DENIED, OPEN_FAILED, ERROR, RECONNECTING, OPEN }

    private var uiState = WebcamUiState.CHECKING
    private var deniedTicks = 0
    // Permission is granted but the camera is not open: ticks in a row, and requestPermission()
    // attempts made for the current plug-in. Both reset on unplug, on open and on "Coba lagi".
    private var grantedNotOpenTicks = 0
    // One connection per device (by device name): IDLE -> CONNECTING -> OPEN.
    private enum class ConnState { IDLE, CONNECTING, OPEN }
    private val connStates = HashMap<String, ConnState>()
    private var currentDeviceName: String? = null
    private var connectStartMs = 0L
    private var openedAtMs = 0L
    private var flowingSinceMs = 0L
    // Consecutive failed connect attempts; reset only after frames flowed steadily for 3 s.
    private var failures = 0
    private var nextRetryAtMs = 0L
    private val CONNECT_TIMEOUT_MS = 5000L
    private val MAX_FAILURES = 5
    private val STABLE_FLOW_MS = 3000L
    private val STALL_MS = 2000L              // no frame for this long while OPEN = failure
    private val SUPPRESS_AFTER_ATTACH_MS = 3000L // libausbc auto-connect gets this long after ATTACHED
    private var lastFrameMs = 0L
    private var lastAttachedMs = 0L
    private var viewAlive = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var usbLogReceiver: BroadcastReceiver? = null // TEMP-ClavoUSB
    private var lastErrorMessage: String? = null

    // Manual rotation (0/90/180/270, clockwise), shared by preview, detector bitmap and overlay.
    private var webcamRotation = 0
    // Frame the detector input was cut from (un-rotated TextureView bitmap size + rotation used).
    @Volatile private var lastFrameW = 0
    @Volatile private var lastFrameH = 0
    @Volatile private var lastFrameRot = 0
    // Status is derived from frames that really arrive (SurfaceTexture timestamp changes).
    private var lastSurfaceTs = 0L
    private var noDeviceTicks = 0

    private val isDetectorReady: Boolean
        get() = this::objectDetectorHelper.isInitialized

    // Replace your existing onCreateView with this:
    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View? {
        _binding = FragmentWebcamBinding.inflate(inflater, container, false)
        return binding.root
    }
    // libausbc posts camera state on the main thread. State is updated here directly; it does not
    // depend on activity?.runOnUiThread (which is skipped when the activity is momentarily null).
    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        onMain {
            val name = self.getUsbDevice().deviceName
            val sid = self.eventSessionId
            val latest = self.getLatestSessionId()
            UsbDebugLog.ev("camera-state", "session=$sid latest=$latest code=$code msg=$msg device=$name conn=${connStates[name]} uiState=$uiState sinceOpenedMs=${sinceOpenedMs()}")
            if (!viewAlive) {
                UsbDebugLog.ev("decision-ignore", "reason=view-gone session=$sid code=$code")
                return@onMain
            }
            when (code) {
                ICameraStateCallBack.State.OPENED -> handleOpened(name, sid, latest)
                ICameraStateCallBack.State.CLOSED -> handleClosed(name, null, sid, latest)
                ICameraStateCallBack.State.ERROR -> handleClosed(name, msg ?: "kesalahan tidak diketahui", sid, latest)
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun sinceOpenedMs(): Long =
        if (openedAtMs == 0L) -1L else SystemClock.elapsedRealtime() - openedAtMs

    private fun devicePresent(name: String): Boolean =
        try { getDeviceList()?.any { it.deviceName == name } ?: true } catch (e: Exception) { true }

    // OPENED is adopted for any device that is plugged in, whatever our own state is (IDLE,
    // CONNECTING or RECONNECTING): it comes from libausbc's auto-connect or from our connect and is
    // real. It is ignored only when its session or device is gone.
    private fun handleOpened(name: String, sid: Int, latest: Int) {
        val now = SystemClock.elapsedRealtime()
        val present = devicePresent(name)
        if (sid < latest || !present) {
            UsbDebugLog.ev("decision-ignore", "reason=${if (sid < latest) "old-session($sid<$latest)" else "device-gone"} code=OPENED device=$name uiState=$uiState")
            if (!present) closeCurrentCamera()
            return
        }
        UsbDebugLog.ev("decision-adopt", "OPENED session=$sid conn=${connStates[name]} uiState=$uiState failures=$failures")
        UsbDebugLog.ev("connect-result", "OK device=$name afterMs=${if (connectStartMs == 0L) -1 else now - connectStartMs}")
        connStates[name] = ConnState.OPEN
        currentDeviceName = name
        openedAtMs = now
        lastFrameMs = 0L
        flowingSinceMs = 0L
        nextRetryAtMs = 0L
        Log.d(TAG, "USB Camera Opened")
        LatencyRecorder.activity("Webcam", "kamera_dibuka", "Webcam") // TEMP-ClavoLat
        isCameraOpened = true
        grantedNotOpenTicks = 0
        deniedTicks = 0
        showWebcamState(WebcamUiState.OPEN)
        if (_binding != null && isDetectorReady) startRealTimeDetection()
    }

    // Stops detection and clears the "open" flags. The only way back to "open" is OPENED.
    private fun stopOpenState() {
        isCameraOpened = false
        openedAtMs = 0L
        lastFrameMs = 0L
        flowingSinceMs = 0L
        detectionJob?.cancel()
        _binding?.overlay?.clear()
    }

    // CLOSED or ERROR. Stops the preview, closes the camera cleanly and schedules a retry.
    private fun handleClosed(name: String, errorMsg: String?, sid: Int, latest: Int) {
        val st = connStates[name] ?: ConnState.IDLE
        UsbDebugLog.ev("camera-closed", "device=$name session=$sid error=$errorMsg conn=$st sinceOpenedMs=${sinceOpenedMs()}")
        if (sid < latest) {
            // The state of a newer session is not touched by a late event of an older one.
            UsbDebugLog.ev("decision-ignore", "reason=old-session($sid<$latest) code=${if (errorMsg != null) "ERROR" else "CLOSED"} device=$name")
            return
        }
        LatencyRecorder.activityOnChange("Webcam", "det", "off", "deteksi_berhenti") // TEMP-ClavoLat
        if (errorMsg != null) LatencyRecorder.activity("Webcam", "error", "kamera: " + errorMsg.take(60)) // TEMP-ClavoLat
        stopOpenState()
        if (st == ConnState.IDLE) {
            UsbDebugLog.ev("decision-ignore", "reason=conn-idle (own close or already handled) device=$name")
            return
        }
        connStates[name] = ConnState.IDLE
        if (errorMsg != null) lastErrorMessage = errorMsg
        closeCurrentCamera() // release the camera thread; the CLOSED it causes is ignored (IDLE)
        registerFailure(errorMsg ?: "closed")
    }

    private fun backoffMs(f: Int): Long = minOf(1000L shl (f - 1), 4000L) // 1, 2, 4, 4, 4 s

    private fun registerFailure(reason: String) {
        val now = SystemClock.elapsedRealtime()
        // No retry while a connect (including libausbc's auto-connect) is running, or right after
        // ATTACHED: the connect that is already under way may well succeed.
        val connecting = connStates.values.any { it == ConnState.CONNECTING }
        if (connecting || now - lastAttachedMs < SUPPRESS_AFTER_ATTACH_MS) {
            UsbDebugLog.ev("decision-retry-suppressed", "reason=$reason connecting=$connecting sinceAttachedMs=${now - lastAttachedMs} failures=$failures")
            showWebcamState(WebcamUiState.CHECKING)
            return
        }
        failures++
        if (failures >= MAX_FAILURES) {
            UsbDebugLog.ev("connect-gave-up", "failures=$failures reason=$reason")
            showWebcamState(WebcamUiState.OPEN_FAILED)
            return
        }
        val wait = backoffMs(failures)
        nextRetryAtMs = SystemClock.elapsedRealtime() + wait
        UsbDebugLog.ev("decision-retry", "failures=$failures/$MAX_FAILURES reason=$reason waitMs=$wait")
        showWebcamState(WebcamUiState.RECONNECTING)
    }

    private fun startConnect(device: android.hardware.usb.UsbDevice) {
        val name = device.deviceName
        connStates[name] = ConnState.CONNECTING
        currentDeviceName = name
        connectStartMs = SystemClock.elapsedRealtime()
        UsbDebugLog.ev("connect-start", "attempt=${failures + 1}/$MAX_FAILURES device=$name")
        showWebcamState(if (failures > 0) WebcamUiState.RECONNECTING else WebcamUiState.CHECKING)
        if (!connectDevice(device)) {
            connStates[name] = ConnState.IDLE
            UsbDebugLog.ev("connect-result", "FAILED connectDevice=false device=$name")
            registerFailure("connectDevice=false")
        }
    }

    private fun onUsbAttachDetach(attached: Boolean, device: android.hardware.usb.UsbDevice?) {
        if (!viewAlive) return
        val now = SystemClock.elapsedRealtime()
        if (attached) {
            lastAttachedMs = now
            failures = 0
            nextRetryAtMs = 0L
            noDeviceTicks = 0
            deniedTicks = 0
            grantedNotOpenTicks = 0
            stopOpenState()
            connStates.clear()
            // libausbc connects by itself on ATTACHED: count it as our one connection in flight.
            device?.deviceName?.let {
                connStates[it] = ConnState.CONNECTING
                connectStartMs = now
                UsbDebugLog.ev("decision-auto-connect", "assumed in flight device=$it (no retry for ${SUPPRESS_AFTER_ATTACH_MS}ms)")
            }
            showWebcamState(WebcamUiState.CHECKING) // no tab switch needed
        } else {
            UsbDebugLog.ev("detached-info", "sinceOpenedMs=${sinceOpenedMs()}")
            stopOpenState()
            connStates.clear()
            failures = 0
            nextRetryAtMs = 0L
            noDeviceTicks = 2
            showWebcamState(WebcamUiState.NO_DEVICE)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        UsbDebugLog.d("WebcamFragment.onViewCreated") // TEMP-ClavoUSB
        viewAlive = true
        usbLogReceiver = UsbDebugLog.registerAttachDetachReceiver(requireContext().applicationContext) { attached, dev -> onUsbAttachDetach(attached, dev) } // TEMP-ClavoUSB

        overlay = binding.overlay
        webcamRotation = DetectionPrefs.getWebcamRotation(requireContext())

        // Setup MediaPipe Object Detector
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            // Debounce quick tab switching, and only build the detector while visible. The
            // detector itself is created on the shared DetectorQueue, i.e. after the Camera
            // tab's detector has been closed.
            delay(300)
            viewLifecycleOwner.lifecycle.whenStarted { loadModelAndStartDetection() }
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

    private fun readFrameTimestamp(): Long = try {
        _binding?.textureView?.surfaceTexture?.timestamp ?: 0L
    } catch (e: Exception) {
        0L
    }

    private fun evaluateWebcamStatus() {
        if (_binding == null) return
        val now = SystemClock.elapsedRealtime()

        // 1) Frames are only a health signal: they never make the camera "open" (only OPENED does).
        val ts = readFrameTimestamp()
        val framesFlowing = ts != 0L && ts != lastSurfaceTs
        lastSurfaceTs = ts
        if (framesFlowing) {
            lastFrameMs = now
            if (flowingSinceMs == 0L) flowingSinceMs = now
        } else {
            flowingSinceMs = 0L
        }

        if (isCameraOpened) {
            // Stall: no frame for STALL_MS while open is a failure, handled like CLOSED/ERROR.
            val ref = maxOf(lastFrameMs, openedAtMs)
            if (!framesFlowing && openedAtMs != 0L && now - ref >= STALL_MS) {
                UsbDebugLog.ev("decision-stall", "no frame for ${now - ref}ms while open device=$currentDeviceName")
                stopOpenState()
                currentDeviceName?.let { connStates[it] = ConnState.IDLE }
                closeCurrentCamera()
                registerFailure("frames stalled")
                return
            }
            deniedTicks = 0
            grantedNotOpenTicks = 0
            noDeviceTicks = 0
            // The failure budget is only refilled by frames that flowed steadily for 3 s.
            if (failures != 0 && flowingSinceMs != 0L && now - flowingSinceMs >= STABLE_FLOW_MS) {
                UsbDebugLog.ev("stable-flow", "frames flowing >= ${STABLE_FLOW_MS}ms: failures $failures -> 0")
                failures = 0
            }
            UsbDebugLog.d("tick: camera open framesFlowing=$framesFlowing uiState=$uiState") // TEMP-ClavoUSB
            showWebcamState(WebcamUiState.OPEN)
            return
        }
        // null = libausbc has not registered its USB client yet; say nothing until it has.
        val devices = try { getDeviceList() } catch (e: Exception) { null }
        if (devices == null) {
            UsbDebugLog.d("tick: getDeviceList()=null (libausbc not ready) uiState=$uiState") // TEMP-ClavoUSB
            return
        }
        LatencyRecorder.activityOnChange("Webcam", "dev", if (devices.isEmpty()) "0" else "1", if (devices.isEmpty()) "webcam_dicabut" else "webcam_terdeteksi", skipInitial = devices.isEmpty()) // TEMP-ClavoLat
        if (devices.isEmpty()) {
            UsbDebugLog.d("tick: devices=0 opened=false uiState=$uiState deniedTicks=$deniedTicks") // TEMP-ClavoUSB
            deniedTicks = 0
            grantedNotOpenTicks = 0
            connStates.clear()
            failures = 0 // unplugged: the next plug-in starts with a fresh budget
            nextRetryAtMs = 0L
            // "No webcam" only after two consecutive ticks without device and without frames.
            noDeviceTicks++
            showWebcamState(if (noDeviceTicks >= 2) WebcamUiState.NO_DEVICE else WebcamUiState.CHECKING)
            return
        }
        noDeviceTicks = 0

        val ctx = context ?: return
        val usbManager = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val granted = devices.firstOrNull { usbManager.hasPermission(it) }
        LatencyRecorder.activityOnChange("Webcam", "perm", if (granted != null) "diberikan" else "belum_ada", "izin_usb", if (granted != null) "diberikan" else "belum_ada", skipInitial = granted == null) // TEMP-ClavoLat
        UsbDebugLog.d( // TEMP-ClavoUSB
            "tick: devices=${devices.size} hasPermission=${granted != null} opened=false " +
                "uiState=$uiState deniedTicks=$deniedTicks grantedNotOpenTicks=$grantedNotOpenTicks " +
                "failures=$failures conn=${granted?.let { connStates[it.deviceName] }}"
        )

        if (granted != null) {
            deniedTicks = 0
            if (uiState == WebcamUiState.OPEN_FAILED) return // keep until the user taps "Coba lagi"
            val name = granted.deviceName
            currentDeviceName = name
            when (connStates[name] ?: ConnState.IDLE) {
                ConnState.CONNECTING -> {
                    if (now - connectStartMs > CONNECT_TIMEOUT_MS) {
                        UsbDebugLog.ev("connect-result", "TIMEOUT after ${now - connectStartMs}ms device=$name")
                        connStates[name] = ConnState.IDLE
                        closeCurrentCamera()
                        registerFailure("timeout")
                    }
                    // else: a connect is in flight; never start a second one
                }
                ConnState.OPEN -> Unit // the OPENED callback is the authority; frames follow
                ConnState.IDLE -> {
                    if (now < nextRetryAtMs) {
                        showWebcamState(WebcamUiState.RECONNECTING)
                        return
                    }
                    // Fresh plug-in: libausbc connects by itself first; give it two ticks.
                    if (failures == 0 && ++grantedNotOpenTicks < 3) {
                        showWebcamState(WebcamUiState.CHECKING)
                        return
                    }
                    grantedNotOpenTicks = 0
                    startConnect(granted) // permission is held: connect directly, no requestPermission
                }
            }
            return
        }
        // A webcam is plugged in, there is no USB permission and we are resumed (so the system
        // permission dialog is not on screen): the user dismissed or denied it. Require two
        // consecutive ticks so a dialog that is about to appear is not reported as a denial.
        grantedNotOpenTicks = 0
        connStates.clear() // no permission: no connect can be in flight
        deniedTicks++
        if (deniedTicks >= 2) showWebcamState(WebcamUiState.PERMISSION_DENIED)
    }

    private fun retryWebcam() {
        deniedTicks = 0
        grantedNotOpenTicks = 0
        failures = 0
        nextRetryAtMs = 0L
        val device = try { getDeviceList()?.firstOrNull() } catch (e: Exception) { null }
        if (device == null) {
            showWebcamState(WebcamUiState.NO_DEVICE)
            return
        }
        closeCurrentCamera()
        connStates[device.deviceName] = ConnState.IDLE
        showWebcamState(WebcamUiState.CHECKING)
        val usbManager = context?.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbManager?.hasPermission(device) == true) {
            startConnect(device) // permission already held: no requestPermission
        } else {
            UsbDebugLog.d("requestPermission (retry button) VID=${device.vendorId} PID=${device.productId}") // TEMP-ClavoUSB
            requestPermission(device) // no permission yet: shows the system dialog
        }
    }

    private fun showWebcamState(state: WebcamUiState) {
        uiState = state
        if (state == WebcamUiState.PERMISSION_DENIED) com.google.mediapipe.examples.objectdetection.utils.LatencyRecorder.activityOnChange("Webcam", "perm", "ditolak", "izin_usb", "ditolak") // TEMP-ClavoLat
        val b = _binding ?: return
        val message: String? = when (state) {
            WebcamUiState.NO_DEVICE ->
                "Webcam belum terdeteksi. Colok webcam USB (OTG) lalu izinkan akses USB."
            WebcamUiState.PERMISSION_DENIED ->
                "Izin USB untuk webcam ditolak, jadi webcam tidak bisa dibuka. " +
                    "Tekan \"Coba lagi\" lalu pilih Izinkan."
            WebcamUiState.OPEN_FAILED ->
                "Webcam gagal tersambung setelah $MAX_FAILURES percobaan" +
                    (lastErrorMessage?.let { " ($it)" } ?: "") +
                    ". Periksa kabel/adaptor OTG lalu tekan \"Coba lagi\"."
            WebcamUiState.RECONNECTING ->
                "Menyambung ulang... (percobaan ${failures + 1} dari $MAX_FAILURES)"
            WebcamUiState.ERROR ->
                "Webcam bermasalah: ${lastErrorMessage ?: "kesalahan tidak diketahui"}. " +
                    "Periksa kabel/adaptor OTG lalu tekan \"Coba lagi\"."
            WebcamUiState.CHECKING, WebcamUiState.OPEN -> null
        }
        b.webcamStatusPanel.visibility = if (message == null) View.GONE else View.VISIBLE
        if (message != null) b.webcamStatusText.text = message
        b.webcamRetryButton.visibility =
            if (state == WebcamUiState.PERMISSION_DENIED || state == WebcamUiState.OPEN_FAILED ||
                state == WebcamUiState.ERROR
            ) View.VISIBLE
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
            com.google.mediapipe.examples.objectdetection.utils.LatencyRecorder.setModel("Webcam", initial.displayName) // TEMP-ClavoLat

            detectionJob?.cancel()
            if (isDetectorReady) objectDetectorHelper.release() // queued close; the new create queues behind it

            objectDetectorHelper = withContext(Dispatchers.IO) {
                ObjectDetectorHelper(
                    context = ctx,
                    threshold = DetectionPrefs.getThreshold(ctx, DetectionPrefs.MODE_WEBCAM),
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
        com.google.mediapipe.examples.objectdetection.utils.LatencyRecorder.activityOnChange("Webcam", "det", "on", "deteksi_mulai") // TEMP-ClavoLat
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
            com.google.mediapipe.examples.objectdetection.utils.LatencyRecorder.frameReceived("Webcam") // TEMP-ClavoLat
            val frame = b.textureView.getBitmap() ?: return
            val rot = webcamRotation
            // Rotate so the detector sees exactly what the user sees (preview is rotated the same way).
            val rotated = if (rot == 0) frame else Bitmap.createBitmap(
                frame, 0, 0, frame.width, frame.height, Matrix().apply { postRotate(rot.toFloat()) }, true
            )
            val sq = FrameMapping.centerSquare(rotated.width, rotated.height)
            val cropped = Bitmap.createBitmap(rotated, sq.x, sq.y, sq.side, sq.side)
            val input = Bitmap.createScaledBitmap(
                cropped, FrameMapping.MODEL_INPUT_SIZE, FrameMapping.MODEL_INPUT_SIZE, true
            )
            lastFrameW = frame.width
            lastFrameH = frame.height
            lastFrameRot = rot
            com.google.mediapipe.examples.objectdetection.utils.ClavoDetLog.frame("Webcam", frame.width, frame.height, rot, "manualRot=$rot rotated=${rotated.width}x${rotated.height} crop=${sq.side} input=${input.width}x${input.height}") // TEMP-ClavoDet
            // Rotation is already baked into the bitmap, so the detector gets 0.
            objectDetectorHelper.detectFromBitmap(input, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error detectFromWebcam", e)
        }
    }

    // ==================== UI & Model Spinner ====================

    // Keeps the TextureView rotation/scale in step with the chosen rotation and its container.
    private fun setupRotationLayout() {
        val b = _binding ?: return
        val listener = OnLayoutChangeListener { _, l, t, r, bt, ol, ot, or2, ob ->
            if (r - l != or2 - ol || bt - t != ob - ot) applyRotationTransform()
        }
        b.textureView.addOnLayoutChangeListener(listener)
        b.webcamContainer.addOnLayoutChangeListener(listener)
        applyRotationTransform()
    }

    // Rotates the preview clockwise by webcamRotation and, for 90/270, shrinks it so the rotated
    // image still fits inside the container. The overlay reads this same transform.
    private fun applyRotationTransform() {
        val b = _binding ?: return
        val tv = b.textureView
        val c = b.webcamContainer
        val rot = webcamRotation
        var s = 1f
        if (rot % 180 != 0 && tv.width > 0 && tv.height > 0 && c.width > 0 && c.height > 0) {
            s = minOf(c.width / tv.height.toFloat(), c.height / tv.width.toFloat())
        }
        tv.rotation = rot.toFloat()
        tv.scaleX = s
        tv.scaleY = s
    }

    // Frame (TextureView-local pixels) -> overlay coordinates: the TextureView's own transform
    // (rotation + scale about its centre), its position in the container, and the container's
    // offset to the overlay.
    private fun frameToOverlayMatrix(): Matrix? {
        val b = _binding ?: return null
        val tv = b.textureView
        val m = Matrix(tv.matrix)
        m.postTranslate(tv.left.toFloat(), tv.top.toFloat())
        val a = IntArray(2)
        val o = IntArray(2)
        b.webcamContainer.getLocationOnScreen(a)
        b.overlay.getLocationOnScreen(o)
        m.postTranslate((a[0] - o[0]).toFloat(), (a[1] - o[1]).toFloat())
        return m
    }

    // Wires the shared model picker; models are bound once they are loaded.
    private fun initBottomSheetControls() {
        val sheet = binding.bottomSheetLayout
        modelSpinnerController = ModelSpinnerController(
            requireContext().applicationContext,
            sheet.spinnerModel,
            sheet.modelStatus
        ) { model ->
            com.google.mediapipe.examples.objectdetection.utils.LatencyRecorder.setModel("Webcam", model.displayName) // TEMP-ClavoLat
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
        initThresholdControl(sheet)
        initRotationControl(sheet)
    }

    private fun thresholdText(v: Float) = String.format(java.util.Locale.US, "Ambang skor: %.2f", v)

    private fun initThresholdControl(sheet: InfoBottomSheetBinding) {
        val ctx = requireContext().applicationContext
        val saved = DetectionPrefs.getThreshold(ctx, DetectionPrefs.MODE_WEBCAM)
        sheet.thresholdSeekbar.max = ThresholdScale.STEPS
        sheet.thresholdSeekbar.progress = ThresholdScale.toProgress(saved)
        sheet.thresholdLabel.text = thresholdText(saved)
        sheet.thresholdSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                sheet.thresholdLabel.text = thresholdText(ThresholdScale.fromProgress(progress))
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}

            override fun onStopTrackingTouch(bar: SeekBar?) {
                applyThreshold(ThresholdScale.fromProgress(bar?.progress ?: return))
            }
        })
    }

    // Saves the threshold and rebuilds the detector with it.
    private fun applyThreshold(value: Float) {
        DetectionPrefs.setThreshold(requireContext().applicationContext, DetectionPrefs.MODE_WEBCAM, value)
        Log.i("ClavoDet", "[Webcam] scoreThreshold set to $value") // TEMP-ClavoDet
        if (!isDetectorReady) return
        detectionJob?.cancel()
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO + NonCancellable) {
                objectDetectorHelper.updateThreshold(value) // one queued step on the shared queue
            }
            _binding?.overlay?.clear()
            startRealTimeDetection()
        }
    }

    // Rotation state is read in onViewCreated; the "Putar" button lives in the header
    // (MainActivity) and calls rotateNext().
    private fun initRotationControl(sheet: InfoBottomSheetBinding) {
        Log.i("ClavoDet", "[Webcam] manual rotation=$webcamRotation") // TEMP-ClavoDet
        com.google.mediapipe.examples.objectdetection.utils.LatencyRecorder.setRotation("Webcam", webcamRotation) // TEMP-ClavoLat
    }

    // Called by the header button: 0 -> 90 -> 180 -> 270 -> 0. Saved per mode.
    override fun rotateNext(): Int {
        val ctx = context?.applicationContext ?: return webcamRotation
        val rot = FrameMapping.nextRotation(webcamRotation)
        webcamRotation = rot
        DetectionPrefs.setWebcamRotation(ctx, rot)
        Log.i("ClavoDet", "[Webcam] manual rotation=$rot") // TEMP-ClavoDet
        com.google.mediapipe.examples.objectdetection.utils.LatencyRecorder.setRotation("Webcam", rot) // TEMP-ClavoLat
        applyRotationTransform()
        _binding?.overlay?.clear()
        return rot
    }

    // ==================== Detector Listener ====================

    override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle) {
        if (isDetectorReady) com.google.mediapipe.examples.objectdetection.utils.ClavoDetLog.result("Webcam", resultBundle, objectDetectorHelper.threshold) // TEMP-ClavoDet
        com.google.mediapipe.examples.objectdetection.utils.LatencyRecorder.resultDelivered("Webcam", resultBundle) // TEMP-ClavoLat
        activity?.runOnUiThread {
            // The view may already be destroyed when this runs; never touch binding via !!.
            val b = _binding ?: return@runOnUiThread
            if (!isAdded || view == null) return@runOnUiThread

            val result = resultBundle.results.firstOrNull()
            val detections = result?.detections() ?: emptyList()
            val matrix = frameToOverlayMatrix()
            // Drop results made with another rotation than the one now on screen.
            if (result != null && detections.isNotEmpty() && matrix != null &&
                lastFrameW > 0 && lastFrameH > 0 && lastFrameRot == webcamRotation
            ) {
                b.overlay.setResults(result, lastFrameW, lastFrameH, lastFrameRot, matrix)
            } else {
                b.overlay.clear()
            }
            b.overlay.invalidate()
        }
    }

    override fun onError(error: String, errorCode: Int) {
        com.google.mediapipe.examples.objectdetection.utils.LatencyRecorder.activity("Webcam", "error", error.take(80)) // TEMP-ClavoLat
        activity?.runOnUiThread {
            if (!isAdded) return@runOnUiThread
            context?.let { Toast.makeText(it, error, Toast.LENGTH_SHORT).show() }
        }
    }

    // ==================== Lifecycle ====================

    // TEMP-ClavoUSB: onResume/onPause exist only for logging
    override fun onResume() {
        super.onResume()
        UsbDebugLog.d("WebcamFragment.onResume")
    }

    override fun onPause() {
        UsbDebugLog.d("WebcamFragment.onPause")
        super.onPause()
    }

    override fun onDestroyView() {
        UsbDebugLog.d("WebcamFragment.onDestroyView") // TEMP-ClavoUSB
        com.google.mediapipe.examples.objectdetection.utils.LatencyRecorder.activityOnChange("Webcam", "det", "off", "deteksi_berhenti") // TEMP-ClavoLat
        viewAlive = false
        mainHandler.removeCallbacksAndMessages(null)
        connStates.clear()
        usbLogReceiver?.let { // TEMP-ClavoUSB
            try { context?.applicationContext?.unregisterReceiver(it) } catch (e: Exception) { }
            usbLogReceiver = null
        }
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