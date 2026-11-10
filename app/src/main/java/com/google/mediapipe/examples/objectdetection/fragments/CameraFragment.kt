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
package com.google.mediapipe.examples.objectdetection.fragments

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.mediapipe.examples.objectdetection.MainViewModel
import com.google.mediapipe.examples.objectdetection.ObjectDetectorHelper
import com.google.mediapipe.examples.objectdetection.R
import com.google.mediapipe.examples.objectdetection.databinding.FragmentCameraBinding
import com.google.mediapipe.examples.objectdetection.utils.ModelRepository
import com.google.mediapipe.examples.objectdetection.utils.ModelSpinnerController
import com.google.mediapipe.examples.objectdetection.utils.LatencyRecorder // TEMP-ClavoLat
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraFragment : Fragment(), ObjectDetectorHelper.DetectorListener {

    private val TAG = "ObjectDetection"

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding
        get() = _fragmentCameraBinding!!

    private lateinit var objectDetectorHelper: ObjectDetectorHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var initialModelPath: String = ""
    private var modelSpinnerController: ModelSpinnerController? = null

    /** Blocking ML operations are performed using this executor */
    private lateinit var backgroundExecutor: ExecutorService

    // Resumes camera setup and detector initialization when the app is foregrounded.
    override fun onResume() {
        super.onResume()
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(
                requireActivity(),
                R.id.fragment_container
            ).navigate(CameraFragmentDirections.actionCameraToPermissions())
        } else if (this::objectDetectorHelper.isInitialized &&
            this::backgroundExecutor.isInitialized && !backgroundExecutor.isShutdown
        ) {
            // Re-create the detector and re-attach the analyzer after onPause released them.
            com.google.mediapipe.examples.objectdetection.utils.LatencyRecorder.activityOnChange("Camera", "det", "on", "deteksi_mulai") // TEMP-ClavoLat
            val analyzer = imageAnalyzer
            if (analyzer != null) {
                backgroundExecutor.execute {
                    objectDetectorHelper.setupObjectDetector()
                    analyzer.setAnalyzer(
                        backgroundExecutor,
                        objectDetectorHelper::detectLivestreamFrame
                    )
                }
            }
        }
    }

    // Pauses the detection and releases detector resources to save power.
    override fun onPause() {
        super.onPause()
        // 1) Stop the frame producer first so no frame reaches a closed detector.
        com.google.mediapipe.examples.objectdetection.utils.LatencyRecorder.activityOnChange("Camera", "det", "off", "deteksi_berhenti") // TEMP-ClavoLat
        imageAnalyzer?.clearAnalyzer()

        if (this::objectDetectorHelper.isInitialized) {
            viewModel.setDelegate(objectDetectorHelper.currentDelegate)
            viewModel.setThreshold(objectDetectorHelper.threshold)
            viewModel.setMaxResults(objectDetectorHelper.maxResults)

            // 2) Close the detector on the executor, after any in-flight analyzer task finishes.
            if (this::backgroundExecutor.isInitialized && !backgroundExecutor.isShutdown) {
                backgroundExecutor.execute { objectDetectorHelper.clearObjectDetector() }
            } else {
                objectDetectorHelper.clearObjectDetector()
            }
        }
    }

    // Cleans up the view binding and shuts down the background executor.
    override fun onDestroyView() {
        _fragmentCameraBinding = null
        modelSpinnerController = null

        // Order matters: stop frames -> stop executor -> close detector.
        imageAnalyzer?.clearAnalyzer()
        cameraProvider?.unbindAll()
        imageAnalyzer = null
        preview = null
        camera = null

        if (this::backgroundExecutor.isInitialized) {
            backgroundExecutor.shutdown()
            try {
                backgroundExecutor.awaitTermination(2, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        if (this::objectDetectorHelper.isInitialized) {
            objectDetectorHelper.release()
        }

        super.onDestroyView()
    }

    // Inflates the fragment's view and initializes the view binding.
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding =
            FragmentCameraBinding.inflate(inflater, container, false)

        return fragmentCameraBinding.root
    }

    // Initializes the object detector, camera, and UI controls after the view is created.
    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        backgroundExecutor = Executors.newSingleThreadExecutor()

        initBottomSheetControls()

        // Runs on Main; heavy work is moved to IO inside. Cancelled automatically when the
        // view is destroyed.
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            try {
                initializeDetectorAndCamera()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize detector", e)
                context?.let {
                    Toast.makeText(it, "Gagal memuat: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        fragmentCameraBinding.overlay.setRunningMode(RunningMode.LIVE_STREAM)

        // Pengecekan Awal Orientasi Saat Fragment dibuat
//        val (bottomNav, toolbar) = getParentUIViews()
//        val orientation = resources.configuration.orientation
//
//        if (bottomNav != null && toolbar != null) {
//            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
//                bottomNav.visibility = View.GONE
//                toolbar.visibility = View.GONE
//            } else {
//                // Pastikan visibilitas default jika bukan landscape
//                bottomNav.visibility = View.VISIBLE
//                toolbar.visibility = View.VISIBLE
//            }
//        }

        // change rotation
//        val bottomNavigationView = requireActivity().findViewById<BottomNavigationView>(R.id.navigation) // Ganti ID jika berbeda
//        val toolbarView = requireActivity().findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar) // Ganti ID jika berbeda

        // Listener untuk mendeteksi perubahan layout, termasuk rotasi
//        view.viewTreeObserver.addOnGlobalLayoutListener {
//            context?.let { safeContext ->
//                val orientation = safeContext.resources.configuration.orientation
//
//                // Hanya jika views ditemukan
//                if (bottomNavigationView != null && toolbarView != null) {
//                    if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
//                        bottomNavigationView.visibility = View.GONE
//                        toolbarView.visibility = View.GONE
//                    } else {
//                        bottomNavigationView.visibility = View.VISIBLE
//                        toolbarView.visibility = View.VISIBLE
//                    }
//                }
//            }
//        }
    }

    private fun getParentUIViews(): Pair<View?, View?> {
        val activity = requireActivity()

        // Gunakan tipe View yang benar (Misal: BottomNavigationView dan Toolbar)
        val bottomNav = activity.findViewById<BottomNavigationView>(R.id.navigation)
        val toolbar = activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)

        // Kita mengembalikan View? dan View? karena kita hanya butuh View untuk visibility
        return Pair(bottomNav, toolbar)
    }

    // Must be called on Main. Every await is followed by a check that the view still exists.
    private suspend fun initializeDetectorAndCamera() {
        val ctx = context?.applicationContext ?: return
        if (_fragmentCameraBinding == null) return

        val available = withContext(Dispatchers.IO) { ModelRepository.listModels(ctx) }
        if (_fragmentCameraBinding == null) return

        val initial = ModelRepository.resolveInitialModel(ctx, available)
        modelSpinnerController?.bind(available, initial)
        if (initial == null) {
            Log.e(TAG, "No .tflite models found in assets/models/")
            return
        }
        initialModelPath = initial.assetPath
        com.google.mediapipe.examples.objectdetection.utils.LatencyRecorder.setModel("Camera", initial.displayName) // TEMP-ClavoLat

        // ViewModel is read here (Main, fragment attached), not from the IO thread.
        val threshold = viewModel.currentThreshold
        val delegate = viewModel.currentDelegate
        val maxResults = viewModel.currentMaxResults

        // NonCancellable: if the view dies mid-load we still get the helper back and release it.
        val helper = withContext(Dispatchers.IO + NonCancellable) {
            ObjectDetectorHelper(
                context = ctx,
                threshold = threshold,
                currentDelegate = delegate,
                modelPath = initial.assetPath,
                maxResults = maxResults,
                objectDetectorListener = this@CameraFragment,
                runningMode = RunningMode.LIVE_STREAM
            )
        }

        val b = _fragmentCameraBinding
        if (b == null) {
            helper.release()
            return
        }
        objectDetectorHelper = helper
        b.viewFinder.post { if (_fragmentCameraBinding != null) setUpCamera() }
    }

    // Wires the shared model picker; models are bound once they are loaded.
    private fun initBottomSheetControls() {
        val sheet = fragmentCameraBinding.bottomSheetLayout
        modelSpinnerController = ModelSpinnerController(
            requireContext().applicationContext,
            sheet.spinnerModel,
            sheet.modelStatus
        ) { model ->
            initialModelPath = model.assetPath
            com.google.mediapipe.examples.objectdetection.utils.LatencyRecorder.setModel("Camera", model.displayName) // TEMP-ClavoLat
            if (this::objectDetectorHelper.isInitialized &&
                this::backgroundExecutor.isInitialized &&
                !backgroundExecutor.isShutdown
            ) {
                backgroundExecutor.execute { objectDetectorHelper.changeModel(model.assetPath) }
                _fragmentCameraBinding?.overlay?.clear()
            }
        }
    }

    // Resets the object detector and clears the overlay view.
    private fun updateControlsUi() {
        backgroundExecutor.execute {
            objectDetectorHelper.clearObjectDetector()
            objectDetectorHelper.setupObjectDetector()
        }

        fragmentCameraBinding.overlay.clear()
    }

    // Initializes CameraX and prepares it for binding camera use cases.
    private fun setUpCamera() {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            },
            ContextCompat.getMainExecutor(requireContext())
        )
    }

    // Configures and binds the camera's preview and image analysis use cases.
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val cameraProvider =
            cameraProvider
                ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector =
            CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

        preview =
            Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .build()

        imageAnalyzer =
            ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(
                        backgroundExecutor,
                        objectDetectorHelper::detectLivestreamFrame
                    )
                }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
            com.google.mediapipe.examples.objectdetection.utils.LatencyRecorder.activity("Camera", "kamera_dibuka", "Camera") // TEMP-ClavoLat
            com.google.mediapipe.examples.objectdetection.utils.LatencyRecorder.activityOnChange("Camera", "det", "on", "deteksi_mulai") // TEMP-ClavoLat
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    // Handles configuration changes, such as screen rotation, to update the image analyzer.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation =
            fragmentCameraBinding.viewFinder.display.rotation

        try {
            val (bottomNav, toolbar) = getParentUIViews()

            if (bottomNav != null && toolbar != null) {
                if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    bottomNav.visibility = View.GONE
                    toolbar.visibility = View.GONE
                } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
                    bottomNav.visibility = View.VISIBLE
                    toolbar.visibility = View.VISIBLE
                }
            } else {
                Log.e(TAG, "FATAL: BottomNav atau Toolbar TIDAK DITEMUKAN di Activity!")
            }
        } catch (e: Exception) {
            // Ini akan menangkap ClassCastException atau NullPointerException lainnya.
            Log.e(TAG, "CRASH saat rotasi UI!", e)
        }
    }

    // A callback to receive and display the detection results from the ObjectDetectorHelper.
    override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle) {
        if (this::objectDetectorHelper.isInitialized) com.google.mediapipe.examples.objectdetection.utils.ClavoDetLog.result("Camera", resultBundle, objectDetectorHelper.threshold) // TEMP-ClavoDet
        com.google.mediapipe.examples.objectdetection.utils.LatencyRecorder.resultDelivered("Camera", resultBundle) // TEMP-ClavoLat
        activity?.runOnUiThread {
            if (_fragmentCameraBinding != null && isAdded) {
                Log.d(TAG, "onResults dipanggil dengan ${resultBundle.results.size} hasil.")

                if (resultBundle.results.isNotEmpty()) {
                    val detectionResult = resultBundle.results[0]

                    if (detectionResult.detections().isNotEmpty()) {
                        Log.d(TAG, "Objek terdeteksi: ${detectionResult.detections().size} buah.")
                        fragmentCameraBinding.overlay.setResults(
                            detectionResult,
                            resultBundle.inputImageHeight,
                            resultBundle.inputImageWidth,
                            resultBundle.inputImageRotation
                        )
                    } else {
                        Log.d(TAG, "Hasil ada, tapi tidak ada objek terdeteksi (detections list is empty).")
                        fragmentCameraBinding.overlay.clear()
                    }
                } else {
                    Log.d(TAG, "Daftar hasil utama kosong (results list is empty).")
                    fragmentCameraBinding.overlay.clear()
                }

                fragmentCameraBinding.overlay.invalidate()
            }
        }
    }

    // A callback to handle and display errors from the ObjectDetectorHelper.
    override fun onError(error: String, errorCode: Int) {
        com.google.mediapipe.examples.objectdetection.utils.LatencyRecorder.activity("Camera", "error", error.take(80)) // TEMP-ClavoLat
        activity?.runOnUiThread {
            if (_fragmentCameraBinding != null && isAdded) {
                Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
            }
        }
    }
}