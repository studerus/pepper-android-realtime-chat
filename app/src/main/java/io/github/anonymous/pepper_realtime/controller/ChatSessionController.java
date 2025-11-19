package io.github.anonymous.pepper_realtime.controller;

import android.util.Log;
import okhttp3.Response;
import java.util.List;
import java.util.Map;

import io.github.anonymous.pepper_realtime.ui.ChatActivity;
import io.github.anonymous.pepper_realtime.controller.GestureController;
import io.github.anonymous.pepper_realtime.R;
import io.github.anonymous.pepper_realtime.network.RealtimeEventHandler;
import io.github.anonymous.pepper_realtime.manager.ApiKeyManager;
import io.github.anonymous.pepper_realtime.manager.ThreadManager;
import io.github.anonymous.pepper_realtime.manager.SettingsManager;
import io.github.anonymous.pepper_realtime.manager.TurnManager;
import io.github.anonymous.pepper_realtime.network.RealtimeApiProvider;
import io.github.anonymous.pepper_realtime.network.RealtimeSessionManager;
import io.github.anonymous.pepper_realtime.ui.ChatMenuController;
import io.github.anonymous.pepper_realtime.ui.ChatMessage;

public class ChatSessionController {
    private static final String TAG = "ChatSessionController";

    private final ChatActivity activity;
    private final RealtimeSessionManager sessionManager;
    private final SettingsManager settingsManager;
    private final ApiKeyManager keyManager;
    private final AudioInputController audioInputController;
    private final ThreadManager threadManager;
    private final GestureController gestureController;
    private final ChatInterruptController interruptController;
    private final TurnManager turnManager;
    private final ChatMenuController chatMenuController;
    private final RealtimeEventHandler eventHandler;
    
    public ChatSessionController(ChatActivity activity,
                               RealtimeSessionManager sessionManager,
                               SettingsManager settingsManager,
                               ApiKeyManager keyManager,
                               AudioInputController audioInputController,
                               ThreadManager threadManager,
                               GestureController gestureController,
                               ChatInterruptController interruptController,
                               TurnManager turnManager,
                               ChatMenuController chatMenuController,
                               RealtimeEventHandler eventHandler) {
        this.activity = activity;
        this.sessionManager = sessionManager;
        this.settingsManager = settingsManager;
        this.keyManager = keyManager;
        this.audioInputController = audioInputController;
        this.threadManager = threadManager;
        this.gestureController = gestureController;
        this.interruptController = interruptController;
        this.turnManager = turnManager;
        this.chatMenuController = chatMenuController;
        this.eventHandler = eventHandler;
    }

    public void startNewSession() {
        Log.i(TAG, "Starting new session...");
        audioInputController.unmute();
        activity.setLastChatBubbleResponseId(null);
        
        activity.runOnUiThread(() -> {
            activity.setStatusText(activity.getString(R.string.status_starting_new_session));
            activity.clearMessages();
        });

        threadManager.executeNetwork(() -> {
            threadManager.executeIO(activity::deleteSessionImages);
            audioInputController.cleanupForRestart();
            gestureController.stopNow();
            if (turnManager != null) {
                turnManager.setState(TurnManager.State.LISTENING);
            }
            
            // Reset flags via Activity accessors
            activity.setResponseGenerating(false);
            activity.setAudioPlaying(false);
            activity.setCurrentResponseId(null);
            activity.setCancelledResponseId(null);
            activity.setLastChatBubbleResponseId(null);
            activity.setExpectingFinalAnswerAfterToolCall(false);
            
            disconnectWebSocketGracefully();
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            
            connectWebSocket(new ChatActivity.WebSocketConnectionCallback() {
                @Override
                public void onSuccess() {
                    Log.i(TAG, "New session started successfully.");
                    if (!settingsManager.isUsingRealtimeAudioInput() && !audioInputController.isSttRunning()) {
                        threadManager.executeAudio(() -> {
                            try {
                                audioInputController.setupSpeechRecognizer();
                                activity.runOnUiThread(() -> audioInputController.startContinuousRecognition());
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to setup Azure Speech after session restart", e);
                            }
                        });
                    } else {
                        audioInputController.startContinuousRecognition();
                    }
                }
                @Override
                public void onError(Throwable error) {
                    if (error.getMessage() != null && 
                        error.getMessage().contains("WebSocket closed before session was updated")) {
                        Log.w(TAG, "Harmless race condition during new session start");
                    } else {
                        Log.e(TAG, "Failed to start new session", error);
                        activity.runOnUiThread(() -> {
                            activity.addMessage(activity.getString(R.string.new_session_error), ChatMessage.Sender.ROBOT);
                            activity.setStatusText(activity.getString(R.string.error_connection_failed_short));
                        });
                    }
                }
            });
        });
    }
    
    public void connectWebSocket(ChatActivity.WebSocketConnectionCallback callback) {
        if (sessionManager.isConnected()) {
            if (callback != null) callback.onSuccess();
            return;
        }
        
        activity.setConnectionCallback(callback);
        
        try {
            RealtimeApiProvider provider = settingsManager.getApiProvider();
            String selectedModel = settingsManager.getModel();
            String azureEndpoint = keyManager.getAzureOpenAiEndpoint();

            String url = provider.getWebSocketUrl(azureEndpoint, selectedModel);
            
            java.util.HashMap<String, String> headers = new java.util.HashMap<>();
            if (provider.isAzureProvider()) {
                headers.put("api-key", keyManager.getAzureOpenAiKey());
            } else {
                headers.put("Authorization", provider.getAuthorizationHeader(keyManager.getAzureOpenAiKey(), keyManager.getOpenAiApiKey()));
            }
            if (!"gpt-realtime".equals(selectedModel)) headers.put("OpenAI-Beta", "realtime=v1");
            
            sessionManager.connect(url, headers);
            
        } catch (Exception e) {
            Log.e(TAG, "Error generating WebSocket connection parameters", e);
            if (callback != null) {
                callback.onError(e);
            }
        }
    }

    public void disconnectWebSocket() {
        if (sessionManager != null) {
            Log.i(TAG, "Disconnecting WebSocket...");
            sessionManager.close(1000, "User initiated disconnect");
        }
    }

    public void disconnectWebSocketGracefully() {
        if (sessionManager != null) {
            Log.i(TAG, "Gracefully disconnecting WebSocket...");
            activity.setConnectionCallback(null);
            
            if ((activity.isResponseGenerating() || activity.isAudioPlaying()) && activity.getCurrentResponseId() != null) {
                Log.d(TAG, "Cancelling active response before disconnect");
                activity.setCancelledResponseId(activity.getCurrentResponseId());
                activity.setResponseGenerating(false);
                activity.setAudioPlaying(false);
                activity.setCurrentResponseId(null);
            }
            sessionManager.close(1000, "Provider switch");
        }
    }
}
