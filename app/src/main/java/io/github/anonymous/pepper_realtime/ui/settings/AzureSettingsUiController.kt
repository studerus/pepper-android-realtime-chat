package io.github.anonymous.pepper_realtime.ui.settings

import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import io.github.anonymous.pepper_realtime.R
import io.github.anonymous.pepper_realtime.ui.ChatActivity
import kotlin.math.roundToInt

class AzureSettingsUiController(
    private val activity: ChatActivity,
    rootView: View,
    private val viewModel: SettingsViewModel
) {

    // UI Elements
    private val azureSpeechSettingsContainer: LinearLayout = rootView.findViewById(R.id.azure_speech_settings_container)
    private val silenceTimeoutSeekBar: SeekBar = rootView.findViewById(R.id.silence_timeout_seekbar)
    private val silenceTimeoutValue: TextView = rootView.findViewById(R.id.silence_timeout_value)
    private val confidenceThresholdSeekBar: SeekBar = rootView.findViewById(R.id.confidence_threshold_seekbar)
    private val confidenceThresholdValue: TextView = rootView.findViewById(R.id.confidence_threshold_value)

    init {
        setupListeners()
        loadInitialValues()
    }

    private fun setupListeners() {
        silenceTimeoutSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                silenceTimeoutValue.text = activity.getString(R.string.silence_timeout_format, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // Defer to applyChanges
            }
        })

        confidenceThresholdSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                confidenceThresholdValue.text = activity.getString(R.string.confidence_threshold_format, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // Defer to applyChanges
            }
        })
    }

    fun loadInitialValues() {
        val silenceTimeout = viewModel.getSilenceTimeout()
        silenceTimeoutSeekBar.progress = silenceTimeout
        silenceTimeoutValue.text = activity.getString(R.string.silence_timeout_format, silenceTimeout)

        val confProgress = (viewModel.getConfidenceThreshold() * 100f).roundToInt()
        confidenceThresholdSeekBar.progress = confProgress
        confidenceThresholdValue.text = activity.getString(R.string.confidence_threshold_format, confProgress)
    }

    fun applyChanges() {
        viewModel.setSilenceTimeout(silenceTimeoutSeekBar.progress)
        viewModel.setConfidenceThreshold(confidenceThresholdSeekBar.progress / 100f)
    }

    fun setVisibility(visible: Boolean) {
        azureSpeechSettingsContainer.visibility = if (visible) View.VISIBLE else View.GONE
    }
}

