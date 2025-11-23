package io.github.anonymous.pepper_realtime.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;
import io.github.anonymous.pepper_realtime.R;
import io.github.anonymous.pepper_realtime.network.RealtimeApiProvider;
import io.github.anonymous.pepper_realtime.tools.ToolRegistry;

@Singleton
public class SettingsRepository {

    private static final String PREFS_NAME = "PepperDialogPrefs";
    private static final String KEY_SYSTEM_PROMPT = "systemPrompt";
    private static final String KEY_MODEL = "model";
    private static final String KEY_VOICE = "voice";
    private static final String KEY_SPEED = "speed";
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_VOLUME = "volume";
    private static final String KEY_SILENCE_TIMEOUT = "silenceTimeout";
    private static final String KEY_ENABLED_TOOLS = "enabledTools";
    private static final String KEY_API_PROVIDER = "apiProvider";
    private static final String KEY_CONFIDENCE_THRESHOLD = "confidenceThreshold";
    private static final String KEY_AUDIO_INPUT_MODE = "audioInputMode";

    // Realtime API specific settings
    private static final String KEY_TRANSCRIPTION_MODEL = "transcriptionModel";
    private static final String KEY_TRANSCRIPTION_LANGUAGE = "transcriptionLanguage";
    private static final String KEY_TRANSCRIPTION_PROMPT = "transcriptionPrompt";
    private static final String KEY_TURN_DETECTION_TYPE = "turnDetectionType";
    private static final String KEY_VAD_THRESHOLD = "vadThreshold";
    private static final String KEY_PREFIX_PADDING = "prefixPadding";
    private static final String KEY_SILENCE_DURATION = "silenceDuration";
    private static final String KEY_IDLE_TIMEOUT = "idleTimeout";
    private static final String KEY_NOISE_REDUCTION = "noiseReduction";
    private static final String KEY_EAGERNESS = "eagerness";

    // Audio input mode constants
    public static final String MODE_REALTIME_API = "realtime_api";
    public static final String MODE_AZURE_SPEECH = "azure_speech";

    private final SharedPreferences settings;
    private final Context context;

