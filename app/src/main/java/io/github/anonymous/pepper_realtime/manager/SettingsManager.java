package io.github.anonymous.pepper_realtime.manager;

import android.content.SharedPreferences;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;

import io.github.anonymous.pepper_realtime.ui.ChatActivity;
import io.github.anonymous.pepper_realtime.R;
import io.github.anonymous.pepper_realtime.network.RealtimeApiProvider;

@SuppressWarnings("SpellCheckingInspection")
public class SettingsManager {

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

    private final ChatActivity activity;
    private final SharedPreferences settings;
    
    // Settings UI
    private EditText systemPromptInput;
    private Spinner modelSpinner;
    private Spinner apiProviderSpinner;
    private Spinner voiceSpinner;
    private SeekBar speedSeekBar;
    private TextView speedValue;
    private Spinner languageSpinner;
    private Spinner audioInputModeSpinner;
    private SeekBar temperatureSeekBar;
    private TextView temperatureValue;
    private SeekBar volumeSeekBar;
    private TextView volumeValue;
    private SeekBar silenceTimeoutSeekBar;
    private TextView silenceTimeoutValue;
    private SeekBar confidenceThresholdSeekBar;
    private TextView confidenceThresholdValue;
    private android.widget.LinearLayout functionCallsContainer;
    
    // Container visibility management
    private LinearLayout azureSpeechSettingsContainer;
    private LinearLayout realtimeApiSettingsContainer;
    private LinearLayout serverVadSettingsContainer;
    private LinearLayout semanticVadSettingsContainer;
    
    // Realtime API specific UI elements
    private Spinner transcriptionModelSpinner;
    private EditText transcriptionLanguageInput;
    private EditText transcriptionPromptInput;
    private Spinner turnDetectionTypeSpinner;
    private SeekBar vadThresholdSeekBar;
    private TextView vadThresholdValue;
    private SeekBar prefixPaddingSeekBar;
    private TextView prefixPaddingValue;
    private SeekBar silenceDurationSeekBar;
    private TextView silenceDurationValue;
    private EditText idleTimeoutInput;
    private Spinner eagernessSpinner;
    private Spinner noiseReductionSpinner;

    public interface SettingsListener {
        void onSettingsChanged();
        void onRecognizerSettingsChanged();
        void onVolumeChanged(int volume);
        void onToolsChanged();
    }

    private SettingsListener listener;

    public SettingsManager(ChatActivity activity, View settingsView) {
        this.activity = activity;
        this.settings = activity.getSharedPreferences(PREFS_NAME, ChatActivity.MODE_PRIVATE);
        initializeViews(settingsView);
        setupListeners();
        loadSettings();
    }

    public void setListener(SettingsListener listener) {
        this.listener = listener;
    }

    private void initializeViews(View navigationView) {
        systemPromptInput = navigationView.findViewById(R.id.system_prompt_input);
        modelSpinner = navigationView.findViewById(R.id.model_spinner);
        apiProviderSpinner = navigationView.findViewById(R.id.api_provider_spinner);
        voiceSpinner = navigationView.findViewById(R.id.voice_spinner);
        speedSeekBar = navigationView.findViewById(R.id.speed_seekbar);
        speedValue = navigationView.findViewById(R.id.speed_value);
        languageSpinner = navigationView.findViewById(R.id.language_spinner);
        audioInputModeSpinner = navigationView.findViewById(R.id.audio_input_mode_spinner);
        temperatureSeekBar = navigationView.findViewById(R.id.temperature_seekbar);
        temperatureValue = navigationView.findViewById(R.id.temperature_value);
        volumeSeekBar = navigationView.findViewById(R.id.volume_seekbar);
        volumeValue = navigationView.findViewById(R.id.volume_value);
        silenceTimeoutSeekBar = navigationView.findViewById(R.id.silence_timeout_seekbar);
        silenceTimeoutValue = navigationView.findViewById(R.id.silence_timeout_value);
        confidenceThresholdSeekBar = navigationView.findViewById(R.id.confidence_threshold_seekbar);
        confidenceThresholdValue = navigationView.findViewById(R.id.confidence_threshold_value);
        functionCallsContainer = navigationView.findViewById(R.id.function_calls_container);
        
        // Container views
        azureSpeechSettingsContainer = navigationView.findViewById(R.id.azure_speech_settings_container);
        realtimeApiSettingsContainer = navigationView.findViewById(R.id.realtime_api_settings_container);
        serverVadSettingsContainer = navigationView.findViewById(R.id.server_vad_settings_container);
        semanticVadSettingsContainer = navigationView.findViewById(R.id.semantic_vad_settings_container);
        
        // Realtime API specific views
        transcriptionModelSpinner = navigationView.findViewById(R.id.transcription_model_spinner);
        transcriptionLanguageInput = navigationView.findViewById(R.id.transcription_language_input);
        transcriptionPromptInput = navigationView.findViewById(R.id.transcription_prompt_input);
        turnDetectionTypeSpinner = navigationView.findViewById(R.id.turn_detection_type_spinner);
        vadThresholdSeekBar = navigationView.findViewById(R.id.vad_threshold_seekbar);
        vadThresholdValue = navigationView.findViewById(R.id.vad_threshold_value);
        prefixPaddingSeekBar = navigationView.findViewById(R.id.prefix_padding_seekbar);
        prefixPaddingValue = navigationView.findViewById(R.id.prefix_padding_value);
        silenceDurationSeekBar = navigationView.findViewById(R.id.silence_duration_seekbar);
        silenceDurationValue = navigationView.findViewById(R.id.silence_duration_value);
        idleTimeoutInput = navigationView.findViewById(R.id.idle_timeout_input);
        eagernessSpinner = navigationView.findViewById(R.id.eagerness_spinner);
        noiseReductionSpinner = navigationView.findViewById(R.id.noise_reduction_spinner);
    }

