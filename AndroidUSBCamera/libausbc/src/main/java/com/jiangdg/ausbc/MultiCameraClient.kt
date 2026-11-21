// Modified 2026-11-21 by Absar Rakha Zarfan (Apache-2.0): single-connect guard, HandlerThread leak fix, session ids, off-main close, no surface-size wait.
package com.jiangdg.ausbc

import android.content.Context
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.os.*
import android.view.Surface
import android.view.View
import com.jiangdg.ausbc.callback.*
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.camera.bean.PreviewSize
import com.jiangdg.ausbc.encode.AACEncodeProcessor
import com.jiangdg.ausbc.encode.AbstractProcessor
import com.jiangdg.ausbc.encode.H264EncodeProcessor
import com.jiangdg.ausbc.encode.audio.AudioStrategySystem
import com.jiangdg.ausbc.encode.audio.AudioStrategyUAC
import com.jiangdg.ausbc.encode.audio.IAudioStrategy
import com.jiangdg.ausbc.encode.bean.RawData
import com.jiangdg.ausbc.encode.muxer.Mp4Muxer
import com.jiangdg.ausbc.render.RenderManager
import com.jiangdg.ausbc.render.effect.AbstractEffect
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.utils.CameraUtils
import com.jiangdg.ausbc.utils.CameraUtils.isFilterDevice
import com.jiangdg.ausbc.utils.CameraUtils.isUsbCamera
import com.jiangdg.ausbc.utils.Logger
import com.jiangdg.ausbc.utils.OpenGLUtils
import com.jiangdg.ausbc.utils.SettableFuture
import com.jiangdg.ausbc.utils.Utils
import com.jiangdg.ausbc.widget.IAspectRatio
import com.jiangdg.usb.*
import com.jiangdg.usb.DeviceFilter
import com.jiangdg.uvc.UVCCamera
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Multi-road camera client
 *
 * @author Created by jiangdg on 2022/7/18
 *      Modified for v3.3.0 by jiangdg on 2023/1/15
 */
class MultiCameraClient(ctx: Context, callback: IDeviceConnectCallBack?) {
    private var mUsbMonitor: USBMonitor? = null
    private val mMainHandler by lazy {
        Handler(Looper.getMainLooper())
    }

