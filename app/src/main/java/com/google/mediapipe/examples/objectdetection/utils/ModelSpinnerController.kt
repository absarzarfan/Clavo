package com.google.mediapipe.examples.objectdetection.utils

import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView

/**
 * Shared model-picker logic for CameraFragment and WebcamFragment: fills the spinner from the
 * bundled models, persists the choice (so both screens use the same model) and reports changes.
 */
class ModelSpinnerController(
    private val context: Context,
    private val spinner: Spinner,
    private val statusView: TextView,
    private val onModelSelected: (ModelRepository.ModelInfo) -> Unit
) {
    private var models: List<ModelRepository.ModelInfo> = emptyList()
    private var currentAssetPath: String? = null

    private val listener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            val model = models.getOrNull(position) ?: return
            if (model.assetPath == currentAssetPath) return // initial/redundant callback
            currentAssetPath = model.assetPath
            ModelRepository.saveSelectedModel(context, model.fileName)
            onModelSelected(model)
        }

        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    /** Shows [available] in the spinner with [selected] chosen; shows a clear message if empty. */
    fun bind(available: List<ModelRepository.ModelInfo>, selected: ModelRepository.ModelInfo?) {
        models = available
        spinner.onItemSelectedListener = null
        if (available.isEmpty()) {
            currentAssetPath = null
            spinner.adapter = null
            spinner.isEnabled = false
            statusView.text = EMPTY_MESSAGE
            statusView.visibility = View.VISIBLE
            return
        }
        statusView.visibility = View.GONE
        spinner.isEnabled = true
        spinner.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            available.map { it.displayName }
        )
        currentAssetPath = selected?.assetPath
        val index = available.indexOfFirst { it.assetPath == currentAssetPath }
        if (index != -1) spinner.setSelection(index, false)
        spinner.onItemSelectedListener = listener
    }

    companion object {
        const val EMPTY_MESSAGE =
            "Tidak ada model ditemukan di assets/models/. Tambahkan file .tflite lalu build ulang aplikasi."
    }
}
