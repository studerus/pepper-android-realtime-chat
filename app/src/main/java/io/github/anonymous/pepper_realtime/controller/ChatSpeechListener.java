package io.github.anonymous.pepper_realtime.controller;

import android.util.Log;
import android.widget.TextView;

import io.github.anonymous.pepper_realtime.ui.ChatViewModel;
import io.github.anonymous.pepper_realtime.R;
import io.github.anonymous.pepper_realtime.manager.SpeechRecognizerManager;
import io.github.anonymous.pepper_realtime.manager.TurnManager;
import io.github.anonymous.pepper_realtime.ui.ChatMessage;

public class ChatSpeechListener implements SpeechRecognizerManager.ActivityCallbacks {
    private static final String TAG = "ChatSpeechListener";

    private final TurnManager turnManager;
    private final TextView statusTextView;
    private final ChatSessionController sessionController;
    private final AudioInputController audioInputController;
    private final ChatViewModel viewModel;

    // Start time tracking for warmup stats
    private final long sttWarmupStartTime;

    public ChatSpeechListener(
            TurnManager turnManager,
            TextView statusTextView,
            long sttWarmupStartTime,
            ChatSessionController sessionController,
            AudioInputController audioInputController,
            ChatViewModel viewModel) {
        this.turnManager = turnManager;
        this.statusTextView = statusTextView;
        this.sttWarmupStartTime = sttWarmupStartTime;
        this.sessionController = sessionController;
        this.audioInputController = audioInputController;
        this.viewModel = viewModel;
    }

    @Override
    public void onRecognizedText(String text) {
        // Gate STT: only accept in LISTENING state and when not muted
        if (turnManager != null && turnManager.getState() != TurnManager.State.LISTENING) {
            Log.i(TAG, "Ignoring STT result because state=" + turnManager.getState());
            return;
        }

        if (audioInputController.isMuted()) {
            Log.i(TAG, "Ignoring STT result because microphone is muted");
            return;
        }

        String sanitizedText = text.replaceAll("\\[Low confidence:.*?]", "").trim();
        viewModel.addMessage(new ChatMessage(sanitizedText, ChatMessage.Sender.USER));
        sessionController.sendMessageToRealtimeAPI(text, true, false);
    }

    @Override
    public void onPartialText(String partialText) {
        // Don't show partial text when muted
        if (audioInputController.isMuted()) {
            return;
        }

        String currentText = viewModel.getStatusText().getValue();
        if (currentText != null && currentText.startsWith("Listening")) {
            viewModel.setStatusText(getString(R.string.status_listening_partial, partialText));
        }
    }

    @Override
    public void onError(String errorMessage) {
        Log.e(TAG, "STT error: " + errorMessage);
        viewModel.setStatusText(getString(R.string.error_generic, errorMessage));
    }

    @Override
    public void onStarted() {
        audioInputController.setSttRunning(true);
        Log.i(TAG, "✅ STT is now actively listening - entering LISTENING state");

        // Hide warmup indicator via ViewModel
        viewModel.setWarmingUp(false);

        viewModel.setStatusText(getString(R.string.status_listening));

        // Now that recognition is ACTUALLY running, transition to LISTENING state
        if (turnManager != null) {
            turnManager.setState(TurnManager.State.LISTENING);
        }
    }

    @Override
    public void onStopped() {
        audioInputController.setSttRunning(false);
    }

    @Override
    public void onReady() {
        long totalWarmupTime = System.currentTimeMillis() - sttWarmupStartTime;
        Log.i(TAG, "✅ Speech Recognizer is fully warmed up and ready (total time: " + totalWarmupTime + "ms)");

        // Start continuous recognition immediately (but keep warmup indicator visible)
        // The indicator will be hidden in onStarted() callback when recognition is
        // truly active
        Log.i(TAG, "STT warmup complete - starting continuous recognition (warmup indicator stays visible)...");

        try {
            audioInputController.startContinuousRecognition();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start recognition after warmup", e);
            viewModel.setWarmingUp(false);
            viewModel.setStatusText(getString(R.string.status_recognizer_not_ready));
        }
    }

    private String getString(int resId) {
        return viewModel.getApplication().getString(resId);
    }

    private String getString(int resId, Object... formatArgs) {
        return viewModel.getApplication().getString(resId, formatArgs);
    }
}