    @Inject
    public SettingsRepository(@ApplicationContext Context context) {
        this.context = context;
        this.settings = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public SharedPreferences getSettings() {
        return settings;
    }

    public String getSystemPrompt() {
        return settings.getString(KEY_SYSTEM_PROMPT, context.getString(R.string.default_system_prompt));
    }

    public void setSystemPrompt(String prompt) {
        settings.edit().putString(KEY_SYSTEM_PROMPT, prompt).apply();
    }

    public String getModel() {
        return settings.getString(KEY_MODEL, context.getString(R.string.openai_default_model));
    }

    public void setModel(String model) {
        settings.edit().putString(KEY_MODEL, model).apply();
    }

    public String getVoice() {
        return settings.getString(KEY_VOICE, "ash");
    }

    public void setVoice(String voice) {
        settings.edit().putString(KEY_VOICE, voice).apply();
    }

    public float getSpeed() {
        int speedProgress = settings.getInt(KEY_SPEED, 100);
        return speedProgress / 100f; // Convert 25-150 to 0.25-1.5
    }

    public int getSpeedProgress() {
        return settings.getInt(KEY_SPEED, 100);
    }

    public void setSpeedProgress(int progress) {
        settings.edit().putInt(KEY_SPEED, progress).apply();
    }

    public String getLanguage() {
        return settings.getString(KEY_LANGUAGE, "en-US");
    }

    public void setLanguage(String language) {
        settings.edit().putString(KEY_LANGUAGE, language).apply();
    }

    public String getAudioInputMode() {
        return settings.getString(KEY_AUDIO_INPUT_MODE, MODE_REALTIME_API);
    }

    public void setAudioInputMode(String mode) {
        settings.edit().putString(KEY_AUDIO_INPUT_MODE, mode).apply();
    }

    public boolean isUsingRealtimeAudioInput() {
        return MODE_REALTIME_API.equals(getAudioInputMode());
    }

    public int getVolume() {
        return settings.getInt(KEY_VOLUME, 80);
    }

    public void setVolume(int volume) {
        settings.edit().putInt(KEY_VOLUME, volume).apply();
    }

    public int getSilenceTimeout() {
        return settings.getInt(KEY_SILENCE_TIMEOUT, 500);
    }

    public void setSilenceTimeout(int timeout) {
        settings.edit().putInt(KEY_SILENCE_TIMEOUT, timeout).apply();
    }

    public float getTemperature() {
        int tempProgress = settings.getInt(KEY_TEMPERATURE, 33);
        if (tempProgress < 0 || tempProgress > 100)
            tempProgress = 33;
        return 0.6f + (tempProgress / 100.0f) * 0.6f;
    }

    public int getTemperatureProgress() {
        return settings.getInt(KEY_TEMPERATURE, 33);
    }

    public void setTemperatureProgress(int progress) {
        settings.edit().putInt(KEY_TEMPERATURE, progress).apply();
    }

    public float getConfidenceThreshold() {
        return settings.getFloat(KEY_CONFIDENCE_THRESHOLD, 0.7f);
    }

    public void setConfidenceThreshold(float threshold) {
        settings.edit().putFloat(KEY_CONFIDENCE_THRESHOLD, threshold).apply();
    }

    public String getApiProvider() {
        return settings.getString(KEY_API_PROVIDER, RealtimeApiProvider.OPENAI_DIRECT.name());
    }

    public RealtimeApiProvider getApiProviderEnum() {
        return RealtimeApiProvider.fromString(getApiProvider());
    }

    public void setApiProvider(String provider) {
        settings.edit().putString(KEY_API_PROVIDER, provider).apply();
    }

    public Set<String> getEnabledTools() {
        Set<String> defaultTools = getDefaultEnabledTools();
        Set<String> savedTools = settings.getStringSet(KEY_ENABLED_TOOLS, null);

        if (savedTools == null) {
            return defaultTools;
        } else {
            Set<String> mergedTools = new HashSet<>(savedTools);
            for (String defaultTool : defaultTools) {
                if (!mergedTools.contains(defaultTool)) {
                    mergedTools.add(defaultTool);
                    Log.i("SettingsRepository", "Auto-enabling new tool: " + defaultTool);
                }
            }
            if (!mergedTools.equals(savedTools)) {
                setEnabledTools(mergedTools);
            }
            return mergedTools;
        }
    }

    public void setEnabledTools(Set<String> enabledTools) {
        settings.edit().putStringSet(KEY_ENABLED_TOOLS, enabledTools).apply();
    }

    private Set<String> getDefaultEnabledTools() {
        ToolRegistry registry = new ToolRegistry();
        return new HashSet<>(registry.getAllToolNames());
    }

    // Realtime API Settings Getters/Setters
    public String getTranscriptionModel() {
        return settings.getString(KEY_TRANSCRIPTION_MODEL, "whisper-1");
    }

    public void setTranscriptionModel(String model) {
        settings.edit().putString(KEY_TRANSCRIPTION_MODEL, model).apply();
    }

    public String getTranscriptionLanguage() {
        return settings.getString(KEY_TRANSCRIPTION_LANGUAGE, "");
    }

    public void setTranscriptionLanguage(String language) {
        settings.edit().putString(KEY_TRANSCRIPTION_LANGUAGE, language).apply();
    }

    public String getTranscriptionPrompt() {
        return settings.getString(KEY_TRANSCRIPTION_PROMPT, "");
    }

    public void setTranscriptionPrompt(String prompt) {
        settings.edit().putString(KEY_TRANSCRIPTION_PROMPT, prompt).apply();
    }

    public String getTurnDetectionType() {
        return settings.getString(KEY_TURN_DETECTION_TYPE, "server_vad");
    }

    public void setTurnDetectionType(String type) {
        settings.edit().putString(KEY_TURN_DETECTION_TYPE, type).apply();
    }

    public float getVadThreshold() {
        return settings.getFloat(KEY_VAD_THRESHOLD, 0.5f);
    }

    public void setVadThreshold(float threshold) {
        settings.edit().putFloat(KEY_VAD_THRESHOLD, threshold).apply();
    }

    public int getPrefixPadding() {
        return settings.getInt(KEY_PREFIX_PADDING, 300);
    }

    public void setPrefixPadding(int padding) {
        settings.edit().putInt(KEY_PREFIX_PADDING, padding).apply();
    }

    public int getSilenceDuration() {
        return settings.getInt(KEY_SILENCE_DURATION, 500);
    }

    public void setSilenceDuration(int duration) {
        settings.edit().putInt(KEY_SILENCE_DURATION, duration).apply();
    }

    public Integer getIdleTimeout() {
        return settings.getInt(KEY_IDLE_TIMEOUT, 0);
    }

    public void setIdleTimeout(int timeout) {
        settings.edit().putInt(KEY_IDLE_TIMEOUT, timeout).apply();
    }

    public String getNoiseReduction() {
        return settings.getString(KEY_NOISE_REDUCTION, "off");
    }

    public void setNoiseReduction(String reduction) {
        settings.edit().putString(KEY_NOISE_REDUCTION, reduction).apply();
    }

    public String getEagerness() {
        return settings.getString(KEY_EAGERNESS, "auto");
    }

    public void setEagerness(String eagerness) {
        settings.edit().putString(KEY_EAGERNESS, eagerness).apply();
    }
}
