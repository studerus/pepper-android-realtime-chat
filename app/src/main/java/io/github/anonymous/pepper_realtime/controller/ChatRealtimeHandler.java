package io.github.anonymous.pepper_realtime.controller;

import android.util.Log;
import com.google.gson.JsonObject;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.github.anonymous.pepper_realtime.manager.ThreadManager;
import io.github.anonymous.pepper_realtime.manager.TurnManager;
import io.github.anonymous.pepper_realtime.manager.AudioPlayer;
import io.github.anonymous.pepper_realtime.network.RealtimeEventHandler;
import io.github.anonymous.pepper_realtime.network.RealtimeEvents;
import io.github.anonymous.pepper_realtime.ui.ChatActivity;
import io.github.anonymous.pepper_realtime.R;
import io.github.anonymous.pepper_realtime.tools.ToolContext;
import io.github.anonymous.pepper_realtime.tools.ToolRegistry;
import io.github.anonymous.pepper_realtime.ui.ChatMessage;
import io.github.anonymous.pepper_realtime.ui.ChatViewModel;

public class ChatRealtimeHandler implements RealtimeEventHandler.Listener {
    private static final String TAG = "ChatRealtimeHandler";

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

    public ChatRealtimeHandler(ChatViewModel viewModel,
            AudioPlayer audioPlayer,
            TurnManager turnManager,
            ThreadManager threadManager,
            ToolRegistry toolRegistry,
            ToolContext toolContext) {
        this.viewModel = viewModel;
        this.audioPlayer = audioPlayer;
        this.turnManager = turnManager;
        this.threadManager = threadManager;
        this.toolRegistry = toolRegistry;
        this.toolContext = toolContext;
    }

    @Override
    public void onSessionUpdated(RealtimeEvents.SessionUpdated event) {
        Log.i(TAG, "Session configured successfully - completing connection promise.");

        if (!Boolean.TRUE.equals(viewModel.getIsWarmingUp().getValue())) {
            // Status update removed to prevent "Ready" state flickering before "Listening"
        }
        if (sessionController != null) {
            sessionController.completeConnectionPromise();
        } else {
            Log.i(TAG, "SessionController null, cannot complete promise explicitly");
        }
    }

    @Override
    public void onAudioTranscriptDelta(String delta, String responseId) {
        if (Objects.equals(responseId, viewModel.getCancelledResponseId())) {
            return; // drop transcript of cancelled response
        }
        String currentStatus = viewModel.getStatusText().getValue();
        if (currentStatus == null || !currentStatus.startsWith("Speaking — tap to interrupt: ")) {
            viewModel.setStatusText(viewModel.getApplication().getString(R.string.status_speaking_tap_to_interrupt));
        }

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
    }

    @Override
    public void onAudioTranscriptDone(String transcript, String responseId) {
        if (Objects.equals(responseId, viewModel.getCancelledResponseId())) {
            return;
        }
        if (isLastMessageFromRobot() && Objects.equals(responseId, viewModel.getLastChatBubbleResponseId())) {
            viewModel.updateLastRobotMessage(transcript);
        }
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
    }

