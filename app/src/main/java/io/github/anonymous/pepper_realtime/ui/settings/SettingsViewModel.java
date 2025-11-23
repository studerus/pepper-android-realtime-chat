package io.github.anonymous.pepper_realtime.ui.settings;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.Set;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import io.github.anonymous.pepper_realtime.manager.SettingsRepository;
import io.github.anonymous.pepper_realtime.network.RealtimeApiProvider;

@HiltViewModel
public class SettingsViewModel extends ViewModel {

    private final SettingsRepository settingsRepository;

    // Events
    private final MutableLiveData<Boolean> restartSessionEvent = new MutableLiveData<>();
    private final MutableLiveData<Boolean> updateSessionEvent = new MutableLiveData<>();
    private final MutableLiveData<Boolean> restartRecognizerEvent = new MutableLiveData<>();
    private final MutableLiveData<Integer> volumeChangeEvent = new MutableLiveData<>();

    @Inject
    public SettingsViewModel(SettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
    }

    // Event Getters
    public LiveData<Boolean> getRestartSessionEvent() {
        return restartSessionEvent;
    }

    public LiveData<Boolean> getUpdateSessionEvent() {
        return updateSessionEvent;
    }

    public LiveData<Boolean> getRestartRecognizerEvent() {
        return restartRecognizerEvent;
    }

    public LiveData<Integer> getVolumeChangeEvent() {
        return volumeChangeEvent;
    }

    // Settings Operations

    public void setSystemPrompt(String prompt) {
        if (!prompt.equals(settingsRepository.getSystemPrompt())) {
            settingsRepository.setSystemPrompt(prompt);
            updateSessionEvent.setValue(true);
        }
    }

    public void setModel(String model) {
        if (!model.equals(settingsRepository.getModel())) {
            settingsRepository.setModel(model);
            restartSessionEvent.setValue(true);
        }
    }

    public void setVoice(String voice) {
        if (!voice.equals(settingsRepository.getVoice())) {
            settingsRepository.setVoice(voice);
            restartSessionEvent.setValue(true);
        }
    }

    public void setSpeedProgress(int progress) {
        if (progress != settingsRepository.getSpeedProgress()) {
            settingsRepository.setSpeedProgress(progress);
            updateSessionEvent.setValue(true);
        }
    }

    public void setLanguage(String language) {
        if (!language.equals(settingsRepository.getLanguage())) {
            settingsRepository.setLanguage(language);
            restartRecognizerEvent.setValue(true);
        }
    }

    public void setApiProvider(String provider) {
        if (!provider.equals(settingsRepository.getApiProvider())) {
            settingsRepository.setApiProvider(provider);
            restartSessionEvent.setValue(true);
        }
    }

    public void setAudioInputMode(String mode) {
        if (!mode.equals(settingsRepository.getAudioInputMode())) {
            settingsRepository.setAudioInputMode(mode);
            restartSessionEvent.setValue(true);
        }
    }

    public void setTemperatureProgress(int progress) {
        if (progress != settingsRepository.getTemperatureProgress()) {
            settingsRepository.setTemperatureProgress(progress);
            updateSessionEvent.setValue(true);
        }
    }

    public void setVolume(int volume) {
        if (volume != settingsRepository.getVolume()) {
            settingsRepository.setVolume(volume);
            volumeChangeEvent.setValue(volume);
        }
    }

    public void setSilenceTimeout(int timeout) {
        if (timeout != settingsRepository.getSilenceTimeout()) {
            settingsRepository.setSilenceTimeout(timeout);
            restartRecognizerEvent.setValue(true);
        }
    }

    public void setConfidenceThreshold(float threshold) {
        if (threshold != settingsRepository.getConfidenceThreshold()) {
            settingsRepository.setConfidenceThreshold(threshold);
            // No immediate action needed, used by recognizer on next result
        }
    }

    public void setEnabledTools(Set<String> tools) {
        if (!tools.equals(settingsRepository.getEnabledTools())) {
            settingsRepository.setEnabledTools(tools);
            updateSessionEvent.setValue(true);
        }
    }

    // Realtime API Specific Settings

    public void setTranscriptionModel(String model) {
        if (!model.equals(settingsRepository.getTranscriptionModel())) {
            settingsRepository.setTranscriptionModel(model);
            triggerRealtimeSettingChange();
        }
    }

    public void setTranscriptionLanguage(String language) {
        if (!language.equals(settingsRepository.getTranscriptionLanguage())) {
            settingsRepository.setTranscriptionLanguage(language);
            triggerRealtimeSettingChange();
        }
    }