    private void loadSettings() {
        // Populate API Provider Spinner
        ApiKeyManager keyManager = new ApiKeyManager(activity);
        RealtimeApiProvider[] configuredProviders = keyManager.getConfiguredProviders();
        
        if (configuredProviders.length > 0) {
            ArrayAdapter<RealtimeApiProvider> providerAdapter = new ArrayAdapter<RealtimeApiProvider>(activity, android.R.layout.simple_spinner_item, configuredProviders) {
                @Override
                public View getView(int position, View convertView, android.view.ViewGroup parent) {
                    if (convertView == null) {
                        convertView = activity.getLayoutInflater().inflate(android.R.layout.simple_spinner_item, parent, false);
                    }
                    TextView textView = (TextView) convertView;
                    textView.setText(getItem(position).getDisplayName());
                    return convertView;
                }
                
                @Override
                public View getDropDownView(int position, View convertView, @NonNull android.view.ViewGroup parent) {
                    if (convertView == null) {
                        convertView = activity.getLayoutInflater().inflate(android.R.layout.simple_spinner_dropdown_item, parent, false);
                    }
                    TextView textView = (TextView) convertView;
                    RealtimeApiProvider provider = getItem(position);
                    textView.setText(provider.getDisplayName());
                    return convertView;
                }
            };
            apiProviderSpinner.setAdapter(providerAdapter);
        } else {
            // No providers configured - show message
            String[] noProviders = { "No API providers configured" };
            ArrayAdapter<String> emptyAdapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, noProviders);
            emptyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            apiProviderSpinner.setAdapter(emptyAdapter);
            apiProviderSpinner.setEnabled(false);
        }

        // Populate Model Spinner (provider-agnostic: all supported models)
        updateModelSpinnerForProvider(null);

