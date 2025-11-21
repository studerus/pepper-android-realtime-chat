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
import io.github.anonymous.pepper_realtime.ui.ChatViewModel;

public class ChatRealtimeHandler implements RealtimeEventHandler.Listener {
    private static final String TAG = "ChatRealtimeHandler";

    private final ChatActivity activity; // Kept for context-dependent calls (runOnUiThread, resources)
    private final ChatViewModel viewModel;
    private final AudioPlayer audioPlayer;
    private final TurnManager turnManager;
    private final ThreadManager threadManager;
    private final ToolRegistry toolRegistry;
    private ToolContext toolContext;
    private ChatSessionController sessionController;

    public void setSessionController(ChatSessionController sessionController) {
        this.sessionController = sessionController;
    }

    public void setToolContext(ToolContext toolContext) {
        this.toolContext = toolContext;
    }

    public ChatRealtimeHandler(ChatActivity activity,
            ChatViewModel viewModel,
            AudioPlayer audioPlayer,
            TurnManager turnManager,
            ThreadManager threadManager,
            ToolRegistry toolRegistry,
            ToolContext toolContext) {
        this.activity = activity;
        this.viewModel = viewModel;
        this.audioPlayer = audioPlayer;
        this.turnManager = turnManager;
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
        if (!Boolean.TRUE.equals(viewModel.getIsWarmingUp().getValue())) {
            // Status update removed to prevent "Ready" state flickering before "Listening"
            // viewModel.setStatusText(activity.getString(R.string.status_ready));
        }
        if (sessionController != null) {
            sessionController.completeConnectionPromise();
        } else {
            Log.i(TAG, "SessionController null, cannot complete promise explicitly");
        }
    }

    @Override
    public void onAudioTranscriptDelta(String delta, String responseId) {
        activity.runOnUiThread(() -> {
            if (Objects.equals(responseId, viewModel.getCancelledResponseId())) {
                return; // drop transcript of cancelled response
            }
            String currentStatus = viewModel.getStatusText().getValue();
            if (currentStatus == null || !currentStatus.startsWith("Speaking — tap to interrupt: ")) {
                viewModel.setStatusText(activity.getString(R.string.status_speaking_tap_to_interrupt));
            }
            // Append to status text logic removed as it's not standard MVVM to append to
            // LiveData string repeatedly for UI effect
            // Instead we rely on the message list update below

            boolean needNew = viewModel.isExpectingFinalAnswerAfterToolCall()
                    || isMessageListEmpty()
                    || !isLastMessageFromRobot()
                    || !Objects.equals(responseId, viewModel.getLastChatBubbleResponseId());
            if (needNew) {
                viewModel.addMessage(new ChatMessage(delta, ChatMessage.Sender.ROBOT));
                viewModel.setExpectingFinalAnswerAfterToolCall(false);
                viewModel.setLastChatBubbleResponseId(responseId);
            } else {
                viewModel.appendToLastMessage(delta);
            }
        });
    }

    @Override
    public void onAudioTranscriptDone(String transcript, String responseId) {
        activity.runOnUiThread(() -> {
            if (Objects.equals(responseId, viewModel.getCancelledResponseId())) {
                return;
            }
            // Ensure the final text is correct by replacing the last message content
            // This fixes issues where deltas might have been dropped or incomplete
            if (isLastMessageFromRobot() && Objects.equals(responseId, viewModel.getLastChatBubbleResponseId())) {
                // We can use updateMessageByItemId if we had the item ID, but here we assume
                // it's the last message
                // Since we don't have the item ID easily available here without tracking it,
                // we'll use a new method in ViewModel or just update the last message if it
                // matches.
                // Actually, appendToLastMessage appends. We need a "replaceLastMessage" or
                // similar.
                // But wait, updateMessageByItemId needs an ID.
                // Let's assume the last message IS the one we want to update.

                // Better approach: The ViewModel's appendToLastMessage logic is robust for
                // deltas.
                // But for "Done", we want to enforce the final string.
                // We can add a method to ViewModel: updateLastRobotMessage(String newText)
                viewModel.updateLastRobotMessage(transcript);
            }
        });
    }

    private boolean isMessageListEmpty() {
        List<ChatMessage> list = viewModel.getMessageList().getValue();
        return list == null || list.isEmpty();
    }

    private boolean isLastMessageFromRobot() {
        List<ChatMessage> list = viewModel.getMessageList().getValue();
        if (list == null || list.isEmpty())
            return false;
        return list.get(list.size() - 1).getSender() == ChatMessage.Sender.ROBOT;
    }

