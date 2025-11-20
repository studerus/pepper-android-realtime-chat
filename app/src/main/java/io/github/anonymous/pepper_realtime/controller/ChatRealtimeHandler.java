package io.github.anonymous.pepper_realtime.controller;

import android.util.Log;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.anonymous.pepper_realtime.manager.ThreadManager;
import io.github.anonymous.pepper_realtime.manager.TurnManager;
import io.github.anonymous.pepper_realtime.manager.AudioPlayer;
import io.github.anonymous.pepper_realtime.network.RealtimeEventHandler;
import io.github.anonymous.pepper_realtime.ui.ChatActivity;
import io.github.anonymous.pepper_realtime.R;
import io.github.anonymous.pepper_realtime.tools.ToolContext;
import io.github.anonymous.pepper_realtime.tools.ToolRegistry;
import io.github.anonymous.pepper_realtime.ui.ChatMessage;

public class ChatRealtimeHandler implements RealtimeEventHandler.Listener {
    private static final String TAG = "ChatRealtimeHandler";

    private final ChatActivity activity;
    private final AudioPlayer audioPlayer;
    private final TurnManager turnManager;
    private final TextView statusTextView;
    private final ThreadManager threadManager;
    private final ToolRegistry toolRegistry;
    private final ToolContext toolContext;

    public ChatRealtimeHandler(ChatActivity activity, 
                             AudioPlayer audioPlayer, 
                             TurnManager turnManager, 
                             TextView statusTextView,
                             ThreadManager threadManager,
                             ToolRegistry toolRegistry,
                             ToolContext toolContext) {
        this.activity = activity;
        this.audioPlayer = audioPlayer;
        this.turnManager = turnManager;
        this.statusTextView = statusTextView;
        this.threadManager = threadManager;
        this.toolRegistry = toolRegistry;
        this.toolContext = toolContext;
    }

