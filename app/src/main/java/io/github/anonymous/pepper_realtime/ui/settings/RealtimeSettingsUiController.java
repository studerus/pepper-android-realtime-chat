package io.github.anonymous.pepper_realtime.ui.settings;

import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import io.github.anonymous.pepper_realtime.R;
import io.github.anonymous.pepper_realtime.ui.ChatActivity;

public class RealtimeSettingsUiController {

    private final ChatActivity activity;
    private final SettingsViewModel viewModel;
    private final View rootView;

    // UI Elements
    private LinearLayout realtimeApiSettingsContainer;
    private LinearLayout serverVadSettingsContainer;
    private LinearLayout semanticVadSettingsContainer;

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

    public RealtimeSettingsUiController(ChatActivity activity, View rootView, SettingsViewModel viewModel) {
        this.activity = activity;
        this.rootView = rootView;
        this.viewModel = viewModel;
        initializeViews();
        setupListeners();
        loadInitialValues();
    }

    private void initializeViews() {
        realtimeApiSettingsContainer = rootView.findViewById(R.id.realtime_api_settings_container);
        serverVadSettingsContainer = rootView.findViewById(R.id.server_vad_settings_container);
        semanticVadSettingsContainer = rootView.findViewById(R.id.semantic_vad_settings_container);

        transcriptionModelSpinner = rootView.findViewById(R.id.transcription_model_spinner);
        transcriptionLanguageInput = rootView.findViewById(R.id.transcription_language_input);
        transcriptionPromptInput = rootView.findViewById(R.id.transcription_prompt_input);
        turnDetectionTypeSpinner = rootView.findViewById(R.id.turn_detection_type_spinner);
        vadThresholdSeekBar = rootView.findViewById(R.id.vad_threshold_seekbar);
        vadThresholdValue = rootView.findViewById(R.id.vad_threshold_value);
        prefixPaddingSeekBar = rootView.findViewById(R.id.prefix_padding_seekbar);
        prefixPaddingValue = rootView.findViewById(R.id.prefix_padding_value);
        silenceDurationSeekBar = rootView.findViewById(R.id.silence_duration_seekbar);
        silenceDurationValue = rootView.findViewById(R.id.silence_duration_value);
        idleTimeoutInput = rootView.findViewById(R.id.idle_timeout_input);
        eagernessSpinner = rootView.findViewById(R.id.eagerness_spinner);
        noiseReductionSpinner = rootView.findViewById(R.id.noise_reduction_spinner);
    }

