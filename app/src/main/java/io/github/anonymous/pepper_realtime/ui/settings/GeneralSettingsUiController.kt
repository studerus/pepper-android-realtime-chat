package io.github.anonymous.pepper_realtime.ui.settings

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import io.github.anonymous.pepper_realtime.R
import io.github.anonymous.pepper_realtime.manager.ApiKeyManager
import io.github.anonymous.pepper_realtime.manager.SettingsManager
import io.github.anonymous.pepper_realtime.network.RealtimeApiProvider
import io.github.anonymous.pepper_realtime.ui.ChatActivity
import kotlin.math.max

class GeneralSettingsUiController(
    private val activity: ChatActivity,
    rootView: View,
    private val viewModel: SettingsViewModel
) {

    // UI Elements
    private val systemPromptInput: EditText = rootView.findViewById(R.id.system_prompt_input)
    private val modelSpinner: Spinner = rootView.findViewById(R.id.model_spinner)
    private val apiProviderSpinner: Spinner = rootView.findViewById(R.id.api_provider_spinner)
    private val voiceSpinner: Spinner = rootView.findViewById(R.id.voice_spinner)
    private val speedSeekBar: SeekBar = rootView.findViewById(R.id.speed_seekbar)
    private val speedValue: TextView = rootView.findViewById(R.id.speed_value)
    private val languageSpinner: Spinner = rootView.findViewById(R.id.language_spinner)
    private val audioInputModeSpinner: Spinner = rootView.findViewById(R.id.audio_input_mode_spinner)
    private val temperatureSeekBar: SeekBar = rootView.findViewById(R.id.temperature_seekbar)
    private val temperatureValue: TextView = rootView.findViewById(R.id.temperature_value)
    private val volumeSeekBar: SeekBar = rootView.findViewById(R.id.volume_seekbar)
    private val volumeValue: TextView = rootView.findViewById(R.id.volume_value)

    // Callback for visibility updates
    fun interface VisibilityUpdateCallback {
        fun onAudioInputModeChanged()
    }

    private var visibilityUpdateCallback: VisibilityUpdateCallback? = null

    init {
        setupListeners()
        loadInitialValues()
    }

    private fun setupListeners() {
        temperatureSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                temperatureValue.text = activity.getString(
                    R.string.temperature_format,
                    convertProgressToTemperature(progress)
                )
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                viewModel.setTemperatureProgress(seekBar.progress)
            }
        })

        speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val effectiveProgress = max(progress, 25)
                speedValue.text = activity.getString(R.string.speed_format, effectiveProgress / 100f)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val progress = max(seekBar.progress, 25)
                viewModel.setSpeedProgress(progress)
            }
        })

        volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                volumeValue.text = activity.getString(R.string.volume_format, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                viewModel.setVolume(seekBar.progress)
            }
        })

        audioInputModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Update visibility immediately when mode changes
                visibilityUpdateCallback?.onAudioInputModeChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    fun loadInitialValues() {
        // Populate Spinners
        val keyManager = ApiKeyManager(activity)
        val configuredProviders = keyManager.getConfiguredProviders()

        if (configuredProviders.isNotEmpty()) {
            val providerAdapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_item,
                configuredProviders.toList()
            )
            providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            apiProviderSpinner.adapter = providerAdapter

            val current = RealtimeApiProvider.fromString(viewModel.getApiProvider())
            for (i in configuredProviders.indices) {
                if (configuredProviders[i] == current) {
                    apiProviderSpinner.setSelection(i)
                    break
                }
            }
        } else {
            val noProviders = arrayOf("No API providers configured")
            val emptyAdapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_item,
                noProviders
            )
            apiProviderSpinner.adapter = emptyAdapter
            apiProviderSpinner.isEnabled = false
        }

        // Model Spinner
        updateModelSpinner()
        val savedModel = viewModel.getModel()
        @Suppress("UNCHECKED_CAST")
        val modelAdapter = modelSpinner.adapter as? ArrayAdapter<String>
        if (modelAdapter != null) {
            val modelPosition = modelAdapter.getPosition(savedModel)
            if (modelPosition >= 0) {
                modelSpinner.setSelection(modelPosition)
            }
        }

        // Voice Spinner
        val voices = arrayOf("alloy", "ash", "ballad", "cedar", "coral", "echo", "marin", "sage", "shimmer", "verse")
        val voiceAdapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, voices)
        voiceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        voiceSpinner.adapter = voiceAdapter
        voiceSpinner.setSelection(voiceAdapter.getPosition(viewModel.getVoice()))

        // Language Spinner
        val languages = SettingsManager.getAvailableLanguages()
        val languageAdapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_item,
            languages
        )
        languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        languageSpinner.adapter = languageAdapter

        val savedLangCode = viewModel.getLanguage()
        for (i in languages.indices) {
            if (languages[i].code == savedLangCode) {
                languageSpinner.setSelection(i)
                break
            }
        }

        // Other values
        systemPromptInput.setText(viewModel.getSystemPrompt())

        val speedProgress = viewModel.getSpeedProgress()
        speedSeekBar.progress = speedProgress
        speedValue.text = activity.getString(R.string.speed_format, speedProgress / 100f)

        val tempProgress = viewModel.getTemperatureProgress()
        temperatureSeekBar.progress = tempProgress
        temperatureValue.text = activity.getString(
            R.string.temperature_format,
            convertProgressToTemperature(tempProgress)
        )

        val volProgress = viewModel.getVolume()
        volumeSeekBar.progress = volProgress
        volumeValue.text = activity.getString(R.string.volume_format, volProgress)

        val savedInputMode = viewModel.getAudioInputMode()
        val inputModePosition = if (SettingsManager.MODE_REALTIME_API == savedInputMode) 0 else 1
        audioInputModeSpinner.setSelection(inputModePosition)
    }

    fun applyChanges() {
        // Read all values and update ViewModel
        viewModel.setSystemPrompt(systemPromptInput.text.toString())

        modelSpinner.selectedItem?.let {
            viewModel.setModel(it as String)
        }

        voiceSpinner.selectedItem?.let {
            viewModel.setVoice(it as String)
        }

        (apiProviderSpinner.selectedItem as? RealtimeApiProvider)?.let {
            viewModel.setApiProvider(it.name)
        }

        (languageSpinner.selectedItem as? LanguageOption)?.let {
            viewModel.setLanguage(it.code)
        }

        val inputMode = if (audioInputModeSpinner.selectedItemPosition == 0) {
            SettingsManager.MODE_REALTIME_API
        } else {
            SettingsManager.MODE_AZURE_SPEECH
        }
        viewModel.setAudioInputMode(inputMode)

        // SeekBars are updated in real-time, but we can re-set them here to be sure
        viewModel.setSpeedProgress(speedSeekBar.progress)
        viewModel.setTemperatureProgress(temperatureSeekBar.progress)
        // Volume is updated in real-time
    }

    private fun convertProgressToTemperature(progress: Int): Float {
        return 0.6f + (progress / 100.0f) * 0.6f
    }

    val isRealtimeAudioModeSelected: Boolean
        get() = audioInputModeSpinner.selectedItemPosition == 0

    fun getApiProviderSpinner(): Spinner = apiProviderSpinner

    fun getModelSpinner(): Spinner = modelSpinner

    fun getActivity(): ChatActivity = activity

    fun setVisibilityUpdateCallback(callback: VisibilityUpdateCallback?) {
        this.visibilityUpdateCallback = callback
    }

    /**
     * Update model spinner options (provider-agnostic)
     */
    private fun updateModelSpinner() {
        val models = arrayOf(
            "gpt-realtime",
            "gpt-realtime-mini",
            "gpt-4o-realtime-preview",
            "gpt-4o-mini-realtime-preview"
        )
        val modelAdapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, models)
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modelSpinner.adapter = modelAdapter
    }
}

