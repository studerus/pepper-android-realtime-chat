package ch.fhnw.pepper_realtime.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import ch.fhnw.pepper_realtime.manager.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    // Settings State as StateFlow
    private val _settingsState = MutableStateFlow(loadSettingsFromRepository())
    val settingsState: StateFlow<SettingsState> = _settingsState.asStateFlow()

    // Events as StateFlow
    private val _restartSessionEvent = MutableStateFlow(false)
    private val _updateSessionEvent = MutableStateFlow(false)
    private val _restartRecognizerEvent = MutableStateFlow(false)
    private val _volumeChangeEvent = MutableStateFlow<Int?>(null)

    val restartSessionEvent: StateFlow<Boolean> = _restartSessionEvent.asStateFlow()
    val updateSessionEvent: StateFlow<Boolean> = _updateSessionEvent.asStateFlow()
    val restartRecognizerEvent: StateFlow<Boolean> = _restartRecognizerEvent.asStateFlow()
    val volumeChangeEvent: StateFlow<Int?> = _volumeChangeEvent.asStateFlow()

    // Batch Update Mode
    private var isBatchMode = false
    private var pendingRestart = false
    private var pendingUpdate = false
    private var pendingRecognizerRestart = false
    
    private fun loadSettingsFromRepository(): SettingsState {
        return SettingsState(
            systemPrompt = settingsRepository.systemPrompt,
            model = settingsRepository.model,
            voice = settingsRepository.voice,
            speedProgress = settingsRepository.speedProgress,
            language = settingsRepository.language,
            apiProvider = settingsRepository.apiProvider,
            audioInputMode = settingsRepository.audioInputMode,
            temperatureProgress = settingsRepository.temperatureProgress,
            volume = settingsRepository.volume,
            silenceTimeout = settingsRepository.silenceTimeout,
            confidenceThreshold = settingsRepository.confidenceThreshold,
            enabledTools = settingsRepository.enabledTools,
            transcriptionModel = settingsRepository.transcriptionModel,
            transcriptionLanguage = settingsRepository.transcriptionLanguage,
            transcriptionPrompt = settingsRepository.transcriptionPrompt,
            turnDetectionType = settingsRepository.turnDetectionType,
            vadThreshold = settingsRepository.vadThreshold,
            prefixPadding = settingsRepository.prefixPadding,
            silenceDuration = settingsRepository.silenceDuration,
            idleTimeout = settingsRepository.idleTimeout,
            eagerness = settingsRepository.eagerness,
            noiseReduction = settingsRepository.noiseReduction
        )
    }
    
    fun beginEditing() {
        Log.d("SettingsViewModel", "beginEditing: entering batch mode")
        isBatchMode = true
        pendingRestart = false
        pendingUpdate = false
        pendingRecognizerRestart = false
    }
    
    fun commitChanges() {
        isBatchMode = false
        Log.d("SettingsViewModel", "commitChanges: pendingRestart=$pendingRestart, pendingUpdate=$pendingUpdate")
        if (pendingRestart) _restartSessionEvent.value = true
        else if (pendingUpdate) _updateSessionEvent.value = true
        
        if (pendingRecognizerRestart) _restartRecognizerEvent.value = true
        
        pendingRestart = false
        pendingUpdate = false
        pendingRecognizerRestart = false
    }
    
    // Settings Operations
    fun setSystemPrompt(prompt: String) {
        if (prompt != settingsRepository.systemPrompt) {
            settingsRepository.systemPrompt = prompt
            _settingsState.update { it.copy(systemPrompt = prompt) }
            if (isBatchMode) pendingUpdate = true else _updateSessionEvent.value = true
        }
    }

    fun setModel(model: String) {
        if (model != settingsRepository.model) {
            Log.d("SettingsViewModel", "setModel: $model (isBatchMode=$isBatchMode)")
            settingsRepository.model = model
            _settingsState.update { it.copy(model = model) }
            if (isBatchMode) pendingRestart = true else _restartSessionEvent.value = true
        }
    }

    fun setVoice(voice: String) {
        if (voice != settingsRepository.voice) {
            settingsRepository.voice = voice
            _settingsState.update { it.copy(voice = voice) }
            if (isBatchMode) pendingRestart = true else _restartSessionEvent.value = true
        }
    }

    fun setSpeedProgress(progress: Int) {
        if (progress != settingsRepository.speedProgress) {
            settingsRepository.speedProgress = progress
            _settingsState.update { it.copy(speedProgress = progress) }
            if (isBatchMode) pendingUpdate = true else _updateSessionEvent.value = true
        }
    }

    fun setLanguage(language: String) {
        if (language != settingsRepository.language) {
            settingsRepository.language = language
            _settingsState.update { it.copy(language = language) }
            if (isBatchMode) pendingRecognizerRestart = true else _restartRecognizerEvent.value = true
        }
    }

    fun setApiProvider(provider: String) {
        if (provider != settingsRepository.apiProvider) {
            settingsRepository.apiProvider = provider
            _settingsState.update { it.copy(apiProvider = provider) }
            if (isBatchMode) pendingRestart = true else _restartSessionEvent.value = true
        }
    }

    fun setAudioInputMode(mode: String) {
        if (mode != settingsRepository.audioInputMode) {
            settingsRepository.audioInputMode = mode
            _settingsState.update { it.copy(audioInputMode = mode) }
            if (isBatchMode) pendingRestart = true else _restartSessionEvent.value = true
        }
    }

    fun setTemperatureProgress(progress: Int) {
        if (progress != settingsRepository.temperatureProgress) {
            settingsRepository.temperatureProgress = progress
            _settingsState.update { it.copy(temperatureProgress = progress) }
            if (isBatchMode) pendingUpdate = true else _updateSessionEvent.value = true
        }
    }

    fun setVolume(volume: Int) {
        if (volume != settingsRepository.volume) {
            settingsRepository.volume = volume
            _settingsState.update { it.copy(volume = volume) }
            _volumeChangeEvent.value = volume
        }
    }

    fun setSilenceTimeout(timeout: Int) {
        if (timeout != settingsRepository.silenceTimeout) {
            settingsRepository.silenceTimeout = timeout
            _settingsState.update { it.copy(silenceTimeout = timeout) }
            if (isBatchMode) pendingRecognizerRestart = true else _restartRecognizerEvent.value = true
        }
    }

    fun setConfidenceThreshold(threshold: Float) {
        if (threshold != settingsRepository.confidenceThreshold) {
            settingsRepository.confidenceThreshold = threshold
            _settingsState.update { it.copy(confidenceThreshold = threshold) }
        }
    }

    fun setEnabledTools(tools: Set<String>) {
        if (tools != settingsRepository.enabledTools) {
            settingsRepository.enabledTools = tools
            _settingsState.update { it.copy(enabledTools = tools) }
            if (isBatchMode) pendingUpdate = true else _updateSessionEvent.value = true
        }
    }
    
    // Realtime API Specific Settings
    fun setTranscriptionModel(model: String) {
        if (model != settingsRepository.transcriptionModel) {
            settingsRepository.transcriptionModel = model
            _settingsState.update { it.copy(transcriptionModel = model) }
            triggerRealtimeSettingChange()
        }
    }

    fun setTranscriptionLanguage(language: String) {
        if (language != settingsRepository.transcriptionLanguage) {
            settingsRepository.transcriptionLanguage = language
            _settingsState.update { it.copy(transcriptionLanguage = language) }
            triggerRealtimeSettingChange()
        }
    }

    fun setTranscriptionPrompt(prompt: String) {
        if (prompt != settingsRepository.transcriptionPrompt) {
            settingsRepository.transcriptionPrompt = prompt
            _settingsState.update { it.copy(transcriptionPrompt = prompt) }
            triggerRealtimeSettingChange()
        }
    }

    fun setTurnDetectionType(type: String) {
        if (type != settingsRepository.turnDetectionType) {
            settingsRepository.turnDetectionType = type
            _settingsState.update { it.copy(turnDetectionType = type) }
            triggerRealtimeSettingChange()
        }
    }

    fun setVadThreshold(threshold: Float) {
        if (threshold != settingsRepository.vadThreshold) {
            settingsRepository.vadThreshold = threshold
            _settingsState.update { it.copy(vadThreshold = threshold) }
            triggerRealtimeSettingChange()
        }
    }

    fun setPrefixPadding(padding: Int) {
        if (padding != settingsRepository.prefixPadding) {
            settingsRepository.prefixPadding = padding
            _settingsState.update { it.copy(prefixPadding = padding) }
            triggerRealtimeSettingChange()
        }
    }

    fun setSilenceDuration(duration: Int) {
        if (duration != settingsRepository.silenceDuration) {
            settingsRepository.silenceDuration = duration
            _settingsState.update { it.copy(silenceDuration = duration) }
            triggerRealtimeSettingChange()
        }
    }

    fun setIdleTimeout(timeout: Int) {
        val current = settingsRepository.idleTimeout
        if (current == null || timeout != current) {
            settingsRepository.idleTimeout = timeout
            _settingsState.update { it.copy(idleTimeout = timeout) }
            triggerRealtimeSettingChange()
        }
    }

    fun setEagerness(eagerness: String) {
        if (eagerness != settingsRepository.eagerness) {
            settingsRepository.eagerness = eagerness
            _settingsState.update { it.copy(eagerness = eagerness) }
            triggerRealtimeSettingChange()
        }
    }

    fun setNoiseReduction(reduction: String) {
        if (reduction != settingsRepository.noiseReduction) {
            settingsRepository.noiseReduction = reduction
            _settingsState.update { it.copy(noiseReduction = reduction) }
            triggerRealtimeSettingChange()
        }
    }
    
    private fun triggerRealtimeSettingChange() {
        if (settingsRepository.isUsingRealtimeAudioInput) {
            if (isBatchMode) pendingUpdate = true else _updateSessionEvent.value = true
        }
    }

    // Event consumption methods
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
