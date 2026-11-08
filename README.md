# Clavo: Real-time Clove Ripeness Detection

Clavo is an Android application designed for real-time object detection, with a specialized focus on determining the ripeness of cloves. The application leverages TensorFlow Lite and MediaPipe to perform on-device inference, providing a fast and efficient solution for agricultural analysis. It supports multiple input sources, making it a versatile tool for both field and lab use.

## Key Features

*   **Clove Ripeness Detection:** Utilizes a custom-trained TFLite model (`efficientdet_lite2_cengkeh_V2.tflite`) to identify and classify cloves.
*   **Real-Time Inference:** Performs object detection on-device for immediate results.
*   **Multiple Input Modes:**
    *   **Live Camera:** Uses the device's built-in camera for live detection.
    *   **External Webcam (USB):** Supports USB (UVC) webcams for flexible camera placement.
*   **Visual Feedback:** Overlays bounding boxes and confidence scores on the detected objects.

## Technology Stack

*   **Platform:** Android
*   **Language:** Kotlin
*   **ML/Vision:**
    *   MediaPipe Tasks for Vision
    *   TensorFlow Lite
*   **Android Jetpack:**
    *   Navigation Component for in-app navigation.
    *   ViewModel for state management.
*   **Libraries:**
    *   `AndroidUSBCamera` for external webcam support.

## How It Works

The core of the application is the `ObjectDetectorHelper` class, which manages the lifecycle and configuration of the MediaPipe `ObjectDetector`. It is responsible for:

1.  Initializing the TFLite model (`efficientdet_lite2_cengkeh_V2.tflite`).
2.  Configuring the detector with parameters like detection threshold and delegate (CPU/GPU).
3.  Processing input from different sources (ImageProxy, Bitmap) and running inference.
4.  Returning detection results to the corresponding UI fragment.

The UI is divided into two main fragments, each handling a specific input source:
*   `CameraFragment`: Runs detection on the live feed of the device's built-in camera.
*   `WebcamFragment`: Manages the connection and frame capture from a USB webcam.

An `OverlayView` is used across all modes to draw the bounding boxes and labels returned by the detector onto the screen.

## Getting Started

### Prerequisites

*   Android Studio (latest stable version)
*   An Android device (API level 24+)
*   (Optional) A USB webcam for testing all features.

### Build and Run

1.  **Clone the repository:**
    ```sh
    git clone https://github.com/jojo297/clavo.git
    ```

2.  **Open in Android Studio:**
    *   Launch Android Studio.
    *   Select `File` > `Open` and navigate to the cloned repository directory.

3.  **Sync and Run:**
    *   Allow Gradle to sync the project dependencies.
    *   Connect your Android device or start an emulator.
    *   Click the **Run** button.

## Usage

Use the bottom navigation bar to switch between detection modes:

*   **Camera:** Uses the device's built-in camera.
*   **Webcam:** Connect a USB webcam to your device (via OTG). Grant permissions when prompted.
