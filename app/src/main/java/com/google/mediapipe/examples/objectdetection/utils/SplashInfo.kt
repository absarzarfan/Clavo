package com.google.mediapipe.examples.objectdetection.utils

/** Pure text of the version/credit overlay shown briefly after the system splash. */
object SplashInfo {
    const val FOOTER = "made by @absarz software"

    fun line1(versionName: String) = "Clavo versi $versionName"

    fun line2(gitSha: String, buildType: String, buildTime: String) =
        "commit $gitSha - $buildType - build $buildTime"

    /** "Model (2): Cengkeh v2, Mangga v2"; "Model (0): -" when none is bundled. */
    fun line3(modelFiles: List<String>): String {
        val names = modelFiles
            .filter { it.endsWith(".tflite", ignoreCase = true) }
            .sorted()
            .map { ModelRepository.shortNameFor(it) }
        return "Model (${names.size}): ${if (names.isEmpty()) "-" else names.joinToString(", ")}"
    }
}
