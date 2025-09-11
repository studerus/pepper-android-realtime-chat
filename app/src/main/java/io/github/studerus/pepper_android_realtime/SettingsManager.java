package io.github.studerus.pepper_android_realtime;

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

@SuppressWarnings("SpellCheckingInspection")
public class SettingsManager {

    private static final String PREFS_NAME = "PepperDialogPrefs";
    private static final String KEY_SYSTEM_PROMPT = "systemPrompt";
    private static final String KEY_MODEL = "model";
    private static final String KEY_VOICE = "voice";
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_VOLUME = "volume";
    private static final String KEY_SILENCE_TIMEOUT = "silenceTimeout";
    private static final String KEY_ENABLED_TOOLS = "enabledTools";
    private static final String KEY_API_PROVIDER = "apiProvider";
    private static final String KEY_CONFIDENCE_THRESHOLD = "confidenceThreshold";

    private final ChatActivity activity;
    private final SharedPreferences settings;
    
    // Settings UI
    private EditText systemPromptInput;
    private Spinner modelSpinner;
    private Spinner apiProviderSpinner;
    private Spinner voiceSpinner;
    private Spinner languageSpinner;
    private SeekBar temperatureSeekBar;
    private TextView temperatureValue;
    private SeekBar volumeSeekBar;
    private TextView volumeValue;
    private SeekBar silenceTimeoutSeekBar;
    private TextView silenceTimeoutValue;
    private SeekBar confidenceThresholdSeekBar;
    private TextView confidenceThresholdValue;
    private android.widget.LinearLayout functionCallsContainer;

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
        languageSpinner = navigationView.findViewById(R.id.language_spinner);
        temperatureSeekBar = navigationView.findViewById(R.id.temperature_seekbar);
        temperatureValue = navigationView.findViewById(R.id.temperature_value);
        volumeSeekBar = navigationView.findViewById(R.id.volume_seekbar);
        volumeValue = navigationView.findViewById(R.id.volume_value);
        silenceTimeoutSeekBar = navigationView.findViewById(R.id.silence_timeout_seekbar);
        silenceTimeoutValue = navigationView.findViewById(R.id.silence_timeout_value);
        confidenceThresholdSeekBar = navigationView.findViewById(R.id.confidence_threshold_seekbar);
        confidenceThresholdValue = navigationView.findViewById(R.id.confidence_threshold_value);
        functionCallsContainer = navigationView.findViewById(R.id.function_calls_container);
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

        String savedLangCode = settings.getString(KEY_LANGUAGE, "de-CH");
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
    }

    public void onDrawerClosed() {
        String oldModel = settings.getString(KEY_MODEL, "gpt-realtime");
        String oldVoice = settings.getString(KEY_VOICE, "ash");
        String oldLang = settings.getString(KEY_LANGUAGE, "de-CH");
        String oldPrompt = settings.getString(KEY_SYSTEM_PROMPT, "");
        String oldProvider = settings.getString(KEY_API_PROVIDER, RealtimeApiProvider.OPENAI_DIRECT.name());
        int oldTemp = settings.getInt(KEY_TEMPERATURE, 33);
        if (oldTemp < 0 || oldTemp > 100) oldTemp = 33;
        Set<String> oldTools = getEnabledTools();
        
        String newModel = (String) modelSpinner.getSelectedItem();
        String newVoice = (String) voiceSpinner.getSelectedItem();
        LanguageOption selectedLang = (LanguageOption) languageSpinner.getSelectedItem();
        String newLang = selectedLang.getCode();
        String newPrompt = systemPromptInput.getText().toString();
        String newProvider = getSelectedApiProvider();
        int newTemp = temperatureSeekBar.getProgress();
        Set<String> newTools = getCurrentlySelectedTools();

        SharedPreferences.Editor editor = settings.edit();
        editor.putString(KEY_SYSTEM_PROMPT, newPrompt);
        editor.putString(KEY_MODEL, newModel);
        editor.putString(KEY_VOICE, newVoice);
        editor.putString(KEY_LANGUAGE, newLang);
        editor.putString(KEY_API_PROVIDER, newProvider);
        editor.putInt(KEY_TEMPERATURE, newTemp);
        editor.putStringSet(KEY_ENABLED_TOOLS, newTools);
        editor.apply();

        // Determine what type of change occurred and notify accordingly
        if (!oldProvider.equals(newProvider) || !oldModel.equals(newModel) || !oldVoice.equals(newVoice)) {
            // Provider, model or voice change requires new session
            if (listener != null) listener.onSettingsChanged();
        } else if (!oldLang.equals(newLang)) {
            // Language change requires recognizer restart
            if (listener != null) listener.onRecognizerSettingsChanged();
        } else if (!oldTools.equals(newTools) || !oldPrompt.equals(newPrompt) || oldTemp != newTemp) {
            // Tools, prompt, or temperature change only requires session update
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

    public String getLanguage() {
        return settings.getString(KEY_LANGUAGE, "de-CH");
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
        io.github.studerus.pepper_android_realtime.tools.ToolRegistryNew registry = new io.github.studerus.pepper_android_realtime.tools.ToolRegistryNew();
        return new HashSet<>(registry.getAllToolNames());
    }

    private void setupFunctionCallsUI() {
        if (functionCallsContainer == null) return;
        
        functionCallsContainer.removeAllViews();
        
        ApiKeyManager keyManager = new ApiKeyManager(activity);
        Set<String> enabledTools = getEnabledTools();
        
        // Get tool info from new registry
        io.github.studerus.pepper_android_realtime.tools.ToolRegistryNew registry = new io.github.studerus.pepper_android_realtime.tools.ToolRegistryNew();
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
                io.github.studerus.pepper_android_realtime.tools.Tool tool = registry.createTool(toolId);
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
        languages.add(new LanguageOption("German (Switzerland)", "de-CH"));
        languages.add(new LanguageOption("German (Germany)", "de-DE"));
        languages.add(new LanguageOption("German (Austria)", "de-AT"));
        languages.add(new LanguageOption("English (United States)", "en-US"));
        languages.add(new LanguageOption("English (United Kingdom)", "en-GB"));
        languages.add(new LanguageOption("English (Australia)", "en-AU"));
        languages.add(new LanguageOption("English (Canada)", "en-CA"));
        languages.add(new LanguageOption("French (Switzerland)", "fr-CH"));
        languages.add(new LanguageOption("French (France)", "fr-FR"));
        languages.add(new LanguageOption("French (Canada)", "fr-CA"));
        languages.add(new LanguageOption("Italian (Switzerland)", "it-CH"));
        languages.add(new LanguageOption("Italian (Italy)", "it-IT"));
        return languages;
    }

    /**
     * Update model spinner options (provider-agnostic)
     */
    private void updateModelSpinnerForProvider(RealtimeApiProvider provider) {
        String[] models = new String[]{ "gpt-realtime", "gpt-4o-realtime-preview", "gpt-4o-mini-realtime-preview" };
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