    @Override
    public void onResponseDone(RealtimeEvents.ResponseDone event) {
        viewModel.setResponseGenerating(false); // API finished generating
        Log.i(TAG, "Full response received. Processing final output.");
        try {
            if (event.response == null || event.response.output == null || event.response.output.isEmpty()) {
                Log.i(TAG, "Response.done with no output. Finishing turn and returning to LISTENING.");
                if (turnManager != null && !audioPlayer.isPlaying()) {
                    turnManager.setState(TurnManager.State.LISTENING);
                }
                return;
            }

            List<RealtimeEvents.Item> functionCalls = new ArrayList<>();
            List<RealtimeEvents.Item> messageItems = new ArrayList<>();

            for (RealtimeEvents.Item item : event.response.output) {
                if ("function_call".equals(item.type)) {
                    functionCalls.add(item);
                } else if ("message".equals(item.type)) {
                    messageItems.add(item);
                }
            }

            if (!functionCalls.isEmpty()) {
                for (RealtimeEvents.Item fc : functionCalls) {
                    String toolName = fc.name;
                    String callId = fc.callId;
                    String argsString = fc.arguments;

                    // Convert to JSONObject for ToolRegistry compatibility
                    final JSONObject args = new JSONObject(argsString);

                    final String fToolName = toolName;
                    final String fCallId = callId;

                    ChatMessage functionCall = ChatMessage.createFunctionCall(fToolName, args.toString(),
                            ChatMessage.Sender.ROBOT);
                    viewModel.addMessage(functionCall);

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

                        final String fResult = toolResult;
                        viewModel.updateLatestFunctionCallResult(fResult);
                        if (sessionController != null) {
                            sessionController.sendToolResult(fCallId, fResult);
                        } else {
                            Log.e(TAG, "SessionController is null, cannot send tool result");
                        }
                    });
                }
            }

            if (!messageItems.isEmpty()) {
                Log.d(TAG, "Final assistant message added to local history.");
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
    public void onError(RealtimeEvents.ErrorEvent event) {
        String code = (event.error != null && event.error.code != null) ? event.error.code : "Unknown";
        String msg = (event.error != null && event.error.message != null) ? event.error.message
                : "An unknown error occurred.";

        Log.e(TAG, "WebSocket Error Received - Code: " + code + ", Message: " + msg);

        viewModel.addMessage(
                new ChatMessage(viewModel.getApplication().getString(R.string.server_error_prefix, msg),
                        ChatMessage.Sender.ROBOT));
        viewModel.setStatusText(viewModel.getApplication().getString(R.string.error_generic, code));

        if (sessionController != null) {
            sessionController.failConnectionPromise("Server returned an error during setup: " + msg);
        } else {
            Log.e(TAG, "SessionController null, cannot fail promise explicitly");
        }
    }

    @Override
    public void onUserSpeechStarted(String itemId) {
        viewModel.setStatusText(viewModel.getApplication().getString(R.string.status_listening));
        Log.d(TAG, "User speech started (Realtime API): " + itemId);

        ChatMessage placeholder = new ChatMessage("...", ChatMessage.Sender.USER);
        placeholder.setItemId(itemId);
        Log.d(TAG, "Created placeholder with itemId: " + itemId + ", UUID: " + placeholder.getUuid());
        viewModel.addMessage(placeholder);
    }

    @Override
    public void onUserSpeechStopped(String itemId) {
        Log.d(TAG, "User speech stopped: " + itemId);
    }

    @Override
    public void onUserTranscriptCompleted(String itemId, String transcript) {
        if (transcript != null && !transcript.isEmpty()) {
            Log.d(TAG, "Attempting to update message with itemId: " + itemId + ", transcript: " + transcript);
            boolean updated = viewModel.updateMessageByItemId(itemId, transcript);

            if (!updated) {
                Log.w(TAG, "No placeholder found for item " + itemId + ", adding new message");
                ChatMessage msg = new ChatMessage(transcript, ChatMessage.Sender.USER);
                msg.setItemId(itemId);
                viewModel.addMessage(msg);
            } else {
                Log.d(TAG, "Successfully updated placeholder for itemId: " + itemId);
            }
        }
    }

    @Override
    public void onUserTranscriptFailed(String itemId, RealtimeEvents.UserTranscriptFailed event) {
        String msg = (event.error != null && event.error.message != null) ? event.error.message : "Unknown error";
        Log.w(TAG, "User transcript failed: " + msg);

        viewModel.updateMessageByItemId(itemId, "(Transcription failed)");
    }

    @Override
    public void onUnknown(String type, JsonObject raw) {
        Log.w(TAG, "Unknown WebSocket message type: " + type);
    }
}