    public void setTranscriptionPrompt(String prompt) {
        if (!prompt.equals(settingsRepository.getTranscriptionPrompt())) {
            settingsRepository.setTranscriptionPrompt(prompt);
            triggerRealtimeSettingChange();
        }
    }

    public void setTurnDetectionType(String type) {
        if (!type.equals(settingsRepository.getTurnDetectionType())) {
            settingsRepository.setTurnDetectionType(type);
            triggerRealtimeSettingChange();
        }
    }

    public void setVadThreshold(float threshold) {
        if (threshold != settingsRepository.getVadThreshold()) {
            settingsRepository.setVadThreshold(threshold);
            triggerRealtimeSettingChange();
        }
    }

    public void setPrefixPadding(int padding) {
        if (padding != settingsRepository.getPrefixPadding()) {
            settingsRepository.setPrefixPadding(padding);
            triggerRealtimeSettingChange();
        }
    }

    public void setSilenceDuration(int duration) {
        if (duration != settingsRepository.getSilenceDuration()) {
            settingsRepository.setSilenceDuration(duration);
            triggerRealtimeSettingChange();
        }
    }

    public void setIdleTimeout(int timeout) {
        Integer current = settingsRepository.getIdleTimeout();
        if (current == null || timeout != current) {
            settingsRepository.setIdleTimeout(timeout);
            triggerRealtimeSettingChange();
        }
    }

    public void setEagerness(String eagerness) {
        if (!eagerness.equals(settingsRepository.getEagerness())) {
            settingsRepository.setEagerness(eagerness);
            triggerRealtimeSettingChange();
        }
    }

    public void setNoiseReduction(String reduction) {
        if (!reduction.equals(settingsRepository.getNoiseReduction())) {
            settingsRepository.setNoiseReduction(reduction);
            triggerRealtimeSettingChange();
        }
    }

    private void triggerRealtimeSettingChange() {
        if (settingsRepository.isUsingRealtimeAudioInput()) {
            updateSessionEvent.setValue(true);
        }
    }

    // Getters for UI Initialization
    public String getSystemPrompt() {
        return settingsRepository.getSystemPrompt();
    }

    public String getModel() {
        return settingsRepository.getModel();
    }

    public String getVoice() {
        return settingsRepository.getVoice();
    }

    public int getSpeedProgress() {
        return settingsRepository.getSpeedProgress();
    }

    public String getLanguage() {
        return settingsRepository.getLanguage();
    }

    public String getApiProvider() {
        return settingsRepository.getApiProvider();
    }

    public String getAudioInputMode() {
        return settingsRepository.getAudioInputMode();
    }

    public int getTemperatureProgress() {
        return settingsRepository.getTemperatureProgress();
    }

    public int getVolume() {
        return settingsRepository.getVolume();
    }

    public int getSilenceTimeout() {
        return settingsRepository.getSilenceTimeout();
    }

    public float getConfidenceThreshold() {
        return settingsRepository.getConfidenceThreshold();
    }

    public Set<String> getEnabledTools() {
        return settingsRepository.getEnabledTools();
    }

    public String getTranscriptionModel() {
        return settingsRepository.getTranscriptionModel();
    }

    public String getTranscriptionLanguage() {
        return settingsRepository.getTranscriptionLanguage();
    }

    public String getTranscriptionPrompt() {
        return settingsRepository.getTranscriptionPrompt();
    }

    public String getTurnDetectionType() {
        return settingsRepository.getTurnDetectionType();
    }

    public float getVadThreshold() {
        return settingsRepository.getVadThreshold();
    }

    public int getPrefixPadding() {
        return settingsRepository.getPrefixPadding();
    }

    public int getSilenceDuration() {
        return settingsRepository.getSilenceDuration();
    }

    public Integer getIdleTimeout() {
        return settingsRepository.getIdleTimeout();
    }

    public String getEagerness() {
        return settingsRepository.getEagerness();
    }

    public String getNoiseReduction() {
        return settingsRepository.getNoiseReduction();
    }

    // Event consumption methods for ChatActivity
    public void consumeRestartSessionEvent() {
        restartSessionEvent.setValue(false);
    }

    public void consumeUpdateSessionEvent() {
        updateSessionEvent.setValue(false);
    }

    public void consumeRestartRecognizerEvent() {
        restartRecognizerEvent.setValue(false);
    }
}