    @Override
    public void onSessionUpdated(JSONObject session) {
        Log.i(TAG, "Session configured successfully - completing connection promise.");
        // Try to read output_audio_format.sample_rate_hz to align AudioTrack
        try {
            if (session != null && session.has("output_audio_format")) {
                JSONObject fmt = session.optJSONObject("output_audio_format");
                if (fmt != null) {
                    int sr = fmt.optInt("sample_rate_hz", 0);
                    if (sr > 0 && audioPlayer != null) {
                        Log.i(TAG, "Applying session sample rate: " + sr);
                        audioPlayer.setSampleRate(sr);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to apply session sample rate", e);
        }
        if (!activity.isWarmingUp()) {
            activity.runOnUiThread(() -> statusTextView.setText(activity.getString(R.string.status_ready)));
        }
        activity.completeConnectionPromise();
    }

    @Override
    public void onAudioTranscriptDelta(String delta, String responseId) {
        activity.runOnUiThread(() -> {
            if (Objects.equals(responseId, activity.getCancelledResponseId())) {
                return; // drop transcript of cancelled response
            }
            CharSequence current = statusTextView.getText();
            if (current == null || current.length() == 0 || !current.toString().startsWith("Speaking — tap to interrupt: ")) {
                statusTextView.setText(activity.getString(R.string.status_speaking_tap_to_interrupt));
            }
            statusTextView.append(delta);
            boolean needNew = activity.isExpectingFinalAnswerAfterToolCall()
                    || activity.isMessageListEmpty()
                    || !activity.isLastMessageFromRobot()
                    || !Objects.equals(responseId, activity.getLastChatBubbleResponseId());
            if (needNew) {
                activity.addMessage(delta, ChatMessage.Sender.ROBOT);
                activity.setExpectingFinalAnswerAfterToolCall(false);
                activity.setLastChatBubbleResponseId(responseId);
            } else {
                activity.appendToLastMessage(delta);
            }
        });
    }

    @Override
    public void onAudioDelta(byte[] pcm16, String responseId) {
        // Ignore audio from cancelled response
        if (Objects.equals(responseId, activity.getCancelledResponseId())) {
            return;
        }
        
        if (!audioPlayer.isPlaying()) {
            turnManager.setState(TurnManager.State.SPEAKING);
        }
        if (responseId != null) {
            if (!Objects.equals(activity.getCurrentResponseId(), responseId)) {
                try { audioPlayer.onResponseBoundary(); } catch (Exception ignored) {}
                activity.setCurrentResponseId(responseId);
            }
        }
        
        activity.setAudioPlaying(true);  // Audio chunks are being played
        audioPlayer.addChunk(pcm16);
        audioPlayer.startIfNeeded();
    }

    @Override
    public void onAudioDone() {
        audioPlayer.markResponseDone();
        // Note: Audio might still be in queue, isAudioPlaying will be set to false
        // when audioPlayer actually finishes playing all chunks
    }

    @Override
    public void onResponseDone(JSONObject response) {
        activity.setResponseGenerating(false);  // API finished generating
        Log.i(TAG, "Full response received. Processing final output.");
        try {
            JSONArray outputArray = response.optJSONArray("output");

            if (outputArray == null || outputArray.length() == 0) {
                Log.i(TAG, "Response.done with no output. Finishing turn and returning to LISTENING.");
                // Ensure we return to LISTENING state even with empty responses
                if (turnManager != null && !audioPlayer.isPlaying()) {
                    turnManager.setState(TurnManager.State.LISTENING);
                }
                return;
            }

            List<JSONObject> functionCalls = new ArrayList<>();
            List<JSONObject> messageItems = new ArrayList<>();

            for (int i = 0; i < outputArray.length(); i++) {
                JSONObject out = outputArray.getJSONObject(i);
                String outType = out.optString("type");
                if ("function_call".equals(outType)) {
                    functionCalls.add(out);
                } else if ("message".equals(outType)) {
                    messageItems.add(out);
                }
            }

            if (!functionCalls.isEmpty()) {
                for (JSONObject fc : functionCalls) {
                    String toolName = fc.getString("name");
                    String callId = fc.getString("call_id");
                    String argsString = fc.getString("arguments");
                    final JSONObject args = new JSONObject(argsString);

                    final String fToolName = toolName;
                    final String fCallId = callId;
                    activity.runOnUiThread(() -> activity.addFunctionCall(fToolName, args.toString()));

                    activity.setExpectingFinalAnswerAfterToolCall(true);
                    threadManager.executeNetwork(() -> {
                        String toolResult;
                        try {
                            toolResult = toolRegistry.executeTool(fToolName, args, toolContext);
                        } catch (Exception toolEx) {
                            Log.e(TAG, "Tool execution crashed for " + fToolName, toolEx);
                            try {
                                toolResult = new JSONObject()
                                        .put("error", "Tool execution failed: " + (toolEx.getMessage() != null ? toolEx.getMessage() : "Unknown error"))
                                        .toString();
                            } catch (Exception jsonEx) {
                                toolResult = "{\"error\":\"Tool execution failed.\"}";
                            }
                        }

                        if (toolResult == null) {
                            toolResult = "{\"error\":\"Tool returned no result.\"}";
                        }

                        final String fResult = toolResult;
                        activity.runOnUiThread(() -> activity.updateFunctionCallResult(fResult));
                        activity.sendToolResult(fCallId, fResult);
                    });
                }
            }

            if (!messageItems.isEmpty()) {
                try {
                    JSONObject firstMsg = messageItems.get(0);
                    JSONObject assistantMessage = new JSONObject();
                    assistantMessage.put("role", "assistant");
                    assistantMessage.put("content", firstMsg.optJSONArray("content"));
                    Log.d(TAG, "Final assistant message added to local history.");
                } catch (Exception e) {
                    Log.e(TAG, "Could not save final assistant message to history", e);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing response.done message. Attempting to recover.", e);
        }
    }

    @Override
    public void onAssistantItemAdded(String itemId) {
        try {
            activity.setLastAssistantItemId(itemId);
        } catch (Exception ignored) {}
    }

    @Override
    public void onResponseBoundary(String newResponseId) {
        try {
            Log.d(TAG, "Response boundary detected - new ID: " + newResponseId + ", previous ID: " + activity.getCurrentResponseId());
            if (audioPlayer != null) {
                audioPlayer.onResponseBoundary();
            }
        } catch (Exception e) {
            Log.w(TAG, "Error during response boundary reset", e);
        }
    }

    @Override
    public void onResponseCreated(String responseId) {
        try {
            activity.setResponseGenerating(true);  // API started generating
            Log.d(TAG, "New response created with ID: " + responseId);
        } catch (Exception ignored) {}
    }

    @Override
    public void onError(JSONObject error) {
        String code = error != null ? error.optString("code", "Unknown") : "Unknown";
        String msg = error != null ? error.optString("message", "An unknown error occurred.") : "";
        Log.e(TAG, "WebSocket Error Received - Code: " + code + ", Message: " + msg);
        activity.runOnUiThread(() -> {
            activity.addMessage(activity.getString(R.string.server_error_prefix, msg), ChatMessage.Sender.ROBOT);
            statusTextView.setText(activity.getString(R.string.error_generic, code));
        });
        activity.failConnectionPromise("Server returned an error during setup: " + msg);
    }

    // User audio input events (Realtime API audio mode)
    @Override
    public void onUserSpeechStarted(String itemId) {
        activity.runOnUiThread(() -> {
            statusTextView.setText(activity.getString(R.string.status_listening));
            Log.d(TAG, "User speech started (Realtime API): " + itemId);
        });
    }

    @Override
    public void onUserSpeechStopped(String itemId) {
        activity.runOnUiThread(() -> activity.handleUserSpeechStopped(itemId));
    }

    @Override
    public void onUserItemCreated(String itemId, JSONObject item) {
        Log.d(TAG, "User conversation item created: " + itemId);
    }

    @Override
    public void onUserTranscriptCompleted(String itemId, String transcript) {
        activity.runOnUiThread(() -> activity.handleUserTranscriptCompleted(itemId, transcript));
    }

    @Override
    public void onUserTranscriptFailed(String itemId, JSONObject error) {
        activity.runOnUiThread(() -> activity.handleUserTranscriptFailed(itemId, error));
    }

    @Override
    public void onUnknown(String type, JSONObject raw) {
        Log.w(TAG, "Unknown WebSocket message type: " + type);
    }
}

