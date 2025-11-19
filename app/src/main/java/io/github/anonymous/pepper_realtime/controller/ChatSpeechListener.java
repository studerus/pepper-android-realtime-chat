package io.github.anonymous.pepper_realtime.controller;

import android.util.Log;
import android.widget.TextView;

import io.github.anonymous.pepper_realtime.ChatActivity;
import io.github.anonymous.pepper_realtime.R;
import io.github.anonymous.pepper_realtime.manager.SpeechRecognizerManager;
import io.github.anonymous.pepper_realtime.manager.TurnManager;
import io.github.anonymous.pepper_realtime.ui.ChatMessage;

public class ChatSpeechListener implements SpeechRecognizerManager.ActivityCallbacks {
    private static final String TAG = "ChatSpeechListener";

    private final ChatActivity activity;
    private final TurnManager turnManager;
    private final TextView statusTextView;

    // Start time tracking for warmup stats
    private final long sttWarmupStartTime;

    public ChatSpeechListener(ChatActivity activity, 
                            TurnManager turnManager, 
                            TextView statusTextView,
                            long sttWarmupStartTime) {
        this.activity = activity;
        this.turnManager = turnManager;
        this.statusTextView = statusTextView;
        this.sttWarmupStartTime = sttWarmupStartTime;
    }

    @Override
    public void onRecognizedText(String text) {
        // Gate STT: only accept in LISTENING state and when not muted
        if (turnManager != null && turnManager.getState() != TurnManager.State.LISTENING) {
            Log.i(TAG, "Ignoring STT result because state=" + turnManager.getState());
            return;
        }

        if (activity.isMuted()) {
            Log.i(TAG, "Ignoring STT result because microphone is muted");
            return;
        }

        String sanitizedText = text.replaceAll("\\[Low confidence:.*?]", "").trim();
        activity.runOnUiThread(() -> activity.addMessage(sanitizedText, ChatMessage.Sender.USER));
        activity.sendMessageToRealtimeAPI(text, true, false);
    }

    @Override
    public void onPartialText(String partialText) {
        // Don't show partial text when muted
        if (activity.isMuted()) {
            return;
        }
        
        activity.runOnUiThread(() -> {
            String currentText = statusTextView.getText().toString();
            if (currentText.startsWith("Listening")) {
                statusTextView.setText(activity.getString(R.string.status_listening_partial, partialText));
            }
        });
    }

    @Override
    public void onError(String errorMessage) {
        Log.e(TAG, "STT error: " + errorMessage);
        activity.runOnUiThread(() -> statusTextView.setText(activity.getString(R.string.error_generic, errorMessage)));
    }

    @Override
    public void onStarted() {
        activity.setSttRunning(true);
        activity.runOnUiThread(() -> {
            Log.i(TAG, "✅ STT is now actively listening - entering LISTENING state");
            
            // Hide warmup indicator NOW - recognition is truly active
            activity.hideWarmupIndicator();
            activity.setWarmingUp(false);
            
            statusTextView.setText(activity.getString(R.string.status_listening));
            
            // Now that recognition is ACTUALLY running, transition to LISTENING state
            if (turnManager != null) {
                turnManager.setState(TurnManager.State.LISTENING);
            }
        });
    }

    @Override
    public void onStopped() {
        activity.setSttRunning(false);
    }

    @Override
    public void onReady() {
        long totalWarmupTime = System.currentTimeMillis() - sttWarmupStartTime;
        Log.i(TAG, "✅ Speech Recognizer is fully warmed up and ready (total time: " + totalWarmupTime + "ms)");
        
        // Start continuous recognition immediately (but keep warmup indicator visible)
        // The indicator will be hidden in onStarted() callback when recognition is truly active
        Log.i(TAG, "STT warmup complete - starting continuous recognition (warmup indicator stays visible)...");
        
        activity.runOnUiThread(() -> {
            try {
                activity.startContinuousRecognition();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start recognition after warmup", e);
                activity.hideWarmupIndicator();
                activity.setWarmingUp(false);
                statusTextView.setText(activity.getString(R.string.status_recognizer_not_ready));
            }
        });
    }
}

