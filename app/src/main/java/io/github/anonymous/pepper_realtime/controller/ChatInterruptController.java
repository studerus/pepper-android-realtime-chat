package io.github.anonymous.pepper_realtime.controller;

import android.util.Log;
import org.json.JSONObject;

import io.github.anonymous.pepper_realtime.ui.ChatActivity;
import io.github.anonymous.pepper_realtime.controller.GestureController;
import io.github.anonymous.pepper_realtime.manager.AudioPlayer;
import io.github.anonymous.pepper_realtime.network.RealtimeSessionManager;

public class ChatInterruptController {
    private static final String TAG = "ChatInterruptController";

    private final RealtimeSessionManager sessionManager;
    private final AudioPlayer audioPlayer;
    private final GestureController gestureController;
    private final AudioInputController audioInputController;
    private final ChatActivity activity; // For access to volatile flags

    public ChatInterruptController(ChatActivity activity,
                                 RealtimeSessionManager sessionManager,
                                 AudioPlayer audioPlayer,
                                 GestureController gestureController,
                                 AudioInputController audioInputController) {
        this.activity = activity;
        this.sessionManager = sessionManager;
        this.audioPlayer = audioPlayer;
        this.gestureController = gestureController;
        this.audioInputController = audioInputController;
    }

    public void interruptSpeech() {
        try {
            if (sessionManager == null || !sessionManager.isConnected()) return;
            
            Log.d(TAG, "🚨 Interrupt: isResponseGenerating=" + activity.isResponseGenerating() + 
                       ", isAudioPlaying=" + activity.isAudioPlaying());
            
            if (activity.isResponseGenerating()) {
                JSONObject cancel = new JSONObject();
                cancel.put("type", "response.cancel");
                sessionManager.send(cancel.toString());
                
                activity.setCancelledResponseId(activity.getCurrentResponseId());
                activity.setResponseGenerating(false);
                Log.d(TAG, "Sent response.cancel for active generation");
            }

            // Note: lastAssistantItemId access needs a getter in Activity or passed in
            // Assuming Activity exposes it via package-private getter for now
            // Wait, better to move this state management out of Activity eventually.
            // For now, let's define how we access it. Activity has package-private accessors.
            String lastItemId = activity.getLastAssistantItemId();
            
            if (lastItemId != null) {
                int playedMs = audioPlayer != null ? Math.max(0, audioPlayer.getEstimatedPlaybackPositionMs()) : 0;
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
                activity.setAudioPlaying(false);
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

