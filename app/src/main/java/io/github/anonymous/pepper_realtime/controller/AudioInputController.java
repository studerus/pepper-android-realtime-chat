package io.github.anonymous.pepper_realtime.controller;

import android.util.Log;

import io.github.anonymous.pepper_realtime.ui.ChatViewModel;
import io.github.anonymous.pepper_realtime.R;
import io.github.anonymous.pepper_realtime.manager.ApiKeyManager;
import io.github.anonymous.pepper_realtime.manager.ThreadManager;
import io.github.anonymous.pepper_realtime.manager.RealtimeAudioInputManager;
import io.github.anonymous.pepper_realtime.manager.SpeechRecognizerManager;
import io.github.anonymous.pepper_realtime.network.RealtimeSessionManager;
import io.github.anonymous.pepper_realtime.ui.ChatMessage;
import io.github.anonymous.pepper_realtime.manager.SettingsRepository;

import dagger.hilt.android.scopes.ActivityScoped;
import javax.inject.Inject;

@ActivityScoped
public class AudioInputController {
    private static final String TAG = "AudioInputController";

    private final ChatViewModel viewModel;
    private final SettingsRepository settingsRepository;
    private final ApiKeyManager keyManager;
    private final RealtimeSessionManager sessionManager;

    // State
    private SpeechRecognizerManager sttManager;
    private RealtimeAudioInputManager realtimeAudioInput;
    private boolean isMuted = false;
    private boolean sttIsRunning = false;
    private long sttWarmupStartTime = 0L;

    // Listener for STT events
    private ChatSpeechListener speechListener;

    @Inject
    public AudioInputController(ChatViewModel viewModel,
            SettingsRepository settingsRepository,
            ApiKeyManager keyManager,
            RealtimeSessionManager sessionManager) {
        this.viewModel = viewModel;
        this.settingsRepository = settingsRepository;
        this.keyManager = keyManager;
        this.sessionManager = sessionManager;
    }

    /**
     * Always get the current ThreadManager instance to avoid using a shutdown instance
     * after app restart.
     */
    private ThreadManager getThreadManager() {
        return ThreadManager.getInstance();
    }

    public void setSpeechListener(ChatSpeechListener listener) {
        this.speechListener = listener;
    }

    public boolean isMuted() {
        return isMuted;
    }

    public boolean isSttRunning() {
        return sttIsRunning;
    }

    public void setSttRunning(boolean running) {
        this.sttIsRunning = running;
    }

    public long getSttWarmupStartTime() {
        return sttWarmupStartTime;
    }

    public void mute() {
        isMuted = true;
        viewModel.setMuted(true);
        stopContinuousRecognition();

        viewModel.setStatusText(getString(R.string.status_muted_tap_to_unmute));
        Log.i(TAG, "Microphone muted - tap status to un-mute");
    }

    public void resetMuteState() {
        isMuted = false;
        viewModel.setMuted(false);
        Log.i(TAG, "Mute state reset (audio capture not started automatically)");
    }

    public void unmute() {
        isMuted = false;
        viewModel.setMuted(false);
        viewModel.setStatusText(getString(R.string.status_listening));

        try {
            startContinuousRecognition();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start recognition on unmute", e);
            viewModel.setStatusText(getString(R.string.status_recognizer_not_ready));
        }
        Log.i(TAG, "Microphone un-muted - resuming listening");
    }

    public void startContinuousRecognition() {
        if (settingsRepository.isUsingRealtimeAudioInput()) {
            // MUTUAL EXCLUSION: Stop Azure STT if it's running before starting Realtime API
            if (sttManager != null) {
                getThreadManager().executeAudio(() -> cleanupSttForReinit());
                Log.i(TAG, "Stopped and cleaned up Azure STT to prevent interference with Realtime API audio");
            }

            if (realtimeAudioInput == null) {
                realtimeAudioInput = new RealtimeAudioInputManager(sessionManager);
                Log.i(TAG, "Created RealtimeAudioInputManager");
            }
            getThreadManager().executeAudio(() -> {
                boolean started = realtimeAudioInput.start();
                if (started) {
                    Log.i(TAG, "Realtime API audio capture started (server VAD enabled)");
                } else {
                    Log.e(TAG, "Failed to start Realtime API audio capture");
                    viewModel.setStatusText(getString(R.string.error_audio_capture_failed));
                    viewModel.addMessage(new ChatMessage(getString(R.string.error_audio_capture_permissions),
                            ChatMessage.Sender.ROBOT));
                }
            });
            return;
        }

        // MUTUAL EXCLUSION: Stop Realtime API audio if it's running before starting
        // Azure STT
        if (realtimeAudioInput != null && realtimeAudioInput.isCapturing()) {
            getThreadManager().executeAudio(() -> realtimeAudioInput.stop());
            Log.i(TAG, "Stopped Realtime API audio to prevent interference with Azure STT");
        }

        if (sttManager == null) {
            Log.w(TAG, "Speech recognizer not initialized yet, cannot start recognition.");
            viewModel.setStatusText(getString(R.string.status_recognizer_not_ready));
            return;
        }
        getThreadManager().executeAudio(() -> sttManager.start());
    }

