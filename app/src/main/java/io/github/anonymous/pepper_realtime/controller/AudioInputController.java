package io.github.anonymous.pepper_realtime.controller;

import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import io.github.anonymous.pepper_realtime.ui.ChatActivity;
import io.github.anonymous.pepper_realtime.R;
import io.github.anonymous.pepper_realtime.manager.ApiKeyManager;
import io.github.anonymous.pepper_realtime.manager.ThreadManager;
import io.github.anonymous.pepper_realtime.manager.RealtimeAudioInputManager;
import io.github.anonymous.pepper_realtime.manager.SettingsManager;
import io.github.anonymous.pepper_realtime.manager.SpeechRecognizerManager;
import io.github.anonymous.pepper_realtime.network.RealtimeSessionManager;
import io.github.anonymous.pepper_realtime.ui.ChatMessage;

public class AudioInputController {
    private static final String TAG = "AudioInputController";

    private final ChatActivity activity;
    private final SettingsManager settingsManager;
    private final ApiKeyManager keyManager;
    private final RealtimeSessionManager sessionManager;
    private final ThreadManager threadManager;
    
    // UI components for status updates
    private final TextView statusTextView;
    private final FloatingActionButton fabInterrupt;
    
    // State
    private SpeechRecognizerManager sttManager;
    private RealtimeAudioInputManager realtimeAudioInput;
    private boolean isMuted = false;
    private boolean sttIsRunning = false;
    private long sttWarmupStartTime = 0L;

    // Listener for STT events
    private ChatSpeechListener speechListener;

    public AudioInputController(ChatActivity activity, 
                              SettingsManager settingsManager,
                              ApiKeyManager keyManager,
                              RealtimeSessionManager sessionManager,
                              ThreadManager threadManager,
                              TextView statusTextView,
                              FloatingActionButton fabInterrupt) {
        this.activity = activity;
        this.settingsManager = settingsManager;
        this.keyManager = keyManager;
        this.sessionManager = sessionManager;
        this.threadManager = threadManager;
        this.statusTextView = statusTextView;
        this.fabInterrupt = fabInterrupt;
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
        stopContinuousRecognition();
        activity.runOnUiThread(() -> {
            statusTextView.setText(activity.getString(R.string.status_muted_tap_to_unmute));
            fabInterrupt.setVisibility(View.GONE);
        });
        Log.i(TAG, "Microphone muted - tap status to un-mute");
    }
    
    public void unmute() {
        isMuted = false;
        activity.runOnUiThread(() -> {
            statusTextView.setText(activity.getString(R.string.status_listening));
            fabInterrupt.setVisibility(View.GONE);
        });
        try {
            startContinuousRecognition();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start recognition on unmute", e);
            activity.runOnUiThread(() -> statusTextView.setText(activity.getString(R.string.status_recognizer_not_ready)));
        }
        Log.i(TAG, "Microphone un-muted - resuming listening");
    }

    public void startContinuousRecognition() {
        if (settingsManager.isUsingRealtimeAudioInput()) {
            if (realtimeAudioInput == null) {
                realtimeAudioInput = new RealtimeAudioInputManager(sessionManager);
                Log.i(TAG, "Created RealtimeAudioInputManager");
            }
            threadManager.executeAudio(() -> {
                boolean started = realtimeAudioInput.start();
                if (started) {
                    Log.i(TAG, "Realtime API audio capture started (server VAD enabled)");
                } else {
                    Log.e(TAG, "Failed to start Realtime API audio capture");
                    activity.runOnUiThread(() -> {
                        statusTextView.setText(activity.getString(R.string.error_audio_capture_failed));
                        activity.addMessage(activity.getString(R.string.error_audio_capture_permissions), ChatMessage.Sender.ROBOT);
                    });
                }
            });
            return;
        }
        
        if (sttManager == null) {
            Log.w(TAG, "Speech recognizer not initialized yet, cannot start recognition.");
            activity.runOnUiThread(() -> statusTextView.setText(activity.getString(R.string.status_recognizer_not_ready)));
            return;
        }
        threadManager.executeAudio(() -> sttManager.start());
    }

    public void stopContinuousRecognition() {
        if (settingsManager.isUsingRealtimeAudioInput()) {
            if (realtimeAudioInput != null && realtimeAudioInput.isCapturing()) {
                threadManager.executeAudio(() -> {
                    realtimeAudioInput.stop();
                    Log.i(TAG, "Realtime API audio capture stopped");
                });
            }
            return;
        }
        if (sttManager != null) {
            threadManager.executeAudio(() -> sttManager.stop());
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
            threadManager.executeAudio(() -> sttManager.stop());
            Log.i(TAG, "Stopped Azure Speech recognizer");
        }
    }

    public void handleResume() {
        String currentMode = settingsManager.getAudioInputMode();
        if (SettingsManager.MODE_AZURE_SPEECH.equals(currentMode) && sttManager != null) {
            try {
                sttManager.start();
                Log.i(TAG, "Restarted Azure STT after resume");
            } catch (Exception e) {
                Log.e(TAG, "Failed to restart STT after resume", e);
            }
        } else if (SettingsManager.MODE_REALTIME_API.equals(currentMode) && realtimeAudioInput != null) {
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
        
        if (!settingsManager.isUsingRealtimeAudioInput()) {
            Log.i(TAG, "Azure Speech mode active - setting up STT...");
            ensureSttManager();
            Log.i(TAG, "Configuring speech recognizer (this will also perform warmup)...");
            configureSpeechRecognizer();
            Log.i(TAG, "Azure Speech Recognizer setup initiated (warmup in progress, will notify via onReady() callback)");
        } else {
            Log.i(TAG, "Realtime API audio mode active - skipping Azure Speech warmup");
        }
    }

    public void reinitializeSpeechRecognizerForSettings() {
        if (settingsManager.isUsingRealtimeAudioInput()) {
            Log.i(TAG, "Realtime API audio mode - no STT re-initialization needed");
            return;
        }
        threadManager.executeAudio(() -> {
            try {
                ensureSttManager();
                configureSpeechRecognizer();
                String langCode = settingsManager.getLanguage();
                int silenceTimeout = settingsManager.getSilenceTimeout();
                Log.i(TAG, "Azure Speech Recognizer re-initialized for language: " + langCode + ", silence timeout: " + silenceTimeout + "ms");
                activity.runOnUiThread(() -> statusTextView.setText(activity.getString(R.string.status_listening)));
            } catch (Exception ex) {
                Log.e(TAG, "Azure Speech re-init failed", ex);
                activity.runOnUiThread(() -> statusTextView.setText(activity.getString(R.string.error_updating_settings)));
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
        String langCode = settingsManager.getLanguage();
        int silenceTimeout = settingsManager.getSilenceTimeout();
        double confidenceThreshold = settingsManager.getConfidenceThreshold();
        sttManager.configure(keyManager.getAzureSpeechKey(), keyManager.getAzureSpeechRegion(), langCode, silenceTimeout, confidenceThreshold);
    }
}

