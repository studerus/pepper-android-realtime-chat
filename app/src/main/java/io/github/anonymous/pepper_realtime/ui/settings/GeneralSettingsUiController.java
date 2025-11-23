package io.github.anonymous.pepper_realtime.ui.settings;

import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.List;

import io.github.anonymous.pepper_realtime.R;
import io.github.anonymous.pepper_realtime.manager.ApiKeyManager;
import io.github.anonymous.pepper_realtime.ui.settings.LanguageOption;
import io.github.anonymous.pepper_realtime.manager.SettingsManager;
import io.github.anonymous.pepper_realtime.network.RealtimeApiProvider;
import io.github.anonymous.pepper_realtime.ui.ChatActivity;

public class GeneralSettingsUiController {

    private final ChatActivity activity;
    private final SettingsViewModel viewModel;
    private final View rootView;

    // UI Elements
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

    // Callback for visibility updates
    public interface VisibilityUpdateCallback {
        void onAudioInputModeChanged();
    }

    private VisibilityUpdateCallback visibilityUpdateCallback;

    public GeneralSettingsUiController(ChatActivity activity, View rootView, SettingsViewModel viewModel) {
        this.activity = activity;
        this.rootView = rootView;
        this.viewModel = viewModel;
        initializeViews();
        setupListeners();
        loadInitialValues();
    }

    private void initializeViews() {
        systemPromptInput = rootView.findViewById(R.id.system_prompt_input);
        modelSpinner = rootView.findViewById(R.id.model_spinner);
        apiProviderSpinner = rootView.findViewById(R.id.api_provider_spinner);
        voiceSpinner = rootView.findViewById(R.id.voice_spinner);
        speedSeekBar = rootView.findViewById(R.id.speed_seekbar);
        speedValue = rootView.findViewById(R.id.speed_value);
        languageSpinner = rootView.findViewById(R.id.language_spinner);
        audioInputModeSpinner = rootView.findViewById(R.id.audio_input_mode_spinner);
        temperatureSeekBar = rootView.findViewById(R.id.temperature_seekbar);
        temperatureValue = rootView.findViewById(R.id.temperature_value);
        volumeSeekBar = rootView.findViewById(R.id.volume_seekbar);
        volumeValue = rootView.findViewById(R.id.volume_value);
    }