    public void stopContinuousRecognition() {
        if (settingsRepository.isUsingRealtimeAudioInput()) {
            if (realtimeAudioInput != null && realtimeAudioInput.isCapturing()) {
                getThreadManager().executeAudio(() -> {
                    realtimeAudioInput.stop();
                    Log.i(TAG, "Realtime API audio capture stopped");
                });
            }
            return;
        }
        if (sttManager != null) {
            getThreadManager().executeAudio(() -> sttManager.stop());
        }
    }

    public void shutdown() {
        if (sttManager != null) {
            sttManager.shutdown();
            sttManager = null;
        }
        if (realtimeAudioInput != null) {
            realtimeAudioInput.stop();
            realtimeAudioInput = null;
        }
    }

    public void cleanupForRestart() {
        if (realtimeAudioInput != null && realtimeAudioInput.isCapturing()) {
            realtimeAudioInput.stop();
            Log.i(TAG, "Stopped Realtime audio capture");
        }
        if (sttManager != null && sttIsRunning) {
            getThreadManager().executeAudio(() -> sttManager.stop());
            Log.i(TAG, "Stopped Azure Speech recognizer");
        }
    }

    public void handleResume() {
        String currentMode = settingsRepository.getAudioInputMode();
        if (SettingsRepository.MODE_AZURE_SPEECH.equals(currentMode) && sttManager != null) {
            try {
                sttManager.start();
                Log.i(TAG, "Restarted Azure STT after resume");
            } catch (Exception e) {
                Log.e(TAG, "Failed to restart STT after resume", e);
            }
        } else if (SettingsRepository.MODE_REALTIME_API.equals(currentMode) && realtimeAudioInput != null) {
            try {
                realtimeAudioInput.start();
                Log.i(TAG, "Restarted Realtime audio input after resume");
            } catch (Exception e) {
                Log.e(TAG, "Failed to restart Realtime audio after resume", e);
            }
        }
    }

    public void setupSpeechRecognizer() throws Exception {
        Log.i(TAG, "Starting STT setup...");

        if (!settingsRepository.isUsingRealtimeAudioInput()) {
            Log.i(TAG, "Azure Speech mode active - setting up STT...");
            ensureSttManager();
            Log.i(TAG, "Configuring speech recognizer (this will also perform warmup)...");
            configureSpeechRecognizer();
            Log.i(TAG,
                    "Azure Speech Recognizer setup initiated (warmup in progress, will notify via onReady() callback)");
        } else {
            Log.i(TAG, "Realtime API audio mode active - skipping Azure Speech warmup");
        }
    }

    public void reinitializeSpeechRecognizerForSettings() {
        if (settingsRepository.isUsingRealtimeAudioInput()) {
            Log.i(TAG, "Realtime API audio mode - no STT re-initialization needed");
            return;
        }
        getThreadManager().executeAudio(() -> {
            try {
                ensureSttManager();
                configureSpeechRecognizer();
                String langCode = settingsRepository.getLanguage();
                int silenceTimeout = settingsRepository.getSilenceTimeout();
                Log.i(TAG, "Azure Speech Recognizer re-initialized for language: " + langCode + ", silence timeout: "
                        + silenceTimeout + "ms");
                viewModel.setStatusText(getString(R.string.status_listening));
            } catch (Exception ex) {
                Log.e(TAG, "Azure Speech re-init failed", ex);
                viewModel.setStatusText(getString(R.string.error_updating_settings));
            }
        });
    }

    public void cleanupSttForReinit() {
        if (sttManager != null) {
            try {
                Log.i(TAG, "Cleaning up existing STT manager before reinitialization");
                sttManager.shutdown();
                Thread.sleep(150);
            } catch (Exception e) {
                Log.w(TAG, "Error during STT cleanup", e);
            }
            sttManager = null;
        }
    }

    public void startWarmup() {
        sttWarmupStartTime = System.currentTimeMillis();
    }

    private void ensureSttManager() {
        if (sttManager == null) {
            sttManager = new SpeechRecognizerManager();
            Log.i(TAG, "Created new SpeechRecognizerManager");
        }
    }

    private void configureSpeechRecognizer() {
        if (speechListener != null) {
            sttManager.setCallbacks(speechListener);
        }
        String langCode = settingsRepository.getLanguage();
        int silenceTimeout = settingsRepository.getSilenceTimeout();
        double confidenceThreshold = settingsRepository.getConfidenceThreshold();
        sttManager.configure(keyManager.getAzureSpeechKey(), keyManager.getAzureSpeechRegion(), langCode,
                silenceTimeout, confidenceThreshold);
    }

    private String getString(int resId) {
        return viewModel.getApplication().getString(resId);
    }
}