    @Override
    public void onAudioDelta(byte[] pcm16, String responseId) {
        // Ignore audio from cancelled response
        if (Objects.equals(responseId, viewModel.getCancelledResponseId())) {
            return;
        }

        if (!audioPlayer.isPlaying()) {
            turnManager.setState(TurnManager.State.SPEAKING);
        }
        if (responseId != null) {
            if (!Objects.equals(viewModel.getCurrentResponseId(), responseId)) {
                try {
                    audioPlayer.onResponseBoundary();
                } catch (Exception ignored) {
                }
                viewModel.setCurrentResponseId(responseId);
            }
        }

        viewModel.setAudioPlaying(true); // Audio chunks are being played
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
        viewModel.setResponseGenerating(false); // API finished generating
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

                    viewModel.setExpectingFinalAnswerAfterToolCall(true);
                    threadManager.executeNetwork(() -> {
                        String toolResult;
                        try {
                            toolResult = toolRegistry.executeTool(fToolName, args, toolContext);
                        } catch (Exception toolEx) {
                            Log.e(TAG, "Tool execution crashed for " + fToolName, toolEx);
                            try {
                                toolResult = new JSONObject()
                                        .put("error", "Tool execution failed: "
                                                + (toolEx.getMessage() != null ? toolEx.getMessage() : "Unknown error"))
                                        .toString();
                            } catch (Exception jsonEx) {
                                toolResult = "{\"error\":\"Tool execution failed.\"}";
                            }
                        }

                        if (toolResult == null) {
                            toolResult = "{\"error\":\"Tool returned no result.\"}";
                        }

                        // ... (inside onResponseDone)
                        final String fResult = toolResult;
                        activity.runOnUiThread(() -> activity.updateFunctionCallResult(fResult));
                        if (sessionController != null) {
                            sessionController.sendToolResult(fCallId, fResult);
                        } else {
                            Log.e(TAG, "SessionController is null, cannot send tool result");
                        }
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
            viewModel.setLastAssistantItemId(itemId);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onResponseBoundary(String newResponseId) {
        try {
            Log.d(TAG, "Response boundary detected - new ID: " + newResponseId + ", previous ID: "
                    + viewModel.getCurrentResponseId());
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
            viewModel.setResponseGenerating(true); // API started generating
            Log.d(TAG, "New response created with ID: " + responseId);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onError(JSONObject error) {
        String code = error != null ? error.optString("code", "Unknown") : "Unknown";
        String msg = error != null ? error.optString("message", "An unknown error occurred.") : "";
        Log.e(TAG, "WebSocket Error Received - Code: " + code + ", Message: " + msg);
        activity.runOnUiThread(() -> {
            viewModel.addMessage(
                    new ChatMessage(activity.getString(R.string.server_error_prefix, msg), ChatMessage.Sender.ROBOT));
            viewModel.setStatusText(activity.getString(R.string.error_generic, code));
        });
        if (sessionController != null) {
            sessionController.failConnectionPromise("Server returned an error during setup: " + msg);
        } else {
            Log.e(TAG, "SessionController null, cannot fail promise explicitly");
        }
    }

    // User audio input events (Realtime API audio mode)
    @Override
    public void onUserSpeechStarted(String itemId) {
        activity.runOnUiThread(() -> {
            viewModel.setStatusText(activity.getString(R.string.status_listening));
            Log.d(TAG, "User speech started (Realtime API): " + itemId);

            // Add placeholder message to reserve spot in chat history
            ChatMessage placeholder = new ChatMessage("...", ChatMessage.Sender.USER);
            placeholder.setItemId(itemId);
            Log.d(TAG, "Created placeholder with itemId: " + itemId + ", UUID: " + placeholder.getUuid());
            viewModel.addMessage(placeholder);
        });
    }

    @Override
    public void onUserSpeechStopped(String itemId) {
        activity.runOnUiThread(() -> {
            // Just log or update status if needed
            Log.d(TAG, "User speech stopped: " + itemId);
        });
    }

    @Override
    public void onUserTranscriptCompleted(String itemId, String transcript) {
        activity.runOnUiThread(() -> {
            if (transcript != null && !transcript.isEmpty()) {
                Log.d(TAG, "Attempting to update message with itemId: " + itemId + ", transcript: " + transcript);
                // Try to update existing placeholder first
                boolean updated = viewModel.updateMessageByItemId(itemId, transcript);

                // If no placeholder found (e.g. missed event), add new message
                if (!updated) {
                    Log.w(TAG, "No placeholder found for item " + itemId + ", adding new message");
                    ChatMessage msg = new ChatMessage(transcript, ChatMessage.Sender.USER);
                    msg.setItemId(itemId);
                    viewModel.addMessage(msg);
                } else {
                    Log.d(TAG, "Successfully updated placeholder for itemId: " + itemId);
                }
            }
        });
    }

    @Override
    public void onUserTranscriptFailed(String itemId, JSONObject error) {
        activity.runOnUiThread(() -> {
            String msg = error != null ? error.optString("message", "Unknown error") : "Unknown error";
            Log.w(TAG, "User transcript failed: " + msg);

            // Update placeholder to show error
            viewModel.updateMessageByItemId(itemId, "(Transcription failed)");
        });
    }

    @Override
    public void onUnknown(String type, JSONObject raw) {
        Log.w(TAG, "Unknown WebSocket message type: " + type);
    }
}