    init {
        mUsbMonitor = USBMonitor(ctx, object : USBMonitor.OnDeviceConnectListener {
            /**
             * Called by receive usb device inserted broadcast
             *
             * @param device usb device info,see [UsbDevice]
             */
            override fun onAttach(device: UsbDevice?) {
                if (Utils.debugCamera) {
                    Logger.i(TAG, "attach device name/pid/vid:${device?.deviceName}&${device?.productId}&${device?.vendorId} ")
                }
                device ?: return
                if (!isUsbCamera(device) && !isFilterDevice(ctx, device)) {
                    return
                }
                mMainHandler.post {
                    callback?.onAttachDev(device)
                }
            }

            /**
             * Called by receive usb device pulled out broadcast
             *
             * @param device usb device info,see [UsbDevice]
             */
            override fun onDetach(device: UsbDevice?) {
                if (Utils.debugCamera) {
                    Logger.i(TAG, "detach device name/pid/vid:${device?.deviceName}&${device?.productId}&${device?.vendorId} ")
                }
                device ?: return
                if (!isUsbCamera(device) && !isFilterDevice(ctx, device)) {
                    return
                }
                mMainHandler.post {
                    callback?.onDetachDec(device)
                }
            }

            /**
             * Called by granted permission
             *
             * @param device usb device info,see [UsbDevice]
             */
            override fun onConnect(
                device: UsbDevice?,
                ctrlBlock: USBMonitor.UsbControlBlock?,
                createNew: Boolean
            ) {
                if (Utils.debugCamera) {
                    Logger.i(TAG, "connect device name/pid/vid:${device?.deviceName}&${device?.productId}&${device?.vendorId} ")
                }
                device ?: return
                if (!isUsbCamera(device) && !isFilterDevice(ctx, device)) {
                    return
                }
                mMainHandler.post {
                    callback?.onConnectDev(device, ctrlBlock)
                }
            }

            /**
             * Called by dis unauthorized permission
             *
             * @param device usb device info,see [UsbDevice]
             */
            override fun onDisconnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                if (Utils.debugCamera) {
                    Logger.i(TAG, "disconnect device name/pid/vid:${device?.deviceName}&${device?.productId}&${device?.vendorId} ")
                }
                device ?: return
                if (!isUsbCamera(device) && !isFilterDevice(ctx, device)) {
                    return
                }
                mMainHandler.post {
                    callback?.onDisConnectDec(device, ctrlBlock)
                }
            }


            /**
             * Called by dis unauthorized permission or request permission exception
             *
             * @param device usb device info,see [UsbDevice]
             */
            override fun onCancel(device: UsbDevice?) {
                if (Utils.debugCamera) {
                    Logger.i(TAG, "cancel device name/pid/vid:${device?.deviceName}&${device?.productId}&${device?.vendorId} ")
                }
                device ?: return
                if (!isUsbCamera(device) && !isFilterDevice(ctx, device)) {
                    return
                }
                mMainHandler.post {
                    callback?.onCancelDev(device)
                }
            }
        })
    }

    /**
     * Register usb insert broadcast
     */
    fun register() {
        if (isMonitorRegistered()) {
            return
        }
        if (Utils.debugCamera) {
            Logger.i(TAG, "register...")
        }
        mUsbMonitor?.register()
    }

    /**
     * UnRegister usb insert broadcast
     */
    fun unRegister() {
        if (!isMonitorRegistered()) {
            return
        }
        if (Utils.debugCamera) {
            Logger.i(TAG, "unRegister...")
        }
        mUsbMonitor?.unregister()
    }

    /**
     * Request usb device permission
     *
     * @param device see [UsbDevice]
     * @return true ready to request permission
     */
    fun requestPermission(device: UsbDevice?): Boolean {
        if (!isMonitorRegistered()) {
            Logger.w(TAG, "Usb monitor haven't been registered.")
            return false
        }
        mUsbMonitor?.requestPermission(device)
        return true
    }

    /**
     * Uvc camera has permission
     *
     * @param device see [UsbDevice]
     * @return true permission granted
     */
    fun hasPermission(device: UsbDevice?) = mUsbMonitor?.hasPermission(device)

    /**
     * Connect a device that already has permission, without asking for permission again.
     * (Modified 2026-11-21 by Absar Rakha Zarfan)
     *
     * @return true if the connect was started
     */
    fun connectDevice(device: UsbDevice?): Boolean {
        if (!isMonitorRegistered()) {
            Logger.w(TAG, "Usb monitor haven't been registered.")
            return false
        }
        return mUsbMonitor?.connectGranted(device) == true
    }

    /**
     * Get device list
     *
     * @param list filter regular
     * @return filter device list
     */
    fun getDeviceList(list: List<DeviceFilter>? = null): MutableList<UsbDevice>? {
        list?.let {
            addDeviceFilters(it)
        }
        return mUsbMonitor?.deviceList
    }

    /**
     * Add device filters
     *
     * @param list filter regular
     */
    fun addDeviceFilters(list: List<DeviceFilter>) {
        mUsbMonitor?.addDeviceFilter(list)
    }

    /**
     * Remove device filters
     *
     * @param list filter regular
     */
    fun removeDeviceFilters(list: List<DeviceFilter>) {
        mUsbMonitor?.removeDeviceFilter(list)
    }

    /**
     * Destroy usb monitor engine
     */
    fun destroy() {
        mUsbMonitor?.destroy()
    }

    fun openDebug(debug: Boolean) {
        Utils.debugCamera = debug
        USBMonitor.DEBUG = debug
        UVCCamera.DEBUG = debug
    }

    private fun isMonitorRegistered() = mUsbMonitor?.isRegistered == true


    /**
     * Camera strategy super class
     *
     * @property ctx context
     * @property device see [UsbDevice]
     * @constructor Create camera by inherit it
     */
    abstract class ICamera(val ctx: Context, val device: UsbDevice): Handler.Callback,
        H264EncodeProcessor.OnEncodeReadyListener {
        private var isCaptureStream: Boolean = false
        private var mMediaMuxer: Mp4Muxer? = null
        private var mEncodeDataCallBack: IEncodeDataCallBack? = null
        private var mCameraThread: HandlerThread? = null
        private var mAudioProcess: AbstractProcessor? = null
        private var mVideoProcess: AbstractProcessor? = null
        private var mRenderManager: RenderManager?  = null
        private var mCameraView: Any? = null
        private var mCameraStateCallback: ICameraStateCallBack? = null
        private var mSizeChangedFuture: SettableFuture<Pair<Int, Int>>? = null
        protected var mContext = ctx
        // Modified 2026-11-21 by Absar Rakha Zarfan: one open per camera.
        // 0 = idle, 1 = opening, 2 = opened. An "opening" older than OPEN_STALE_MS counts as idle.
        @Volatile private var mOpenState = 0
        @Volatile private var mOpenStartMs = 0L
        // Session id: +1 on every accepted openCamera. Every state event is stamped with the id it
        // was posted under; the callback reads it through eventSessionId (valid inside the callback).
        @Volatile private var mSessionId = 0
        @Volatile var eventSessionId = 0
            private set
        // An invalidated camera (its client was torn down) never delivers state events again.
        @Volatile private var mInvalid = false
        private val mStoppingThreads = CopyOnWriteArrayList<HandlerThread>()
        protected var mCameraRequest: CameraRequest? = null
        protected var mCameraHandler: Handler? = null
        protected var isPreviewed: Boolean = false
        protected var isNeedGLESRender: Boolean = false
        protected var mCtrlBlock: USBMonitor.UsbControlBlock? = null
        protected var mPreviewDataCbList = CopyOnWriteArrayList<IPreviewDataCallBack>()
        private val mCacheEffectList by lazy {
            arrayListOf<AbstractEffect>()
        }
        protected val mMainHandler: Handler by lazy {
            Handler(Looper.getMainLooper())
        }
        protected val mNV21DataQueue: LinkedBlockingDeque<ByteArray> by lazy {
            LinkedBlockingDeque(MAX_NV21_DATA)
        }
        protected val mSaveImageExecutor: ExecutorService by lazy {
            Executors.newFixedThreadPool(10)
        }
        protected val mDateFormat by lazy {
            SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.getDefault())
        }
        protected val mCameraDir by lazy {
            "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}/Camera"
        }

        override fun handleMessage(msg: Message): Boolean {
            when (msg.what) {
                MSG_START_PREVIEW -> {
                    val previewWidth = mCameraRequest!!.previewWidth
                    val previewHeight = mCameraRequest!!.previewHeight
                    val renderMode = mCameraRequest!!.renderMode
                    val isRawPreviewData = mCameraRequest!!.isRawPreviewData
                    when (val cameraView = mCameraView) {
                        is IAspectRatio -> {
                            if (mCameraRequest!!.isAspectRatioShow) {
                                cameraView.setAspectRatio(previewWidth, previewHeight)
                            }
                            cameraView
                        }
                        else -> {
                            null
                        }
                    }.also { view->
                        isNeedGLESRender = isGLESRender(renderMode == CameraRequest.RenderMode.OPENGL)
                        if (! isNeedGLESRender && view != null) {
                            openCameraInternal(view)
                            return true
                        }
                        // use opengl render
                        // if surface is null, force off screen render whatever mode
                        // and use init preview size（measure size） for render size
                        // Modified 2026-11-21 by Absar Rakha Zarfan: no 2 s wait for a size event, and the
                        // render size is derived from the preview ratio (not from a stale measurement).
                        val measureSize = computeRenderSize(view as? View, previewWidth, previewHeight)
                        logRenderSize("preview-ratio", measureSize.first, measureSize.second, previewWidth, previewHeight)
                        mCameraRequest!!.renderMode = CameraRequest.RenderMode.OPENGL
                        val screenWidth = measureSize.first
                        val screenHeight = measureSize.second
                        val surface = view?.getSurface()
                        val previewCb = if (isRawPreviewData) {
                            null
                        } else {
                            mPreviewDataCbList
                        }
                        mRenderManager = RenderManager(ctx, previewWidth, previewHeight, previewCb)
                        mRenderManager?.startRenderScreen(screenWidth, screenHeight, surface, object : RenderManager.CameraSurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture?) {
                                if (surfaceTexture == null) {
                                    closeCamera()
                                    postStateEvent(ICameraStateCallBack.State.ERROR, "create camera surface failed")
                                    return
                                }
                                openCameraInternal(surfaceTexture)
                            }
                        })
                        mRenderManager?.setRotateType(mCameraRequest!!.defaultRotateType)
                        if (mCacheEffectList.isNotEmpty()) {
                            mCacheEffectList.forEach { effect ->
                                mRenderManager?.addRenderEffect(effect)
                            }
                            return@also
                        }
                        mCameraRequest?.defaultEffect?.apply {
                            mRenderManager?.addRenderEffect(this)
                        }
                    }
                }
                MSG_STOP_PREVIEW -> {
                    try {
                        mSizeChangedFuture?.cancel(true)
                        mSizeChangedFuture = null
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    closeCameraInternal()
                    mRenderManager?.getCacheEffectList()?.apply {
                        mCacheEffectList.clear()
                        mCacheEffectList.addAll(this)
                    }
                    mRenderManager?.stopRenderScreen()
                    mRenderManager = null
                }
                MSG_CAPTURE_IMAGE -> {
                    (msg.obj as Pair<*, *>).apply {
                        val path = first as? String
                        val cb = second as ICaptureCallBack
                        if (isNeedGLESRender && !mCameraRequest!!.isCaptureRawImage) {
                            mRenderManager?.saveImage(cb, path)
                            return@apply
                        }
                        captureImageInternal(path, second as ICaptureCallBack)
                    }
                }
                MSG_CAPTURE_VIDEO_START -> {
                    (msg.obj as Triple<*, *, *>).apply {
                        captureVideoStartInternal(first as? String, second as Long, third as ICaptureCallBack)
                    }
                }
                MSG_CAPTURE_VIDEO_STOP -> {
                    captureVideoStopInternal()
                }
                MSG_CAPTURE_STREAM_START -> {
                    isCaptureStream = true
                    captureStreamStartInternal()
                }
                MSG_CAPTURE_STREAM_STOP -> {
                    isCaptureStream = false
                    // if recording, cancel it
                    if (isRecording()) {
                        return true
                    }
                    captureStreamStopInternal()
                }
            }
            return true
        }

        protected abstract fun <T> openCameraInternal(cameraView: T)
        protected abstract fun closeCameraInternal()
        protected abstract fun captureImageInternal(savePath: String?, callback: ICaptureCallBack)

        protected open fun getAudioStrategy(): IAudioStrategy? {
            return when(mCameraRequest?.audioSource) {
                CameraRequest.AudioSource.SOURCE_AUTO -> {
                    if (isMicSupported(device) && mCtrlBlock!=null) {
                        if (Utils.debugCamera) {
                            Logger.i(TAG, "Audio record by using device internal mic")
                        }
                        AudioStrategyUAC(mCtrlBlock!!)
                    } else {
                        if (Utils.debugCamera) {
                            Logger.i(TAG, "Audio record by using system mic")
                        }
                        AudioStrategySystem()
                    }
                }
                CameraRequest.AudioSource.SOURCE_DEV_MIC -> {
                    if (isMicSupported(device) && mCtrlBlock!=null) {
                        if (Utils.debugCamera) {
                            Logger.i(TAG, "Audio record by using device internal mic")
                        }
                        return AudioStrategyUAC(mCtrlBlock!!)
                    }
                    return null
                }
                CameraRequest.AudioSource.SOURCE_SYS_MIC -> {
                    if (Utils.debugCamera) {
                        Logger.i(TAG, "Audio record by using system mic")
                    }
                    AudioStrategySystem()
                }
                else -> {
                    null
                }
            }
        }

        /**
         * should use opengl, recommend
         *
         * @return default depend on device opengl version, >=2.0 is true
         */
        private fun isGLESRender(isGlesRenderOpen: Boolean): Boolean =isGlesRenderOpen && OpenGLUtils.isGlEsSupported(ctx)

        /**
         * Init encode processor
         *
         * @param previewWidth camera opened preview width
         * @param previewHeight camera opened preview height
         */
        protected fun initEncodeProcessor(previewWidth: Int, previewHeight: Int) {
            releaseEncodeProcessor()
            // create audio process
            getAudioStrategy()?.let { audio->
                AACEncodeProcessor(audio)
            }?.also { processor ->
                mAudioProcess = processor
            }
            // create video process
            mContext.resources.configuration.orientation.let { orientation ->
                orientation == Configuration.ORIENTATION_PORTRAIT
            }.also { isPortrait ->
                mVideoProcess = H264EncodeProcessor(previewWidth, previewHeight, isNeedGLESRender, isPortrait)
            }
        }

        /**
         * Release encode processor
         */
        protected fun releaseEncodeProcessor() {
            try {
                mMediaMuxer?.release()
            } catch (e: Exception) {
                e.printStackTrace()
                Logger.e(TAG, "release muxer failed, err is ${e.localizedMessage}")
            }
            mVideoProcess?.stopEncode()
            mAudioProcess?.stopEncode()
            mVideoProcess = null
            mMediaMuxer = null
            mAudioProcess = null
            isCaptureStream = false
        }

        /**
         * Put video data
         *
         * @param data NV21 raw data
         */
        protected fun putVideoData(data: ByteArray) {
            mVideoProcess?.putRawData(RawData(data, data.size))
        }

        /**
         * Start rec mp3
         *
         * @param mp3Path  mp3 save path
         * @param callBack record status, see [ICaptureCallBack]
         */
        fun captureAudioStart(callBack: ICaptureCallBack, mp3Path: String?=null) {
            if (! CameraUtils.hasAudioPermission(mContext)) {
                callBack.onError("Has no audio permission")
                return
            }
            if (! CameraUtils.hasStoragePermission(mContext)) {
                callBack.onError("Has no storage permission")
                return
            }
            val path = if (mp3Path.isNullOrEmpty()) {
                "${mContext.getExternalFilesDir(null)?.path}/${System.currentTimeMillis()}.mp3"
            } else {
                mp3Path
            }
            (mAudioProcess as? AACEncodeProcessor)?.recordMp3Start(path, callBack)
        }

        /**
         * Stop rec mp3
         */
        fun captureAudioStop() {
            (mAudioProcess as? AACEncodeProcessor)?.recordMp3Stop()
        }

        /**
         * Start play mic
         *
         * @param callBack play mic status in real-time, see [IPlayCallBack]
         */
        fun startPlayMic(callBack: IPlayCallBack?) {
            if (! CameraUtils.hasAudioPermission(mContext)) {
                callBack?.onError("Has no audio permission")
                return
            }
            (mAudioProcess as? AACEncodeProcessor)?.playAudioStart(callBack)
        }

        /**
         * Stop play mic
         */
        fun stopPlayMic() {
            (mAudioProcess as? AACEncodeProcessor)?.playAudioStop()
        }

        /**
         * Rotate camera render angle
         *
         * @param type rotate angle, null means rotating nothing
         * see [RotateType.ANGLE_90], [RotateType.ANGLE_270],...etc.
         */
        fun setRotateType(type: RotateType?) {
            mRenderManager?.setRotateType(type)
        }

        /**
         * Set render size
         *
         * @param width surface width
         * @param height surface height
         */
        fun setRenderSize(width: Int, height: Int) {
            mSizeChangedFuture?.set(Pair(width, height))
            // Modified 2026-11-21 by Absar Rakha Zarfan: the view's real surface size reaches the
            // renderer (it used to be dropped), so a relayout never leaves a stale viewport.
            if (width > 0 && height > 0 && mRenderManager != null) {
                logRenderSize("surface-changed", width, height, mCameraRequest?.previewWidth ?: 0, mCameraRequest?.previewHeight ?: 0)
                mRenderManager?.setRenderSize(width, height)
            }
        }

        /**
         * Modified 2026-11-21 by Absar Rakha Zarfan.
         * Render size = view fitted into its container at the preview ratio (same rule as
         * AspectRatioTextureView.onMeasure), e.g. 1080x810 for 640x480, 1080x608 for 1280x720.
         */
        protected fun computeRenderSize(view: View?, pw: Int, ph: Int): Pair<Int, Int> {
            val ratio = if (pw > 0 && ph > 0) pw.toDouble() / ph else 4.0 / 3.0
            val parent = view?.parent as? View
            val cw = parent?.width ?: 0
            val ch = parent?.height ?: 0
            if (cw > 0 && ch > 0) {
                lastContainerW = cw
                lastContainerH = ch
            }
            val w = if (cw > 0) cw else if (lastContainerW > 0) lastContainerW else if (lastSurfaceW > 0) lastSurfaceW else 1080
            val h = if (ch > 0) ch else if (lastContainerH > 0) lastContainerH else 0
            val out = if (h > 0 && ratio <= w.toDouble() / h) {
                Pair((h * ratio).toInt(), h)
            } else {
                Pair(w, (w / ratio).toInt())
            }
            lastSurfaceW = out.first
            lastSurfaceH = out.second
            return out
        }

        /**
         * Modified 2026-11-21 by Absar Rakha Zarfan: after a successful open, adapt view ratio and
         * render size to the preview size that was really accepted, without closing the camera.
         */
        protected fun applyAcceptedPreviewSize(pw: Int, ph: Int, requestedW: Int, requestedH: Int) {
            val view = mCameraView
            if (view is IAspectRatio && mCameraRequest?.isAspectRatioShow == true) view.setAspectRatio(pw, ph)
            if (!isNeedGLESRender || mRenderManager == null) return
            val size = computeRenderSize(view as? View, pw, ph)
            val changed = pw != requestedW || ph != requestedH
            logRenderSize(if (changed) "preview-changed" else "preview-ok", size.first, size.second, pw, ph)
            mRenderManager?.setRenderSize(size.first, size.second)
        }

        private fun logRenderSize(source: String, w: Int, h: Int, pw: Int, ph: Int) {
            android.util.Log.i("ClavoUSB", "render-size source=$source size=${w}x$h preview=${pw}x$ph device=${device.deviceName}")
            if (w > 0 && h > 0 && pw > 0 && ph > 0) {
                val rr = w.toDouble() / h
                val pr = pw.toDouble() / ph
                if (Math.abs(rr / pr - 1.0) > 0.02) {
                    android.util.Log.w("ClavoUSB", "render-size-warning ratio render=${"%.3f".format(rr)} preview=${"%.3f".format(pr)} differ>2% source=$source size=${w}x$h preview=${pw}x$ph")
                }
            }
        }

        /**
         * Add render effect.There is only one setting in the same category
         * <p>
         * The default effects:
         * @see [com.jiangdg.ausbc.render.effect.EffectBlackWhite]
         * @see [com.jiangdg.ausbc.render.effect.EffectZoom]
         * @see [com.jiangdg.ausbc.render.effect.EffectSoul]
         * <p>
         * Of course, you can also realize a custom effect by extending from [AbstractEffect]
         *
         * @param effect a effect
         */
        fun addRenderEffect(effect: AbstractEffect) {
            mRenderManager?.addRenderEffect(effect)
        }

        /**
         * Remove render effect
         *
         * @param effect a effect, extending from [AbstractEffect]
         */
        fun removeRenderEffect(effect: AbstractEffect) {
            val defaultId =  mCameraRequest?.defaultEffect?.getId()
            if (effect.getId() == defaultId) {
                mCameraRequest?.defaultEffect = null
            }
            mRenderManager?.removeRenderEffect(effect)
        }

        /**
         * Get default effect
         *
         * @return  default effect, extending from [AbstractEffect]
         */
        fun getDefaultEffect() = mCameraRequest?.defaultEffect

        /**
         * Update render effect
         *
         * @param classifyId effect classify id
         * @param effect new effect, null means set none
         */
        fun updateRenderEffect(classifyId: Int, effect: AbstractEffect?) {
            mRenderManager?.getCacheEffectList()?.find {
                it.getClassifyId() == classifyId
            }?.also {
                removeRenderEffect(it)
            }
            effect ?: return
            addRenderEffect(effect)
        }

        /**
         * Post camera state to main thread
         *
         * @param state see [ICameraStateCallBack.State]
         * @param msg detail msg
         */
        protected fun postStateEvent(state: ICameraStateCallBack.State, msg: String? = null) {
            when (state) {
                ICameraStateCallBack.State.OPENED -> mOpenState = 2
                ICameraStateCallBack.State.ERROR, ICameraStateCallBack.State.CLOSED -> mOpenState = 0
            }
            if (mInvalid) {
                Logger.w(TAG, "state event $state dropped: camera was invalidated")
                return
            }
            val sid = mSessionId
            mMainHandler.post {
                if (mInvalid) return@post
                eventSessionId = sid
                mCameraStateCallback?.onCameraState(this, state, msg)
            }
        }

        /** Latest session id (the one of the most recent accepted openCamera). */
        fun getLatestSessionId(): Int = mSessionId

        /** The owner (client) of this camera was torn down: no more state events, no more opens. */
        fun invalidate() {
            mInvalid = true
        }

        /**
         * Waits (at most [timeoutMs] in total) for camera threads that were told to stop to finish.
         * Never call on the main thread for long: callers pass <= 1000 ms and use a worker thread.
         */
        fun awaitStopped(timeoutMs: Long) {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            for (t in mStoppingThreads) {
                if (t === Thread.currentThread()) continue
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) break
                try {
                    t.join(left)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            mStoppingThreads.removeAll { !it.isAlive }
        }

        /**
         * Set usb control block, when the uvc device was granted permission
         *
         * @param ctrlBlock see [USBMonitor.OnDeviceConnectListener]#onConnectedDev
         */
        fun setUsbControlBlock(ctrlBlock: USBMonitor.UsbControlBlock?) {
            this.mCtrlBlock = ctrlBlock
            // Before USBMonitor closes the USB connection (on its own background thread) the
            // stream must be stopped: stop the camera and wait for its thread, at most 1 s.
            ctrlBlock?.setPreCloseHook(Runnable {
                closeCamera()
                awaitStopped(1000)
            })
        }

        /**
         * Get usb device information
         *
         * @return see [UsbDevice]
         */
        fun getUsbDevice() = device

        /**
         * Is mic supported
         *
         * @return true camera support mic
         */
        fun isMicSupported(device: UsbDevice?) = CameraUtils.isCameraContainsMic(device)


        /**
         * Get all preview sizes
         *
         * @param aspectRatio aspect ratio
         * @return [PreviewSize] list of camera
         */
        abstract fun getAllPreviewSizes(aspectRatio: Double? = null): MutableList<PreviewSize>

        /**
         * Open camera
         *
         * @param cameraView render surface view，support Surface or SurfaceTexture
         *                      or SurfaceView or TextureView or GLSurfaceView
         * @param cameraRequest camera request
         */
        fun <T> openCamera(cameraView: T? = null, cameraRequest: CameraRequest? = null) {
            // Modified 2026-11-21 by Absar Rakha Zarfan: a second open of the same camera (for
            // example a duplicate connect callback) must not start a second UVC session.
            if (mInvalid) {
                Logger.w(TAG, "openCamera ignored: camera was invalidated")
                return
            }
            synchronized(this) {
                val now = SystemClock.elapsedRealtime()
                if (mOpenState == 2 ||
                    (mOpenState == 1 && now - mOpenStartMs < OPEN_STALE_MS)
                ) {
                    Logger.w(TAG, "openCamera ignored: already ${if (mOpenState == 2) "opened" else "opening"}")
                    return
                }
                mOpenState = 1
                mOpenStartMs = now
                mSessionId++
            }
            mCameraView = cameraView ?: mCameraView
            mCameraRequest = cameraRequest ?: getDefaultCameraRequest()
            // Modified 2026-11-21 by Absar Rakha Zarfan: stop a previous camera thread instead of
            // overwriting (and leaking) it.
            mCameraThread?.let { old ->
                mStoppingThreads.add(old)
                mCameraHandler?.obtainMessage(MSG_STOP_PREVIEW)?.sendToTarget()
                old.quitSafely()
                mCameraThread = null
                mCameraHandler = null
            }
            HandlerThread("camera-${System.currentTimeMillis()}").apply {
                start()
            }.let { thread ->
                this.mCameraThread = thread
                thread
            }.also {
                mCameraHandler = Handler(it.looper, this)
                mCameraHandler?.obtainMessage(MSG_START_PREVIEW)?.sendToTarget()
            }
        }

        /**
         * Close camera
         */
        @Synchronized
        fun closeCamera() {
            mOpenState = 0 // Modified 2026-11-21 by Absar Rakha Zarfan
            mCameraThread?.let { mStoppingThreads.add(it) }
            mCameraHandler?.obtainMessage(MSG_STOP_PREVIEW)?.sendToTarget()
            mCameraThread?.quitSafely()
            mCameraThread = null
            mCameraHandler = null
        }

        /**
         * check if camera opened
         *
         * @return camera open status, true or false
         */
        fun isCameraOpened() = isPreviewed

        /**
         * Get current camera request
         *
         * @return see [CameraRequest], can be null
         */
        fun getCameraRequest() = mCameraRequest

        /**
         * Capture image
         *
         * @param callBack capture a image status, see [ICaptureCallBack]
         * @param path image save path, default is DICM/Camera
         */
        fun captureImage(callBack: ICaptureCallBack, path: String? = null) {
            Pair(path, callBack).apply {
                mCameraHandler?.obtainMessage(MSG_CAPTURE_IMAGE, this)?.sendToTarget()
            }
        }

        /**
         * Capture video start
         *
         * @param callBack capture result callback, see [ICaptureCallBack]
         * @param path video save path, default is DICM/Camera
         * @param durationInSec video file auto divide duration is seconds
         */
        fun captureVideoStart(callBack: ICaptureCallBack, path: String? = null, durationInSec: Long = 0L) {
            Triple(path, durationInSec, callBack).apply {
                mCameraHandler?.obtainMessage(MSG_CAPTURE_VIDEO_START, this)?.sendToTarget()
            }
        }

        /**
         * Capture video stop
         */
        fun captureVideoStop() {
            mCameraHandler?.obtainMessage(MSG_CAPTURE_VIDEO_STOP)?.sendToTarget()
        }

        /**
         * Capture stream start
         *  Getting H.264 and AAC stream
         */
        fun captureStreamStart() {
            mCameraHandler?.obtainMessage(MSG_CAPTURE_STREAM_START)?.sendToTarget()
        }

        /**
         * Capture stream stop
         */
        fun captureStreamStop() {
            mCameraHandler?.obtainMessage(MSG_CAPTURE_STREAM_STOP)?.sendToTarget()
        }

        /**
         * Update resolution
         *
         * @param width camera preview width, see [PreviewSize]
         * @param height camera preview height, [PreviewSize]
         * @return result of operation
         */
        fun updateResolution(width: Int, height: Int) {
            if (mCameraRequest == null) {
                Logger.w(TAG, "updateResolution failed, please open camera first.")
                return
            }
            if (isStreaming() || isRecording()) {
                Logger.e(TAG, "updateResolution failed, video recording...")
                return
            }
            mCameraRequest?.apply {
                if (previewWidth == width && previewHeight == height) {
                    return@apply
                }
                Logger.i(TAG, "updateResolution: width = $width, height = $height")
                closeCamera()
                mMainHandler.postDelayed({
                    previewWidth = width
                    previewHeight = height
                    openCamera(mCameraView, mCameraRequest)
                }, 1000)
            }
        }

        /**
         * Set camera state call back
         *
         * @param callback camera be opened or closed
         */
        fun setCameraStateCallBack(callback: ICameraStateCallBack?) {
            this.mCameraStateCallback = callback
        }

        /**
         * set encode data call back
         *
         * @param callBack camera encoded data call back, see [IEncodeDataCallBack]
         */
        fun setEncodeDataCallBack(callBack: IEncodeDataCallBack?) {
            this.mEncodeDataCallBack = callBack
        }

        /**
         * Add preview data call back
         * @param callBack preview data call back
         */
        fun addPreviewDataCallBack(callBack: IPreviewDataCallBack) {
            if (mPreviewDataCbList.contains(callBack)) {
                return
            }
            mPreviewDataCbList.add(callBack)
        }

        /**
         * Remove preview data call back
         *
         * @param callBack preview data call back
         */
        fun removePreviewDataCallBack(callBack: IPreviewDataCallBack) {
            if (! mPreviewDataCbList.contains(callBack)) {
                return
            }
            mPreviewDataCbList.remove(callBack)
        }

        fun getSuitableSize(maxWidth: Int, maxHeight: Int): PreviewSize {
            val sizeList = getAllPreviewSizes()
            if (sizeList.isNullOrEmpty()) {
                return PreviewSize(DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT)
            }
            // find it
            sizeList.find {
                (it.width == maxWidth && it.height == maxHeight)
            }.also { size ->
                size ?: return@also
                return size
            }
            // find the same aspectRatio
            val aspectRatio = maxWidth.toFloat() / maxHeight
            sizeList.find {
                val w = it.width
                val h = it.height
                val ratio = w.toFloat() / h
                ratio == aspectRatio && w <= maxWidth && h <= maxHeight
            }.also { size ->
                size ?: return@also
                return size
            }
            // find the closest aspectRatio
            var minDistance: Int = maxWidth
            var closetSize = sizeList[0]
            sizeList.forEach { size ->
                if (minDistance >= abs((maxWidth - size.width))) {
                    minDistance = abs(maxWidth - size.width)
                    closetSize = size
                }
            }
            // use default
            sizeList.find {
                (it.width == DEFAULT_PREVIEW_WIDTH || it.height == DEFAULT_PREVIEW_HEIGHT)
            }.also { size ->
                size ?: return@also
                return size
            }
            return closetSize
        }

        fun isPreviewSizeSupported(previewSize: PreviewSize): Boolean {
            return getAllPreviewSizes().find {
                it.width == previewSize.width && it.height == previewSize.height
            } != null
        }

        /**
         * check video record status
         * Only muxer started means recording success
         */
        fun isRecording(): Boolean = mMediaMuxer?.isMuxerStarter() == true

        /**
         * check stream status
         * need video encoding and stream flag are true
         */
        fun isStreaming(): Boolean = isEncoding() && isCaptureStream

        private fun isEncoding(): Boolean = mVideoProcess?.isEncoding() == true

        private fun captureVideoStartInternal(path: String?, durationInSec: Long, callBack: ICaptureCallBack) {
            if (! isCameraOpened()) {
                Logger.e(TAG ,"capture video failed, camera not opened")
                return
            }
            if (isRecording()) {
                Logger.w(TAG, "capturing video already running")
                return
            }
            captureStreamStartInternal()
            Mp4Muxer(mContext, callBack, path, durationInSec, mAudioProcess==null).apply {
                mVideoProcess?.setMp4Muxer(this)
                mAudioProcess?.setMp4Muxer(this)
            }.also { muxer ->
                mMediaMuxer = muxer
            }
            Logger.i(TAG, "capturing video start")
        }

        private fun captureVideoStopInternal() {
            // if streaming, cancel it
            if (! isStreaming()) {
                captureStreamStopInternal()
            }
            try {
                mMediaMuxer?.release()
            } catch (e: Exception) {
                e.printStackTrace()
                Logger.e(TAG, "release muxer failed, err is ${e.localizedMessage}")
            } finally {
                mMediaMuxer = null
            }
            Logger.i(TAG, "capturing video stop")
        }

        private fun captureStreamStartInternal() {
            if (! isCameraOpened()) {
                Logger.e(TAG ,"capture stream failed, camera not opened")
                return
            }
            if (isEncoding()) {
                Logger.w(TAG, "capturing stream canceled, already running")
                return
            }
            (mVideoProcess as? H264EncodeProcessor)?.apply {
                if (mVideoProcess?.isEncoding() == true) {
                    return@apply
                }
                startEncode()
                setEncodeDataCallBack(mEncodeDataCallBack)
                setOnEncodeReadyListener(this@ICamera)
            }
            (mAudioProcess as? AACEncodeProcessor)?.apply {
                if (mAudioProcess?.isEncoding() == true) {
                    return@apply
                }
                startEncode()
                setEncodeDataCallBack(mEncodeDataCallBack)
            }
            Logger.i(TAG, "capturing stream start")
        }

        private fun captureStreamStopInternal() {
            mRenderManager?.stopRenderCodec()
            (mVideoProcess as? H264EncodeProcessor)?.apply {
                if (! isEncoding()) {
                    return@apply
                }
                stopEncode()
                setEncodeDataCallBack(null)
            }
            (mAudioProcess as? AACEncodeProcessor)?.apply {
                if (! isEncoding()) {
                    return@apply
                }
                stopEncode()
                setEncodeDataCallBack(null)
            }
            Logger.i(TAG, "capturing stream stop")
        }

        override fun onReady(surface: Surface?) {
            if (surface == null) {
                Logger.e(TAG, "start encode failed, input surface is null")
                return
            }
            mCameraRequest?.apply {
                mRenderManager?.startRenderCodec(surface, previewWidth, previewHeight)
            }
        }

        private fun getDefaultCameraRequest(): CameraRequest {
            return CameraRequest.Builder()
                .setPreviewWidth(1280)
                .setPreviewHeight(720)
                .create()
        }
    }

    companion object {
        private const val TAG = "MultiCameraClient"
        private const val MSG_START_PREVIEW = 0x01
        private const val OPEN_STALE_MS = 8000L // Modified 2026-11-21 by Absar Rakha Zarfan
        @Volatile private var lastSurfaceW = 0
        @Volatile private var lastSurfaceH = 0
        @Volatile private var lastContainerW = 0
        @Volatile private var lastContainerH = 0
        private const val MSG_STOP_PREVIEW = 0x02
        private const val MSG_CAPTURE_IMAGE = 0x03
        private const val MSG_CAPTURE_VIDEO_START = 0x04
        private const val MSG_CAPTURE_VIDEO_STOP = 0x05
        private const val MSG_CAPTURE_STREAM_START = 0x06
        private const val MSG_CAPTURE_STREAM_STOP = 0x07
        private const val DEFAULT_PREVIEW_WIDTH = 640
        private const val DEFAULT_PREVIEW_HEIGHT = 480
        const val MAX_NV21_DATA = 5
        const val CAPTURE_TIMES_OUT_SEC = 3L
    }
}