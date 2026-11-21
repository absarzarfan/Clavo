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

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.mediapipe.examples.objectdetection.databinding.ActivityMainBinding
import com.google.mediapipe.examples.objectdetection.utils.DetectionPrefs
import com.google.mediapipe.examples.objectdetection.utils.RotatableScreen
import com.google.mediapipe.examples.objectdetection.utils.UsbDebugLog // TEMP-ClavoUSB
import com.google.mediapipe.tasks.vision.core.RunningMode

/**
 * Main entry point into our app. This app follows the single-activity pattern, and all
 * functionality is implemented in the form of fragments.
 */

class MainActivity : AppCompatActivity() {

    private lateinit var activityMainBinding: ActivityMainBinding

    private val viewModel: MainViewModel by viewModels()
    private lateinit var detectorHelper: ObjectDetectorHelper


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.google.mediapipe.examples.objectdetection.utils.GeoLog.active = intent?.getBooleanExtra("clavo_geo", false) ?: false // TEMP-ClavoGeo
        com.google.mediapipe.examples.objectdetection.utils.LatencyRecorder.init(applicationContext) // TEMP-ClavoLat
        if (savedInstanceState == null) com.google.mediapipe.examples.objectdetection.utils.LatencyRecorder.activity("App", "app_dibuka") // TEMP-ClavoLat
        UsbDebugLog.logProcessStart(applicationContext, intent, savedInstanceState != null) // TEMP-ClavoUSB
        if (savedInstanceState == null) UsbDebugLog.logSession(this) // TEMP-ClavoUSB
        activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(activityMainBinding.root)


        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as NavHostFragment
        val navController = navHostFragment.navController
        activityMainBinding.navigation.setupWithNavController(navController)
        activityMainBinding.navigation.setOnNavigationItemReselectedListener {
            // ignore the reselection
        }

        // Header "Putar" button: shown on Camera and Webcam only; its label always follows the
        // stored rotation of the active tab (also after a tab change or activity re-creation).
        navController.addOnDestinationChangedListener { _, _, _ -> refreshRotateButton() }
        activityMainBinding.btnRotateHeader.setOnClickListener {
            val screen = navHostFragment.childFragmentManager.primaryNavigationFragment as? RotatableScreen
            if (screen != null) {
                setRotateLabel(screen.rotateNext())
            }
        }
        refreshRotateButton()
    }

    private fun currentRotationMode(): String? {
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as? NavHostFragment ?: return null
        return when (navHostFragment.navController.currentDestination?.id) {
            R.id.camera_fragment -> DetectionPrefs.MODE_CAMERA
            R.id.webcam_fragment -> DetectionPrefs.MODE_WEBCAM
            else -> null
        }
    }

    // Shows the button with the stored rotation of the active tab, or hides it on other screens.
    fun refreshRotateButton() {
        val mode = currentRotationMode()
        if (mode == null) {
            activityMainBinding.btnRotateHeader.visibility = View.GONE
            return
        }
        activityMainBinding.btnRotateHeader.visibility = View.VISIBLE
        setRotateLabel(DetectionPrefs.getRotation(applicationContext, mode))
    }

    private fun setRotateLabel(degrees: Int) {
        activityMainBinding.txtRotateHeader.text = "${degrees}°"
        activityMainBinding.btnRotateHeader.contentDescription = "Putar tampilan, $degrees derajat"
    }

    override fun onResume() {
        super.onResume()
        refreshRotateButton()
    }

    // launchMode=singleTask: a USB_DEVICE_ATTACHED while the app is open lands here instead of
    // creating a new instance. Nothing is reset on purpose; UsbManager grants permission itself.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra("clavo_geo")) com.google.mediapipe.examples.objectdetection.utils.GeoLog.active = intent.getBooleanExtra("clavo_geo", false) // TEMP-ClavoGeo
        UsbDebugLog.d("MainActivity.onNewIntent action=${intent.action}") // TEMP-ClavoUSB
    }

    override fun onBackPressed() {
        finish()
    }
}