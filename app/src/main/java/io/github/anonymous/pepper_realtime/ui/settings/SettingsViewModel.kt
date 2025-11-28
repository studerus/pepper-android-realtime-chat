package io.github.anonymous.pepper_realtime.ui.settings

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.anonymous.pepper_realtime.manager.SettingsRepository
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    // Events
    private val _restartSessionEvent = MutableLiveData<Boolean>()
    private val _updateSessionEvent = MutableLiveData<Boolean>()
    private val _restartRecognizerEvent = MutableLiveData<Boolean>()
    private val _volumeChangeEvent = MutableLiveData<Int>()

    // Event Getters
    val restartSessionEvent: LiveData<Boolean> get() = _restartSessionEvent
    val updateSessionEvent: LiveData<Boolean> get() = _updateSessionEvent
    val restartRecognizerEvent: LiveData<Boolean> get() = _restartRecognizerEvent
    val volumeChangeEvent: LiveData<Int> get() = _volumeChangeEvent

    // Batch Update Mode
    private var isBatchMode = false
    private var pendingRestart = false
    private var pendingUpdate = false
    private var pendingRecognizerRestart = false
    
    fun beginEditing() {
        isBatchMode = true
        pendingRestart = false
        pendingUpdate = false
        pendingRecognizerRestart = false
    }
    
    fun commitChanges() {
        isBatchMode = false
        if (pendingRestart) _restartSessionEvent.value = true
        else if (pendingUpdate) _updateSessionEvent.value = true
        
        if (pendingRecognizerRestart) _restartRecognizerEvent.value = true
        
        // Reset pending flags
        pendingRestart = false
        pendingUpdate = false
        pendingRecognizerRestart = false
    }
    
    // Settings Operations
    fun setSystemPrompt(prompt: String) {
        if (prompt != settingsRepository.systemPrompt) {
            settingsRepository.systemPrompt = prompt
            if (isBatchMode) pendingUpdate = true else _updateSessionEvent.value = true
        }
    }

    fun setModel(model: String) {
        if (model != settingsRepository.model) {
            settingsRepository.model = model
            if (isBatchMode) pendingRestart = true else _restartSessionEvent.value = true
        }
    }

    fun setVoice(voice: String) {
        if (voice != settingsRepository.voice) {
            settingsRepository.voice = voice
            if (isBatchMode) pendingRestart = true else _restartSessionEvent.value = true
        }
    }

    fun setSpeedProgress(progress: Int) {
        if (progress != settingsRepository.speedProgress) {
            settingsRepository.speedProgress = progress
            if (isBatchMode) pendingUpdate = true else _updateSessionEvent.value = true
        }
    }

    fun setLanguage(language: String) {
        if (language != settingsRepository.language) {
            settingsRepository.language = language
            if (isBatchMode) pendingRecognizerRestart = true else _restartRecognizerEvent.value = true
        }
    }

    fun setApiProvider(provider: String) {
        if (provider != settingsRepository.apiProvider) {
            settingsRepository.apiProvider = provider
            if (isBatchMode) pendingRestart = true else _restartSessionEvent.value = true
        }
    }

    fun setAudioInputMode(mode: String) {
        if (mode != settingsRepository.audioInputMode) {
            settingsRepository.audioInputMode = mode
            if (isBatchMode) pendingRestart = true else _restartSessionEvent.value = true
        }
    }

    fun setTemperatureProgress(progress: Int) {
        if (progress != settingsRepository.temperatureProgress) {
            settingsRepository.temperatureProgress = progress
            if (isBatchMode) pendingUpdate = true else _updateSessionEvent.value = true
        }
    }

    fun setVolume(volume: Int) {
        if (volume != settingsRepository.volume) {
            settingsRepository.volume = volume
            _volumeChangeEvent.value = volume // Volume should change immediately for feedback
        }
    }

    fun setSilenceTimeout(timeout: Int) {
        if (timeout != settingsRepository.silenceTimeout) {
            settingsRepository.silenceTimeout = timeout
            if (isBatchMode) pendingRecognizerRestart = true else _restartRecognizerEvent.value = true
        }
    }

    fun setConfidenceThreshold(threshold: Float) {
        if (threshold != settingsRepository.confidenceThreshold) {
            settingsRepository.confidenceThreshold = threshold
            // No immediate action needed
        }
    }

    fun setEnabledTools(tools: Set<String>) {
        if (tools != settingsRepository.enabledTools) {
            settingsRepository.enabledTools = tools
            if (isBatchMode) pendingUpdate = true else _updateSessionEvent.value = true
        }
    }
    
    // Realtime API Specific Settings
    fun setTranscriptionModel(model: String) {
        if (model != settingsRepository.transcriptionModel) {
            settingsRepository.transcriptionModel = model
            triggerRealtimeSettingChange()
        }
    }

    fun setTranscriptionLanguage(language: String) {
        if (language != settingsRepository.transcriptionLanguage) {
            settingsRepository.transcriptionLanguage = language
            triggerRealtimeSettingChange()
        }
    }

    fun setTranscriptionPrompt(prompt: String) {
        if (prompt != settingsRepository.transcriptionPrompt) {
            settingsRepository.transcriptionPrompt = prompt
            triggerRealtimeSettingChange()
        }
    }

    fun setTurnDetectionType(type: String) {
        if (type != settingsRepository.turnDetectionType) {
            settingsRepository.turnDetectionType = type
            triggerRealtimeSettingChange()
        }
    }

    fun setVadThreshold(threshold: Float) {
        if (threshold != settingsRepository.vadThreshold) {
            settingsRepository.vadThreshold = threshold
            triggerRealtimeSettingChange()
        }
    }

    fun setPrefixPadding(padding: Int) {
        if (padding != settingsRepository.prefixPadding) {
            settingsRepository.prefixPadding = padding
            triggerRealtimeSettingChange()
        }
    }

    fun setSilenceDuration(duration: Int) {
        if (duration != settingsRepository.silenceDuration) {
            settingsRepository.silenceDuration = duration
            triggerRealtimeSettingChange()
        }
    }

    fun setIdleTimeout(timeout: Int) {
        val current = settingsRepository.idleTimeout
        if (current == null || timeout != current) {
            settingsRepository.idleTimeout = timeout
            triggerRealtimeSettingChange()
        }
    }

    fun setEagerness(eagerness: String) {
        if (eagerness != settingsRepository.eagerness) {
            settingsRepository.eagerness = eagerness
            triggerRealtimeSettingChange()
        }
    }

    fun setNoiseReduction(reduction: String) {
        if (reduction != settingsRepository.noiseReduction) {
            settingsRepository.noiseReduction = reduction
            triggerRealtimeSettingChange()
        }
    }
    
    private fun triggerRealtimeSettingChange() {
        if (settingsRepository.isUsingRealtimeAudioInput) {
            if (isBatchMode) pendingUpdate = true else _updateSessionEvent.value = true
        }
    }

    // Getters for UI Initialization
    fun getSystemPrompt(): String = settingsRepository.systemPrompt
    fun getModel(): String = settingsRepository.model
    fun getVoice(): String = settingsRepository.voice
    fun getSpeedProgress(): Int = settingsRepository.speedProgress
    fun getLanguage(): String = settingsRepository.language
    fun getApiProvider(): String = settingsRepository.apiProvider
    fun getAudioInputMode(): String = settingsRepository.audioInputMode
    fun getTemperatureProgress(): Int = settingsRepository.temperatureProgress
    fun getVolume(): Int = settingsRepository.volume
    fun getSilenceTimeout(): Int = settingsRepository.silenceTimeout
    fun getConfidenceThreshold(): Float = settingsRepository.confidenceThreshold
    fun getEnabledTools(): Set<String> = settingsRepository.enabledTools
    fun getTranscriptionModel(): String = settingsRepository.transcriptionModel
    fun getTranscriptionLanguage(): String = settingsRepository.transcriptionLanguage
    fun getTranscriptionPrompt(): String = settingsRepository.transcriptionPrompt
    fun getTurnDetectionType(): String = settingsRepository.turnDetectionType
    fun getVadThreshold(): Float = settingsRepository.vadThreshold
    fun getPrefixPadding(): Int = settingsRepository.prefixPadding
    fun getSilenceDuration(): Int = settingsRepository.silenceDuration
    fun getIdleTimeout(): Int? = settingsRepository.idleTimeout
    fun getEagerness(): String = settingsRepository.eagerness
    fun getNoiseReduction(): String = settingsRepository.noiseReduction

    // Event consumption methods for ChatActivity
    fun consumeRestartSessionEvent() {
        _restartSessionEvent.value = false
    }

    fun consumeUpdateSessionEvent() {
        _updateSessionEvent.value = false
    }

    fun consumeRestartRecognizerEvent() {
        _restartRecognizerEvent.value = false
    }
}