    private void setupListeners() {
        // We need to handle "save on close" logic or "save on change".
        // The original SettingsManager saved on drawer close for some things, and
        // immediately for others.
        // To simplify and make it more reactive, we can save on change for SeekBars and
        // Spinners.
        // For EditText (System Prompt), we might want to save on focus loss or explicit
        // action,
        // but for now let's stick to the pattern of "apply when drawer closes" or
        // similar if possible.
        // However, the ViewModel is designed for immediate updates.
        // Let's implement immediate updates for interactive elements.

        // Note: The original SettingsManager had a "onDrawerClosed" method that
        // gathered all values.
        // We should probably keep that pattern for the "bulk" settings to avoid too
        // many updates,
        // OR we can make the ViewModel smart enough to debounce or only trigger
        // restarts when needed.
        // Given the user requirement "voice, model require restart", immediate update
        // might trigger restart immediately.
        // That might be annoying if the user wants to change multiple things.
        // BUT, the drawer is usually modal-ish.
        // Let's stick to the "apply" pattern for things that cause restarts.
        // Actually, the original code applied changes in `onDrawerClosed`.
        // We should expose a `applyChanges()` method here that reads the UI and updates
        // the ViewModel.

        temperatureSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                temperatureValue.setText(
                        activity.getString(R.string.temperature_format, convertProgressToTemperature(progress)));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Defer to applyChanges() or update immediately?
                // Original updated immediately for SeekBars in listeners?
                // Original: temperatureSeekBar -> onStopTrackingTouch ->
                // settingsRepository.setTemperatureProgress
                viewModel.setTemperatureProgress(seekBar.getProgress());
            }
        });

        speedSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int effectiveProgress = Math.max(progress, 25);
                speedValue.setText(activity.getString(R.string.speed_format, effectiveProgress / 100f));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int progress = Math.max(seekBar.getProgress(), 25);
                viewModel.setSpeedProgress(progress);
            }
        });

        volumeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                volumeValue.setText(activity.getString(R.string.volume_format, progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                viewModel.setVolume(seekBar.getProgress());
            }
        });

        // Spinners usually trigger onItemSelected immediately.
        // If we want to support "Apply on Close", we shouldn't set listeners that
        // update ViewModel immediately
        // for things that cause restarts.
        // But for things like Audio Input Mode, it changes UI visibility immediately.

        audioInputModeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                // Update visibility immediately when mode changes
                if (visibilityUpdateCallback != null) {
                    visibilityUpdateCallback.onAudioInputModeChanged();
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
    }

    public void loadInitialValues() {
        // Populate Spinners (copied logic from SettingsManager)
        ApiKeyManager keyManager = new ApiKeyManager(activity);
        RealtimeApiProvider[] configuredProviders = keyManager.getConfiguredProviders();

        if (configuredProviders.length > 0) {
            ArrayAdapter<RealtimeApiProvider> providerAdapter = new ArrayAdapter<>(activity,
                    android.R.layout.simple_spinner_item, configuredProviders);
            providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            apiProviderSpinner.setAdapter(providerAdapter);

            RealtimeApiProvider current = RealtimeApiProvider.fromString(viewModel.getApiProvider());
            for (int i = 0; i < configuredProviders.length; i++) {
                if (configuredProviders[i] == current) {
                    apiProviderSpinner.setSelection(i);
                    break;
                }
            }
        } else {
            String[] noProviders = { "No API providers configured" };
            ArrayAdapter<String> emptyAdapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item,
                    noProviders);
            apiProviderSpinner.setAdapter(emptyAdapter);
            apiProviderSpinner.setEnabled(false);
        }

        // Model Spinner
        updateModelSpinner();
        String savedModel = viewModel.getModel();
        ArrayAdapter<String> modelAdapter = (ArrayAdapter<String>) modelSpinner.getAdapter();
        if (modelAdapter != null) {
            int modelPosition = modelAdapter.getPosition(savedModel);
            if (modelPosition >= 0) {
                modelSpinner.setSelection(modelPosition);
            }
        }

        // Voice Spinner
        String[] voices = { "alloy", "ash", "ballad", "cedar", "coral", "echo", "marin", "sage", "shimmer", "verse" };
        ArrayAdapter<String> voiceAdapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, voices);
        voiceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        voiceSpinner.setAdapter(voiceAdapter);
        voiceSpinner.setSelection(voiceAdapter.getPosition(viewModel.getVoice()));

        // Language Spinner
        // We need SettingsManager.getAvailableLanguages() logic.
        // Since we are refactoring, we can't easily access private methods of
        // SettingsManager.
        // We should probably move that static data/logic to a util class or duplicate
        // it.
        // For now, I'll assume we can access it if it's public or package-private, or
        // I'll duplicate the list.
        // It was private in SettingsManager. I'll duplicate the list creation for
        // safety.
        List<LanguageOption> languages = io.github.anonymous.pepper_realtime.manager.SettingsManager
                .getAvailableLanguages();
        ArrayAdapter<LanguageOption> languageAdapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, languages);
        languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(languageAdapter);

        String savedLangCode = viewModel.getLanguage();
        for (int i = 0; i < languages.size(); i++) {
            if (languages.get(i).getCode().equals(savedLangCode)) {
                languageSpinner.setSelection(i);
                break;
            }
        }

        // Other values
        systemPromptInput.setText(viewModel.getSystemPrompt());

        int speedProgress = viewModel.getSpeedProgress();
        speedSeekBar.setProgress(speedProgress);
        speedValue.setText(activity.getString(R.string.speed_format, speedProgress / 100f));

        int tempProgress = viewModel.getTemperatureProgress();
        temperatureSeekBar.setProgress(tempProgress);
        temperatureValue
                .setText(activity.getString(R.string.temperature_format, convertProgressToTemperature(tempProgress)));

        int volProgress = viewModel.getVolume();
        volumeSeekBar.setProgress(volProgress);
        volumeValue.setText(activity.getString(R.string.volume_format, volProgress));

        String savedInputMode = viewModel.getAudioInputMode();
        int inputModePosition = SettingsManager.MODE_REALTIME_API.equals(savedInputMode) ? 0 : 1;
        audioInputModeSpinner.setSelection(inputModePosition);
    }

    public void applyChanges() {
        // Read all values and update ViewModel
        viewModel.setSystemPrompt(systemPromptInput.getText().toString());

        if (modelSpinner.getSelectedItem() != null) {
            viewModel.setModel((String) modelSpinner.getSelectedItem());
        }

        if (voiceSpinner.getSelectedItem() != null) {
            viewModel.setVoice((String) voiceSpinner.getSelectedItem());
        }

        if (apiProviderSpinner.getSelectedItem() instanceof RealtimeApiProvider) {
            viewModel.setApiProvider(((RealtimeApiProvider) apiProviderSpinner.getSelectedItem()).name());
        }

        if (languageSpinner.getSelectedItem() instanceof LanguageOption) {
            viewModel.setLanguage(((LanguageOption) languageSpinner.getSelectedItem()).getCode());
        }

        String inputMode = audioInputModeSpinner.getSelectedItemPosition() == 0 ? SettingsManager.MODE_REALTIME_API
                : SettingsManager.MODE_AZURE_SPEECH;
        viewModel.setAudioInputMode(inputMode);

        // SeekBars are updated in real-time, but we can re-set them here to be sure
        viewModel.setSpeedProgress(speedSeekBar.getProgress());
        viewModel.setTemperatureProgress(temperatureSeekBar.getProgress());
        // Volume is updated in real-time
    }

    private float convertProgressToTemperature(int progress) {
        return 0.6f + (progress / 100.0f) * 0.6f;
    }

    public boolean isRealtimeAudioModeSelected() {
        return audioInputModeSpinner.getSelectedItemPosition() == 0;
    }

    public Spinner getApiProviderSpinner() {
        return apiProviderSpinner;
    }

    public Spinner getModelSpinner() {
        return modelSpinner;
    }

    public ChatActivity getActivity() {
        return activity;
    }

    public void setVisibilityUpdateCallback(VisibilityUpdateCallback callback) {
        this.visibilityUpdateCallback = callback;
    }

    /**
     * Update model spinner options (provider-agnostic)
     */
    private void updateModelSpinner() {
        String[] models = new String[] { "gpt-realtime", "gpt-realtime-mini", "gpt-4o-realtime-preview",
                "gpt-4o-mini-realtime-preview" };
        ArrayAdapter<String> modelAdapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, models);
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modelSpinner.setAdapter(modelAdapter);
    }
}