        // Populate Voice Spinner (includes new GA voices: cedar, marin)
        String[] voices = { "alloy", "ash", "ballad", "cedar", "coral", "echo", "marin", "sage", "shimmer", "verse" };
        ArrayAdapter<String> voiceAdapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, voices);
        voiceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        voiceSpinner.setAdapter(voiceAdapter);

        // Populate Language Spinner
        List<LanguageOption> languages = getAvailableLanguages();
        ArrayAdapter<LanguageOption> languageAdapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, languages);
        languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(languageAdapter);

        // Load saved settings
        systemPromptInput.setText(settings.getString(KEY_SYSTEM_PROMPT, activity.getString(R.string.default_system_prompt)));
        
        // Set API Provider selection (default: OPENAI_DIRECT)
        if (configuredProviders.length > 0) {
            RealtimeApiProvider savedProvider = RealtimeApiProvider.fromString(settings.getString(KEY_API_PROVIDER, RealtimeApiProvider.OPENAI_DIRECT.name()));
            for (int i = 0; i < configuredProviders.length; i++) {
                if (configuredProviders[i] == savedProvider) {
                    apiProviderSpinner.setSelection(i);
                    break;
                }
            }
        }
        
        // Set model selection (provider-agnostic; default to gpt-realtime)
        @SuppressWarnings("unchecked")
        ArrayAdapter<String> currentModelAdapter = (ArrayAdapter<String>) modelSpinner.getAdapter();
        if (currentModelAdapter != null) {
            String savedModel = settings.getString(KEY_MODEL, activity.getString(R.string.openai_default_model));
            int modelPosition = currentModelAdapter.getPosition(savedModel);
            if (modelPosition >= 0) {
                modelSpinner.setSelection(modelPosition);
            }
        }
        
        voiceSpinner.setSelection(voiceAdapter.getPosition(settings.getString(KEY_VOICE, "ash")));
        
        // Speed setting (0.25 to 1.5, stored as 25 to 150)
        int speedProgress = settings.getInt(KEY_SPEED, 100); // Default: 1.0x speed
        if (speedProgress < 25 || speedProgress > 150) speedProgress = 100;
        speedSeekBar.setProgress(speedProgress);
        speedValue.setText(activity.getString(R.string.speed_format, speedProgress / 100f));

        String savedLangCode = settings.getString(KEY_LANGUAGE, "en-US");
        for (int i = 0; i < languages.size(); i++) {
            if (languages.get(i).getCode().equals(savedLangCode)) {
                languageSpinner.setSelection(i);
                break;
            }
        }

        int tempProgress = settings.getInt(KEY_TEMPERATURE, 33);
        if (tempProgress < 0 || tempProgress > 100) tempProgress = 33;
        temperatureSeekBar.setProgress(tempProgress);
        temperatureValue.setText(activity.getString(R.string.temperature_format, convertProgressToTemperature(tempProgress)));

        int volProgress = settings.getInt(KEY_VOLUME, 80);
        volumeSeekBar.setProgress(volProgress);
        volumeValue.setText(activity.getString(R.string.volume_format, volProgress));

        int silenceTimeout = settings.getInt(KEY_SILENCE_TIMEOUT, 500);
        silenceTimeoutSeekBar.setProgress(silenceTimeout);
        silenceTimeoutValue.setText(activity.getString(R.string.silence_timeout_format, silenceTimeout));

        // Confidence threshold (0-100 stored as float percent)
        int confProgress = Math.round(settings.getFloat(KEY_CONFIDENCE_THRESHOLD, 0.7f) * 100f);
        if (confProgress < 0 || confProgress > 100) confProgress = 70;
        confidenceThresholdSeekBar.setProgress(confProgress);
        confidenceThresholdValue.setText(activity.getString(R.string.confidence_threshold_format, confProgress));

        // Audio Input Mode (default: Realtime API)
        String savedInputMode = settings.getString(KEY_AUDIO_INPUT_MODE, MODE_REALTIME_API);
        int inputModePosition = MODE_REALTIME_API.equals(savedInputMode) ? 0 : 1;
        audioInputModeSpinner.setSelection(inputModePosition);
        
        // Load Realtime API Settings
        String savedTranscriptionModel = settings.getString(KEY_TRANSCRIPTION_MODEL, "whisper-1");
        ArrayAdapter<CharSequence> transcriptionAdapter = ArrayAdapter.createFromResource(
            activity, R.array.transcription_models, android.R.layout.simple_spinner_item);
        int transcriptionModelPosition = 0;
        for (int i = 0; i < transcriptionAdapter.getCount(); i++) {
            if (transcriptionAdapter.getItem(i).toString().equals(savedTranscriptionModel)) {
                transcriptionModelPosition = i;
                break;
            }
        }
        transcriptionModelSpinner.setSelection(transcriptionModelPosition);
        
        transcriptionLanguageInput.setText(settings.getString(KEY_TRANSCRIPTION_LANGUAGE, ""));
        transcriptionPromptInput.setText(settings.getString(KEY_TRANSCRIPTION_PROMPT, ""));
        
        String savedTurnDetectionType = settings.getString(KEY_TURN_DETECTION_TYPE, "server_vad");
        int turnDetectionPosition = savedTurnDetectionType.equals("semantic_vad") ? 1 : 0;
        turnDetectionTypeSpinner.setSelection(turnDetectionPosition);
        
        int vadThresholdProgress = Math.round(settings.getFloat(KEY_VAD_THRESHOLD, 0.5f) * 100f);
        vadThresholdSeekBar.setProgress(vadThresholdProgress);
        vadThresholdValue.setText(activity.getString(R.string.realtime_vad_threshold_format, vadThresholdProgress / 100f));
        
        int prefixPadding = settings.getInt(KEY_PREFIX_PADDING, 300);
        prefixPaddingSeekBar.setProgress(prefixPadding);
        prefixPaddingValue.setText(activity.getString(R.string.realtime_prefix_padding_format, prefixPadding));
        
        int silenceDuration = settings.getInt(KEY_SILENCE_DURATION, 500);
        silenceDurationSeekBar.setProgress(silenceDuration);
        silenceDurationValue.setText(activity.getString(R.string.realtime_silence_duration_format, silenceDuration));
        
        int idleTimeout = settings.getInt(KEY_IDLE_TIMEOUT, 0);
        if (idleTimeout > 0) {
            idleTimeoutInput.setText(String.valueOf(idleTimeout));
        }
        
        String savedEagerness = settings.getString(KEY_EAGERNESS, "auto");
        int eagernessPosition = 0;
        if (savedEagerness.equals("low")) eagernessPosition = 1;
        else if (savedEagerness.equals("medium")) eagernessPosition = 2;
        else if (savedEagerness.equals("high")) eagernessPosition = 3;
        eagernessSpinner.setSelection(eagernessPosition);
        
        String savedNoiseReduction = settings.getString(KEY_NOISE_REDUCTION, "off");
        int noiseReductionPosition = 0;
        if (savedNoiseReduction.equals("near_field")) noiseReductionPosition = 1;
        else if (savedNoiseReduction.equals("far_field")) noiseReductionPosition = 2;
        noiseReductionSpinner.setSelection(noiseReductionPosition);
        
        // Set initial visibility based on audio input mode
        updateSettingsVisibility(MODE_REALTIME_API.equals(savedInputMode));
        
        // Set initial VAD settings visibility based on turn detection type
        updateVadSettingsVisibility(savedTurnDetectionType.equals("server_vad"));

        // Setup Function Calls UI
        setupFunctionCallsUI();
    }

    private void setupListeners() {
        temperatureSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                temperatureValue.setText(activity.getString(R.string.temperature_format, convertProgressToTemperature(progress)));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                settings.edit().putInt(KEY_TEMPERATURE, seekBar.getProgress()).apply();
            }
        });
        
        speedSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                speedValue.setText(activity.getString(R.string.speed_format, progress / 100f));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                settings.edit().putInt(KEY_SPEED, seekBar.getProgress()).apply();
            }
        });

        volumeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                volumeValue.setText(activity.getString(R.string.volume_format, progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                int progress = seekBar.getProgress();
                settings.edit().putInt(KEY_VOLUME, progress).apply();
                if (listener != null) listener.onVolumeChanged(progress);
            }
        });

        silenceTimeoutSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                silenceTimeoutValue.setText(activity.getString(R.string.silence_timeout_format, progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                int newTimeout = seekBar.getProgress();
                settings.edit().putInt(KEY_SILENCE_TIMEOUT, newTimeout).apply();
                if (listener != null) listener.onRecognizerSettingsChanged();
            }
        });

        confidenceThresholdSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                confidenceThresholdValue.setText(activity.getString(R.string.confidence_threshold_format, progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                float newThreshold = seekBar.getProgress() / 100f;
                settings.edit().putFloat(KEY_CONFIDENCE_THRESHOLD, newThreshold).apply();
            }
        });
        
        // Audio Input Mode Spinner - Toggle visibility
        audioInputModeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                updateSettingsVisibility(position == 0); // true = Realtime API, false = Azure
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        
        // Turn Detection Type Spinner - Toggle VAD settings visibility
        turnDetectionTypeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                updateVadSettingsVisibility(position == 0); // true = Server VAD, false = Semantic VAD
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        
        // Realtime API Settings Listeners
        vadThresholdSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                vadThresholdValue.setText(activity.getString(R.string.realtime_vad_threshold_format, progress / 100f));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                float threshold = seekBar.getProgress() / 100f;
                settings.edit().putFloat(KEY_VAD_THRESHOLD, threshold).apply();
            }
        });
        
        prefixPaddingSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                prefixPaddingValue.setText(activity.getString(R.string.realtime_prefix_padding_format, progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                settings.edit().putInt(KEY_PREFIX_PADDING, seekBar.getProgress()).apply();
            }
        });
        
        silenceDurationSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                silenceDurationValue.setText(activity.getString(R.string.realtime_silence_duration_format, progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                settings.edit().putInt(KEY_SILENCE_DURATION, seekBar.getProgress()).apply();
            }
        });
    }
    
    private void updateSettingsVisibility(boolean isRealtimeMode) {
        if (isRealtimeMode) {
            azureSpeechSettingsContainer.setVisibility(View.GONE);
            realtimeApiSettingsContainer.setVisibility(View.VISIBLE);
        } else {
            azureSpeechSettingsContainer.setVisibility(View.VISIBLE);
            realtimeApiSettingsContainer.setVisibility(View.GONE);
        }
    }
    
    private void updateVadSettingsVisibility(boolean isServerVad) {
        if (isServerVad) {
            serverVadSettingsContainer.setVisibility(View.VISIBLE);
            semanticVadSettingsContainer.setVisibility(View.GONE);
        } else {
            serverVadSettingsContainer.setVisibility(View.GONE);
            semanticVadSettingsContainer.setVisibility(View.VISIBLE);
        }
    }

    public void onDrawerClosed() {
        String oldModel = settings.getString(KEY_MODEL, "gpt-realtime");
        String oldVoice = settings.getString(KEY_VOICE, "ash");
        int oldSpeed = settings.getInt(KEY_SPEED, 100);
        String oldLang = settings.getString(KEY_LANGUAGE, "en-US");
        String oldPrompt = settings.getString(KEY_SYSTEM_PROMPT, "");
        String oldProvider = settings.getString(KEY_API_PROVIDER, RealtimeApiProvider.OPENAI_DIRECT.name());
        String oldInputMode = settings.getString(KEY_AUDIO_INPUT_MODE, MODE_REALTIME_API);
        int oldTemp = settings.getInt(KEY_TEMPERATURE, 33);
        if (oldTemp < 0 || oldTemp > 100) oldTemp = 33;
        Set<String> oldTools = getEnabledTools();
        
        String newModel = (String) modelSpinner.getSelectedItem();
        String newVoice = (String) voiceSpinner.getSelectedItem();
        int newSpeed = speedSeekBar.getProgress();
        LanguageOption selectedLang = (LanguageOption) languageSpinner.getSelectedItem();
        String newLang = selectedLang.getCode();
        String newPrompt = systemPromptInput.getText().toString();
        String newProvider = getSelectedApiProvider();
        String newInputMode = audioInputModeSpinner.getSelectedItemPosition() == 0 ? MODE_REALTIME_API : MODE_AZURE_SPEECH;
        int newTemp = temperatureSeekBar.getProgress();
        Set<String> newTools = getCurrentlySelectedTools();

        // Capture new Realtime API settings
        String newTranscriptionModel = (String) transcriptionModelSpinner.getSelectedItem();
        String newTranscriptionLanguage = transcriptionLanguageInput.getText().toString();
        String newTranscriptionPrompt = transcriptionPromptInput.getText().toString();
        String newTurnDetectionType = turnDetectionTypeSpinner.getSelectedItemPosition() == 0 ? "server_vad" : "semantic_vad";
        float newVadThreshold = vadThresholdSeekBar.getProgress() / 100f;
        int newPrefixPadding = prefixPaddingSeekBar.getProgress();
        int newSilenceDuration = silenceDurationSeekBar.getProgress();
        int newIdleTimeout = 0;
        try {
            String idleTimeoutStr = idleTimeoutInput.getText().toString().trim();
            if (!idleTimeoutStr.isEmpty()) {
                newIdleTimeout = Integer.parseInt(idleTimeoutStr);
            }
        } catch (NumberFormatException e) {
            // Invalid input, default to 0
        }
        String newEagerness = "auto";
        int eagernessPos = eagernessSpinner.getSelectedItemPosition();
        if (eagernessPos == 1) newEagerness = "low";
        else if (eagernessPos == 2) newEagerness = "medium";
        else if (eagernessPos == 3) newEagerness = "high";
        
        String newNoiseReduction = "off";
        int noiseReductionPos = noiseReductionSpinner.getSelectedItemPosition();
        if (noiseReductionPos == 1) newNoiseReduction = "near_field";
        else if (noiseReductionPos == 2) newNoiseReduction = "far_field";
        
        // Get old Realtime API settings to check for changes
        String oldTranscriptionModel = settings.getString(KEY_TRANSCRIPTION_MODEL, "whisper-1");
        String oldTurnDetectionType = settings.getString(KEY_TURN_DETECTION_TYPE, "server_vad");
        float oldVadThreshold = settings.getFloat(KEY_VAD_THRESHOLD, 0.5f);
        int oldPrefixPadding = settings.getInt(KEY_PREFIX_PADDING, 300);
        int oldSilenceDuration = settings.getInt(KEY_SILENCE_DURATION, 500);
        String oldEagerness = settings.getString(KEY_EAGERNESS, "auto");
        String oldNoiseReduction = settings.getString(KEY_NOISE_REDUCTION, "off");
        
        boolean realtimeSettingsChanged = !oldTranscriptionModel.equals(newTranscriptionModel) ||
                                          !oldTurnDetectionType.equals(newTurnDetectionType) ||
                                          oldVadThreshold != newVadThreshold ||
                                          oldPrefixPadding != newPrefixPadding ||
                                          oldSilenceDuration != newSilenceDuration ||
                                          !oldEagerness.equals(newEagerness) ||
                                          !oldNoiseReduction.equals(newNoiseReduction);

        SharedPreferences.Editor editor = settings.edit();
        editor.putString(KEY_SYSTEM_PROMPT, newPrompt);
        editor.putString(KEY_MODEL, newModel);
        editor.putString(KEY_VOICE, newVoice);
        editor.putInt(KEY_SPEED, newSpeed);
        editor.putString(KEY_LANGUAGE, newLang);
        editor.putString(KEY_API_PROVIDER, newProvider);
        editor.putString(KEY_AUDIO_INPUT_MODE, newInputMode);
        editor.putInt(KEY_TEMPERATURE, newTemp);
        editor.putStringSet(KEY_ENABLED_TOOLS, newTools);
        
        // Save Realtime API settings
        editor.putString(KEY_TRANSCRIPTION_MODEL, newTranscriptionModel);
        editor.putString(KEY_TRANSCRIPTION_LANGUAGE, newTranscriptionLanguage);
        editor.putString(KEY_TRANSCRIPTION_PROMPT, newTranscriptionPrompt);
        editor.putString(KEY_TURN_DETECTION_TYPE, newTurnDetectionType);
        editor.putFloat(KEY_VAD_THRESHOLD, newVadThreshold);
        editor.putInt(KEY_PREFIX_PADDING, newPrefixPadding);
        editor.putInt(KEY_SILENCE_DURATION, newSilenceDuration);
        editor.putInt(KEY_IDLE_TIMEOUT, newIdleTimeout);
        editor.putString(KEY_EAGERNESS, newEagerness);
        editor.putString(KEY_NOISE_REDUCTION, newNoiseReduction);
        
        editor.apply();

        // Determine what type of change occurred and notify accordingly
        if (!oldProvider.equals(newProvider) || !oldModel.equals(newModel) || !oldVoice.equals(newVoice) || !oldInputMode.equals(newInputMode)) {
            // Provider, model, voice, or audio input mode change requires new session
            // Audio mode change needs full session restart for clean transition
            if (listener != null) listener.onSettingsChanged();
        } else if (realtimeSettingsChanged && MODE_REALTIME_API.equals(newInputMode)) {
            // Realtime API settings changed while in Realtime mode - requires session update
            if (listener != null) listener.onToolsChanged();
        } else if (!oldLang.equals(newLang)) {
            // Language change requires recognizer restart (only for Azure Speech mode)
            if (listener != null) listener.onRecognizerSettingsChanged();
        } else if (!oldTools.equals(newTools) || !oldPrompt.equals(newPrompt) || oldTemp != newTemp || oldSpeed != newSpeed) {
            // Tools, prompt, temperature, or speed change only requires session update
            if (listener != null) listener.onToolsChanged();
        }
    }

    public String getSystemPrompt() {
        return settings.getString(KEY_SYSTEM_PROMPT, activity.getString(R.string.default_system_prompt));
    }



    public String getModel() {
        return settings.getString(KEY_MODEL, activity.getString(R.string.openai_default_model));
    }

    public String getVoice() {
        return settings.getString(KEY_VOICE, "ash");
    }
    
    public float getSpeed() {
        int speedProgress = settings.getInt(KEY_SPEED, 100);
        return speedProgress / 100f; // Convert 25-150 to 0.25-1.5
    }

    public String getLanguage() {
        return settings.getString(KEY_LANGUAGE, "en-US");
    }
    
    public String getAudioInputMode() {
        return settings.getString(KEY_AUDIO_INPUT_MODE, MODE_REALTIME_API);
    }
    
    public boolean isUsingRealtimeAudioInput() {
        return MODE_REALTIME_API.equals(getAudioInputMode());
    }
    
    public int getVolume() {
        return settings.getInt(KEY_VOLUME, 80);
    }

    public int getSilenceTimeout() {
        return settings.getInt(KEY_SILENCE_TIMEOUT, 500);
    }
    
    public float getTemperature() {
        int tempProgress = settings.getInt(KEY_TEMPERATURE, 33);
        if (tempProgress < 0 || tempProgress > 100) tempProgress = 33;
        return convertProgressToTemperature(tempProgress);
    }

    public Set<String> getEnabledTools() {
        Set<String> defaultTools = getDefaultEnabledTools();
        Set<String> savedTools = settings.getStringSet(KEY_ENABLED_TOOLS, null);
        
        if (savedTools == null) {
            // First time - return all default tools
            return defaultTools;
        } else {
            // Merge saved tools with new tools (for app updates)
            Set<String> mergedTools = new HashSet<>(savedTools);
            
            // Add any new tools that weren't in the saved list
            for (String defaultTool : defaultTools) {
                if (!mergedTools.contains(defaultTool)) {
                    mergedTools.add(defaultTool);
                    Log.i("SettingsManager", "Auto-enabling new tool: " + defaultTool);
                }
            }
            
            // Save merged list for next time
            if (!mergedTools.equals(savedTools)) {
                setEnabledTools(mergedTools);
                // Note: session.update will be triggered on next settings access
            }
            
            return mergedTools;
        }
    }

    public void setEnabledTools(Set<String> enabledTools) {
        settings.edit().putStringSet(KEY_ENABLED_TOOLS, enabledTools).apply();
    }

    private Set<String> getDefaultEnabledTools() {
        // By default, all tools should be enabled
        // Get all tools from new registry
        io.github.anonymous.pepper_realtime.tools.ToolRegistry registry = new io.github.anonymous.pepper_realtime.tools.ToolRegistry();
        return new HashSet<>(registry.getAllToolNames());
    }

    private void setupFunctionCallsUI() {
        if (functionCallsContainer == null) return;
        
        functionCallsContainer.removeAllViews();
        
        ApiKeyManager keyManager = new ApiKeyManager(activity);
        Set<String> enabledTools = getEnabledTools();
        
        // Get tool info from new registry
        io.github.anonymous.pepper_realtime.tools.ToolRegistry registry = new io.github.anonymous.pepper_realtime.tools.ToolRegistry();
        for (String toolId : registry.getAllToolNames()) {
            View toolItemView = activity.getLayoutInflater().inflate(R.layout.item_tool_setting, functionCallsContainer, false);
            
            CheckBox toolCheckbox = toolItemView.findViewById(R.id.tool_checkbox);
            TextView toolName = toolItemView.findViewById(R.id.tool_name);
            TextView toolApiKeyStatus = toolItemView.findViewById(R.id.tool_api_key_status);
            TextView toolDescription = toolItemView.findViewById(R.id.tool_description);
            ImageView expandIcon = toolItemView.findViewById(R.id.expand_icon);
            LinearLayout descriptionContainer = toolItemView.findViewById(R.id.description_container);
            
            // Set tool information
            toolName.setText(toolId);
            toolCheckbox.setChecked(enabledTools.contains(toolId));
            
            // Get the actual tool description from the tool definition
            try {
                io.github.anonymous.pepper_realtime.tools.Tool tool = registry.createTool(toolId);
                if (tool != null) {
                    JSONObject definition = tool.getDefinition();
                    String description = definition.optString("description", "No description available");
                    toolDescription.setText(description);
                } else {
                    toolDescription.setText("Tool not available");
                }
            } catch (Exception e) {
                toolDescription.setText("Error loading description");
            }
            
            // Check API key availability if required
            boolean isApiKeyAvailable = true;
            String apiKeyType = null;
            
            // Map tool names to their API key requirements
            switch (toolId) {
                case "analyze_vision":
                    apiKeyType = "Groq";
                    isApiKeyAvailable = keyManager.isVisionAnalysisAvailable();
                    break;
                case "search_internet":
                    apiKeyType = "Tavily";
                    isApiKeyAvailable = keyManager.isInternetSearchAvailable();
                    break;
                case "get_weather":
                    apiKeyType = "OpenWeatherMap";
                    isApiKeyAvailable = keyManager.isWeatherAvailable();
                    break;
                case "play_youtube_video":
                    apiKeyType = "YouTube";
                    isApiKeyAvailable = keyManager.isYouTubeAvailable();
                    break;
            }
            
            if (apiKeyType != null && !isApiKeyAvailable) {
                toolApiKeyStatus.setVisibility(View.VISIBLE);
                toolApiKeyStatus.setText(activity.getString(R.string.api_key_required_format, apiKeyType));
                toolCheckbox.setEnabled(false);
                toolCheckbox.setChecked(false);
            }
            
            // Setup expand/collapse functionality
            View.OnClickListener toggleDescription = v -> {
                boolean isExpanded = descriptionContainer.getVisibility() == View.VISIBLE;
                descriptionContainer.setVisibility(isExpanded ? View.GONE : View.VISIBLE);
                expandIcon.setRotation(isExpanded ? 0 : 180);
            };
            
            toolItemView.setOnClickListener(toggleDescription);
            expandIcon.setOnClickListener(toggleDescription);
            
            // Store tool name as tag for later retrieval
            toolCheckbox.setTag(toolId);
            
            functionCallsContainer.addView(toolItemView);
        }
    }

    public Set<String> getCurrentlySelectedTools() {
        Set<String> selectedTools = new HashSet<>();
        if (functionCallsContainer == null) return selectedTools;
        
        for (int i = 0; i < functionCallsContainer.getChildCount(); i++) {
            View toolItemView = functionCallsContainer.getChildAt(i);
            CheckBox toolCheckbox = toolItemView.findViewById(R.id.tool_checkbox);
            if (toolCheckbox != null && toolCheckbox.isChecked() && toolCheckbox.getTag() != null) {
                selectedTools.add((String) toolCheckbox.getTag());
            }
        }
        return selectedTools;
    }

    private float convertProgressToTemperature(int progress) {
        return 0.6f + (progress / 100.0f) * 0.6f;
    }

    private List<LanguageOption> getAvailableLanguages() {
        List<LanguageOption> languages = new ArrayList<>();
        
        // German variants
        languages.add(new LanguageOption("German (Switzerland)", "de-CH"));
        languages.add(new LanguageOption("German (Germany)", "de-DE"));
        languages.add(new LanguageOption("German (Austria)", "de-AT"));
        
        // English variants
        languages.add(new LanguageOption("English (United States)", "en-US"));
        languages.add(new LanguageOption("English (United Kingdom)", "en-GB"));
        languages.add(new LanguageOption("English (Australia)", "en-AU"));
        languages.add(new LanguageOption("English (Canada)", "en-CA"));
        
        // French variants
        languages.add(new LanguageOption("French (Switzerland)", "fr-CH"));
        languages.add(new LanguageOption("French (France)", "fr-FR"));
        languages.add(new LanguageOption("French (Canada)", "fr-CA"));
        
        // Italian variants
        languages.add(new LanguageOption("Italian (Switzerland)", "it-CH"));
        languages.add(new LanguageOption("Italian (Italy)", "it-IT"));
        
        // Spanish variants
        languages.add(new LanguageOption("Spanish (Spain)", "es-ES"));
        languages.add(new LanguageOption("Spanish (Argentina)", "es-AR"));
        languages.add(new LanguageOption("Spanish (Mexico)", "es-MX"));
        
        // Portuguese variants
        languages.add(new LanguageOption("Portuguese (Brazil)", "pt-BR"));
        languages.add(new LanguageOption("Portuguese (Portugal)", "pt-PT"));
        
        // Chinese variants
        languages.add(new LanguageOption("Chinese (Mandarin, Simplified)", "zh-CN"));
        languages.add(new LanguageOption("Chinese (Cantonese, Traditional)", "zh-HK"));
        languages.add(new LanguageOption("Chinese (Taiwanese Mandarin)", "zh-TW"));
        
        // Japanese
        languages.add(new LanguageOption("Japanese", "ja-JP"));
        
        // Korean
        languages.add(new LanguageOption("Korean", "ko-KR"));
        
        // Russian
        languages.add(new LanguageOption("Russian", "ru-RU"));
        
        // Arabic
        languages.add(new LanguageOption("Arabic (UAE)", "ar-AE"));
        languages.add(new LanguageOption("Arabic (Saudi Arabia)", "ar-SA"));
        
        // Dutch
        languages.add(new LanguageOption("Dutch (Netherlands)", "nl-NL"));
        
        // Norwegian
        languages.add(new LanguageOption("Norwegian (Bokmål)", "nb-NO"));
        
        // Swedish
        languages.add(new LanguageOption("Swedish", "sv-SE"));
        
        // Danish
        languages.add(new LanguageOption("Danish", "da-DK"));
        
        return languages;
    }

    /**
     * Update model spinner options (provider-agnostic)
     */
    private void updateModelSpinnerForProvider(RealtimeApiProvider provider) {
        String[] models = new String[]{ "gpt-realtime", "gpt-realtime-mini", "gpt-4o-realtime-preview", "gpt-4o-mini-realtime-preview" };
        ArrayAdapter<String> modelAdapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, models);
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modelSpinner.setAdapter(modelAdapter);
    }
    
    /**
     * Get default model (provider-agnostic): prefer OpenAI gpt-realtime
     */
    private String getDefaultModelForProvider(RealtimeApiProvider provider) {
        return activity.getString(R.string.openai_default_model);
    }
    
    /**
     * Get currently selected API provider
     */
    private String getSelectedApiProvider() {
        if (apiProviderSpinner.getAdapter() == null || apiProviderSpinner.getSelectedItem() == null) {
            return RealtimeApiProvider.OPENAI_DIRECT.name();
        }
        
        Object selectedItem = apiProviderSpinner.getSelectedItem();
        if (selectedItem instanceof RealtimeApiProvider) {
            return ((RealtimeApiProvider) selectedItem).name();
        }
        
        // Fallback for error cases
        return RealtimeApiProvider.OPENAI_DIRECT.name();
    }
    
    /**
     * Get currently selected API provider as enum
     */
    public RealtimeApiProvider getApiProvider() {
        return RealtimeApiProvider.fromString(settings.getString(KEY_API_PROVIDER, RealtimeApiProvider.OPENAI_DIRECT.name()));
    }
    
    public double getConfidenceThreshold() {
        return settings.getFloat(KEY_CONFIDENCE_THRESHOLD, 0.7f); // Default 70%
    }
    
    // Realtime API specific getters
    public String getTranscriptionModel() {
        return settings.getString(KEY_TRANSCRIPTION_MODEL, "whisper-1");
    }
    
    public String getTranscriptionLanguage() {
        return settings.getString(KEY_TRANSCRIPTION_LANGUAGE, "");
    }
    
    public String getTranscriptionPrompt() {
        return settings.getString(KEY_TRANSCRIPTION_PROMPT, "");
    }
    
    public String getTurnDetectionType() {
        return settings.getString(KEY_TURN_DETECTION_TYPE, "server_vad");
    }
    
    public float getVadThreshold() {
        return settings.getFloat(KEY_VAD_THRESHOLD, 0.5f);
    }
    
    public int getPrefixPadding() {
        return settings.getInt(KEY_PREFIX_PADDING, 300);
    }
    
    public int getSilenceDuration() {
        return settings.getInt(KEY_SILENCE_DURATION, 500);
    }
    
    public Integer getIdleTimeout() {
        int timeout = settings.getInt(KEY_IDLE_TIMEOUT, 0);
        return timeout > 0 ? timeout : null;
    }
    
    public String getEagerness() {
        return settings.getString(KEY_EAGERNESS, "auto");
    }
    
    public String getNoiseReduction() {
        return settings.getString(KEY_NOISE_REDUCTION, "off");
    }

    // A simple helper class to hold language display name and code
    private static class LanguageOption {
        private final String name;
        private final String code;

        LanguageOption(String name, String code) {
            this.name = name;
            this.code = code;
        }

        public String getCode() {
            return code;
        }

        @NonNull
        @Override
        public String toString() {
            return name;
        }
    }
}
