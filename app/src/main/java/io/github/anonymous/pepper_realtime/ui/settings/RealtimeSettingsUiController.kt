package io.github.anonymous.pepper_realtime.ui.settings

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import io.github.anonymous.pepper_realtime.R
import io.github.anonymous.pepper_realtime.ui.ChatActivity
import kotlin.math.max
import kotlin.math.roundToInt

class RealtimeSettingsUiController(
    private val activity: ChatActivity,
    rootView: View,
    private val viewModel: SettingsViewModel
) {

    // UI Elements
    private val realtimeApiSettingsContainer: LinearLayout = rootView.findViewById(R.id.realtime_api_settings_container)
    private val serverVadSettingsContainer: LinearLayout = rootView.findViewById(R.id.server_vad_settings_container)
    private val semanticVadSettingsContainer: LinearLayout = rootView.findViewById(R.id.semantic_vad_settings_container)

    private val transcriptionModelSpinner: Spinner = rootView.findViewById(R.id.transcription_model_spinner)
    private val transcriptionLanguageInput: EditText = rootView.findViewById(R.id.transcription_language_input)
    private val transcriptionPromptInput: EditText = rootView.findViewById(R.id.transcription_prompt_input)
    private val turnDetectionTypeSpinner: Spinner = rootView.findViewById(R.id.turn_detection_type_spinner)
    private val vadThresholdSeekBar: SeekBar = rootView.findViewById(R.id.vad_threshold_seekbar)
    private val vadThresholdValue: TextView = rootView.findViewById(R.id.vad_threshold_value)
    private val prefixPaddingSeekBar: SeekBar = rootView.findViewById(R.id.prefix_padding_seekbar)
    private val prefixPaddingValue: TextView = rootView.findViewById(R.id.prefix_padding_value)
    private val silenceDurationSeekBar: SeekBar = rootView.findViewById(R.id.silence_duration_seekbar)
    private val silenceDurationValue: TextView = rootView.findViewById(R.id.silence_duration_value)
    private val idleTimeoutInput: EditText = rootView.findViewById(R.id.idle_timeout_input)
    private val eagernessSpinner: Spinner = rootView.findViewById(R.id.eagerness_spinner)
    private val noiseReductionSpinner: Spinner = rootView.findViewById(R.id.noise_reduction_spinner)

    init {
        setupListeners()
        loadInitialValues()
    }

    private fun setupListeners() {
        turnDetectionTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateVadSettingsVisibility(position == 0) // true = Server VAD
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        vadThresholdSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                vadThresholdValue.text = activity.getString(R.string.realtime_vad_threshold_format, progress / 100f)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // Defer to applyChanges
            }
        })

        prefixPaddingSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                prefixPaddingValue.text = activity.getString(R.string.realtime_prefix_padding_format, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // Defer to applyChanges
            }
        })

        silenceDurationSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val effectiveProgress = max(progress, 200)
                silenceDurationValue.text = activity.getString(R.string.realtime_silence_duration_format, effectiveProgress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // Defer to applyChanges
            }
        })
    }

    fun loadInitialValues() {
        // Transcription Model
        val savedTranscriptionModel = viewModel.getTranscriptionModel()
        val transcriptionAdapter = ArrayAdapter.createFromResource(
            activity, R.array.transcription_models, android.R.layout.simple_spinner_item
        )
        transcriptionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        transcriptionModelSpinner.adapter = transcriptionAdapter

        var transcriptionModelPosition = 0
        for (i in 0 until transcriptionAdapter.count) {
            val item = transcriptionAdapter.getItem(i)
            if (item?.toString() == savedTranscriptionModel) {
                transcriptionModelPosition = i
                break
            }
        }
        transcriptionModelSpinner.setSelection(transcriptionModelPosition)

        transcriptionLanguageInput.setText(viewModel.getTranscriptionLanguage())
        transcriptionPromptInput.setText(viewModel.getTranscriptionPrompt())

        val savedTurnDetectionType = viewModel.getTurnDetectionType()
        val turnDetectionPosition = if (savedTurnDetectionType == "semantic_vad") 1 else 0
        turnDetectionTypeSpinner.setSelection(turnDetectionPosition)

        val vadThresholdProgress = (viewModel.getVadThreshold() * 100f).roundToInt()
        vadThresholdSeekBar.progress = vadThresholdProgress
        vadThresholdValue.text = activity.getString(R.string.realtime_vad_threshold_format, vadThresholdProgress / 100f)

        val prefixPadding = viewModel.getPrefixPadding()
        prefixPaddingSeekBar.progress = prefixPadding
        prefixPaddingValue.text = activity.getString(R.string.realtime_prefix_padding_format, prefixPadding)

        val silenceDuration = viewModel.getSilenceDuration()
        silenceDurationSeekBar.progress = silenceDuration
        silenceDurationValue.text = activity.getString(R.string.realtime_silence_duration_format, silenceDuration)

        val idleTimeout = viewModel.getIdleTimeout()
        if (idleTimeout != null && idleTimeout > 0) {
            idleTimeoutInput.setText(idleTimeout.toString())
        }

        val savedEagerness = viewModel.getEagerness()
        val eagernessPosition = when (savedEagerness) {
            "low" -> 1
            "medium" -> 2
            "high" -> 3
            else -> 0
        }
        eagernessSpinner.setSelection(eagernessPosition)

        val savedNoiseReduction = viewModel.getNoiseReduction()
        val noiseReductionPosition = when (savedNoiseReduction) {
            "near_field" -> 1
            "far_field" -> 2
            else -> 0
        }
        noiseReductionSpinner.setSelection(noiseReductionPosition)

        updateVadSettingsVisibility(turnDetectionPosition == 0)
    }

    fun applyChanges() {
        transcriptionModelSpinner.selectedItem?.let {
            viewModel.setTranscriptionModel(it.toString())
        }
        viewModel.setTranscriptionLanguage(transcriptionLanguageInput.text.toString())
        viewModel.setTranscriptionPrompt(transcriptionPromptInput.text.toString())

        val turnDetectionType = if (turnDetectionTypeSpinner.selectedItemPosition == 0) "server_vad" else "semantic_vad"
        viewModel.setTurnDetectionType(turnDetectionType)

        viewModel.setVadThreshold(vadThresholdSeekBar.progress / 100f)
        viewModel.setPrefixPadding(prefixPaddingSeekBar.progress)
        viewModel.setSilenceDuration(max(silenceDurationSeekBar.progress, 200))

        try {
            val idleTimeoutStr = idleTimeoutInput.text.toString().trim()
            viewModel.setIdleTimeout(
                if (idleTimeoutStr.isNotEmpty()) idleTimeoutStr.toInt() else 0
            )
        } catch (ignored: NumberFormatException) {
        }

        val eagerness = when (eagernessSpinner.selectedItemPosition) {
            1 -> "low"
            2 -> "medium"
            3 -> "high"
            else -> "auto"
        }
        viewModel.setEagerness(eagerness)

        val noiseReduction = when (noiseReductionSpinner.selectedItemPosition) {
            1 -> "near_field"
            2 -> "far_field"
            else -> "off"
        }
        viewModel.setNoiseReduction(noiseReduction)
    }

    fun setVisibility(visible: Boolean) {
        realtimeApiSettingsContainer.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun updateVadSettingsVisibility(isServerVad: Boolean) {
        if (isServerVad) {
            serverVadSettingsContainer.visibility = View.VISIBLE
            semanticVadSettingsContainer.visibility = View.GONE
        } else {
            serverVadSettingsContainer.visibility = View.GONE
            semanticVadSettingsContainer.visibility = View.VISIBLE
        }
    }
}

