package io.github.anonymous.pepper_realtime.controller;

import android.util.Log;
import org.json.JSONObject;

import io.github.anonymous.pepper_realtime.controller.GestureController;
import io.github.anonymous.pepper_realtime.manager.AudioPlayer;
import io.github.anonymous.pepper_realtime.ui.ChatViewModel;
import io.github.anonymous.pepper_realtime.network.RealtimeSessionManager;

public class ChatInterruptController {
    private static final String TAG = "ChatInterruptController";

    private final RealtimeSessionManager sessionManager;
    private final AudioPlayer audioPlayer;
    private final GestureController gestureController;
    private final AudioInputController audioInputController;
    private final ChatViewModel viewModel;

    @javax.inject.Inject
    public ChatInterruptController(ChatViewModel viewModel,
            RealtimeSessionManager sessionManager,
            AudioPlayer audioPlayer,
            GestureController gestureController,
            AudioInputController audioInputController) {
        this.viewModel = viewModel;
        this.sessionManager = sessionManager;
        this.audioPlayer = audioPlayer;
        this.gestureController = gestureController;
        this.audioInputController = audioInputController;
    }

    public void interruptSpeech() {
        try {
            if (sessionManager == null || !sessionManager.isConnected())
                return;

            boolean isGenerating = Boolean.TRUE.equals(viewModel.getIsResponseGenerating().getValue());
            boolean isPlaying = Boolean.TRUE.equals(viewModel.getIsAudioPlaying().getValue());

            Log.d(TAG, "🚨 Interrupt: isResponseGenerating=" + isGenerating +
                    ", isAudioPlaying=" + isPlaying);

            if (isGenerating) {
                JSONObject cancel = new JSONObject();
                cancel.put("type", "response.cancel");
                sessionManager.send(cancel.toString());

                viewModel.setCancelledResponseId(viewModel.getCurrentResponseId());
                viewModel.setResponseGenerating(false);
                Log.d(TAG, "Sent response.cancel for active generation");
            }

            String lastItemId = viewModel.getLastAssistantItemId();

            // Only truncate if we are actively generating or playing
            if (lastItemId != null && (isGenerating || isPlaying)) {
                int playedMs = audioPlayer != null ? Math.max(0, audioPlayer.getEstimatedPlaybackPositionMs()) : 0;

                // Safety margin to prevent "invalid_value" error if our local clock is slightly
                // ahead of server
                // or if we try to truncate right at the end of the stream.
                if (playedMs > 0) {
                    playedMs = Math.max(0, playedMs - 500);
                }

                Log.d(TAG, "Sending truncate for item=" + lastItemId + ", audio_end_ms=" + playedMs);
                JSONObject truncate = new JSONObject();
                truncate.put("type", "conversation.item.truncate");
                truncate.put("item_id", lastItemId);
                truncate.put("content_index", 0);
                truncate.put("audio_end_ms", playedMs);
                sessionManager.send(truncate.toString());
            }

            if (audioPlayer != null) {
                audioPlayer.interruptNow();
                viewModel.setAudioPlaying(false);
            }
            gestureController.stopNow();

        } catch (Exception e) {
            Log.e(TAG, "Error during interruptSpeech", e);
        }
    }

    public void interruptAndMute() {
        interruptSpeech();
        audioInputController.mute();
    }
}
