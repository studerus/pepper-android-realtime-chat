package io.github.anonymous.pepper_realtime.ui.settings;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import io.github.anonymous.pepper_realtime.R;
import io.github.anonymous.pepper_realtime.ui.ChatActivity;

public class AzureSettingsUiController {

    private final ChatActivity activity;
    private final SettingsViewModel viewModel;
    private final View rootView;

    // UI Elements
    private LinearLayout azureSpeechSettingsContainer;
    private SeekBar silenceTimeoutSeekBar;
    private TextView silenceTimeoutValue;
    private SeekBar confidenceThresholdSeekBar;
    private TextView confidenceThresholdValue;

    public AzureSettingsUiController(ChatActivity activity, View rootView, SettingsViewModel viewModel) {
        this.activity = activity;
        this.rootView = rootView;
        this.viewModel = viewModel;
        initializeViews();
        setupListeners();
        loadInitialValues();
    }

    private void initializeViews() {
        azureSpeechSettingsContainer = rootView.findViewById(R.id.azure_speech_settings_container);
        silenceTimeoutSeekBar = rootView.findViewById(R.id.silence_timeout_seekbar);
        silenceTimeoutValue = rootView.findViewById(R.id.silence_timeout_value);
        confidenceThresholdSeekBar = rootView.findViewById(R.id.confidence_threshold_seekbar);
        confidenceThresholdValue = rootView.findViewById(R.id.confidence_threshold_value);
    }

    private void setupListeners() {
        silenceTimeoutSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                silenceTimeoutValue.setText(activity.getString(R.string.silence_timeout_format, progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Defer to applyChanges
            }
        });

        confidenceThresholdSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                confidenceThresholdValue.setText(activity.getString(R.string.confidence_threshold_format, progress));
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
        int silenceTimeout = viewModel.getSilenceTimeout();
        silenceTimeoutSeekBar.setProgress(silenceTimeout);
        silenceTimeoutValue.setText(activity.getString(R.string.silence_timeout_format, silenceTimeout));

        int confProgress = Math.round(viewModel.getConfidenceThreshold() * 100f);
        confidenceThresholdSeekBar.setProgress(confProgress);
        confidenceThresholdValue.setText(activity.getString(R.string.confidence_threshold_format, confProgress));
    }

    public void applyChanges() {
        viewModel.setSilenceTimeout(silenceTimeoutSeekBar.getProgress());
        viewModel.setConfidenceThreshold(confidenceThresholdSeekBar.getProgress() / 100f);
    }

    public void setVisibility(boolean visible) {
        azureSpeechSettingsContainer.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