    private void setupListeners() {
        turnDetectionTypeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                updateVadSettingsVisibility(position == 0); // true = Server VAD
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        vadThresholdSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                vadThresholdValue.setText(activity.getString(R.string.realtime_vad_threshold_format, progress / 100f));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Defer to applyChanges
            }
        });

        prefixPaddingSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                prefixPaddingValue.setText(activity.getString(R.string.realtime_prefix_padding_format, progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Defer to applyChanges
            }
        });

        silenceDurationSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int effectiveProgress = Math.max(progress, 200);
                silenceDurationValue
                        .setText(activity.getString(R.string.realtime_silence_duration_format, effectiveProgress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Defer to applyChanges
            }
        });
    }

    public void loadInitialValues() {
        // Transcription Model
        String savedTranscriptionModel = viewModel.getTranscriptionModel();
        ArrayAdapter<CharSequence> transcriptionAdapter = ArrayAdapter.createFromResource(
                activity, R.array.transcription_models, android.R.layout.simple_spinner_item);
        transcriptionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        transcriptionModelSpinner.setAdapter(transcriptionAdapter);

        int transcriptionModelPosition = 0;
        for (int i = 0; i < transcriptionAdapter.getCount(); i++) {
            CharSequence item = transcriptionAdapter.getItem(i);
            if (item != null && item.toString().equals(savedTranscriptionModel)) {
                transcriptionModelPosition = i;
                break;
            }
        }
        transcriptionModelSpinner.setSelection(transcriptionModelPosition);

        transcriptionLanguageInput.setText(viewModel.getTranscriptionLanguage());
        transcriptionPromptInput.setText(viewModel.getTranscriptionPrompt());

        String savedTurnDetectionType = viewModel.getTurnDetectionType();
        int turnDetectionPosition = savedTurnDetectionType.equals("semantic_vad") ? 1 : 0;
        turnDetectionTypeSpinner.setSelection(turnDetectionPosition);

        int vadThresholdProgress = Math.round(viewModel.getVadThreshold() * 100f);
        vadThresholdSeekBar.setProgress(vadThresholdProgress);
        vadThresholdValue
                .setText(activity.getString(R.string.realtime_vad_threshold_format, vadThresholdProgress / 100f));

        int prefixPadding = viewModel.getPrefixPadding();
        prefixPaddingSeekBar.setProgress(prefixPadding);
        prefixPaddingValue.setText(activity.getString(R.string.realtime_prefix_padding_format, prefixPadding));

        int silenceDuration = viewModel.getSilenceDuration();
        silenceDurationSeekBar.setProgress(silenceDuration);
        silenceDurationValue.setText(activity.getString(R.string.realtime_silence_duration_format, silenceDuration));

        Integer idleTimeout = viewModel.getIdleTimeout();
        if (idleTimeout != null && idleTimeout > 0) {
            idleTimeoutInput.setText(String.valueOf(idleTimeout));
        }

        String savedEagerness = viewModel.getEagerness();
        int eagernessPosition = switch (savedEagerness) {
            case "low" -> 1;
            case "medium" -> 2;
            case "high" -> 3;
            default -> 0;
        };
        eagernessSpinner.setSelection(eagernessPosition);

        String savedNoiseReduction = viewModel.getNoiseReduction();
        int noiseReductionPosition = switch (savedNoiseReduction) {
            case "near_field" -> 1;
            case "far_field" -> 2;
            default -> 0;
        };
        noiseReductionSpinner.setSelection(noiseReductionPosition);

        updateVadSettingsVisibility(turnDetectionPosition == 0);
    }

    public void applyChanges() {
        if (transcriptionModelSpinner.getSelectedItem() != null) {
            viewModel.setTranscriptionModel(transcriptionModelSpinner.getSelectedItem().toString());
        }
        viewModel.setTranscriptionLanguage(transcriptionLanguageInput.getText().toString());
        viewModel.setTranscriptionPrompt(transcriptionPromptInput.getText().toString());

        String turnDetectionType = turnDetectionTypeSpinner.getSelectedItemPosition() == 0 ? "server_vad"
                : "semantic_vad";
        viewModel.setTurnDetectionType(turnDetectionType);

        viewModel.setVadThreshold(vadThresholdSeekBar.getProgress() / 100f);
        viewModel.setPrefixPadding(prefixPaddingSeekBar.getProgress());
        viewModel.setSilenceDuration(Math.max(silenceDurationSeekBar.getProgress(), 200));

        try {
            String idleTimeoutStr = idleTimeoutInput.getText().toString().trim();
            if (!idleTimeoutStr.isEmpty()) {
                viewModel.setIdleTimeout(Integer.parseInt(idleTimeoutStr));
            } else {
                viewModel.setIdleTimeout(0);
            }
        } catch (NumberFormatException ignored) {
        }

        String eagerness = "auto";
        int eagernessPos = eagernessSpinner.getSelectedItemPosition();
        if (eagernessPos == 1)
            eagerness = "low";
        else if (eagernessPos == 2)
            eagerness = "medium";
        else if (eagernessPos == 3)
            eagerness = "high";
        viewModel.setEagerness(eagerness);

        String noiseReduction = "off";
        int noiseReductionPos = noiseReductionSpinner.getSelectedItemPosition();
        if (noiseReductionPos == 1)
            noiseReduction = "near_field";
        else if (noiseReductionPos == 2)
            noiseReduction = "far_field";
        viewModel.setNoiseReduction(noiseReduction);
    }

    public void setVisibility(boolean visible) {
        realtimeApiSettingsContainer.setVisibility(visible ? View.VISIBLE : View.GONE);
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
}
