package io.github.anonymous.pepper_realtime.controller;

import android.util.Log;
import okhttp3.Response;
import java.util.List;
import java.util.Map;

import io.github.anonymous.pepper_realtime.controller.GestureController;
import io.github.anonymous.pepper_realtime.R;
import io.github.anonymous.pepper_realtime.network.RealtimeEventHandler;
import io.github.anonymous.pepper_realtime.manager.ApiKeyManager;
import io.github.anonymous.pepper_realtime.manager.ThreadManager;
import io.github.anonymous.pepper_realtime.manager.SettingsRepository;
import io.github.anonymous.pepper_realtime.manager.TurnManager;
import io.github.anonymous.pepper_realtime.network.RealtimeApiProvider;
import io.github.anonymous.pepper_realtime.network.RealtimeSessionManager;
import io.github.anonymous.pepper_realtime.network.WebSocketConnectionCallback;
import io.github.anonymous.pepper_realtime.ui.ChatMenuController;
import io.github.anonymous.pepper_realtime.ui.ChatMessage;
import io.github.anonymous.pepper_realtime.ui.ChatViewModel;
import io.github.anonymous.pepper_realtime.manager.AudioPlayer;
import io.github.anonymous.pepper_realtime.manager.SessionImageManager;

public class ChatSessionController {
    private static final String TAG = "ChatSessionController";

    private final ChatViewModel viewModel;
    private final RealtimeSessionManager sessionManager;
    private final SettingsRepository settingsRepository;
    private final ApiKeyManager keyManager;
    private final AudioInputController audioInputController;
    private final ThreadManager threadManager;
    private final GestureController gestureController;
    private final TurnManager turnManager;
    private final ChatInterruptController interruptController;
    private final AudioPlayer audioPlayer;
    private final RealtimeEventHandler eventHandler;
    private final SessionImageManager sessionImageManager;
    private WebSocketConnectionCallback connectionCallback;

    public ChatSessionController(
            ChatViewModel viewModel,
            RealtimeSessionManager sessionManager,
            SettingsRepository settingsRepository,
            ApiKeyManager keyManager,
            AudioInputController audioInputController,
            ThreadManager threadManager,
            GestureController gestureController,
            TurnManager turnManager,
            ChatInterruptController interruptController,
            AudioPlayer audioPlayer,
            RealtimeEventHandler eventHandler,
            SessionImageManager sessionImageManager) {
        this.viewModel = viewModel;
        this.sessionManager = sessionManager;
        this.settingsRepository = settingsRepository;
        this.keyManager = keyManager;
        this.audioInputController = audioInputController;

        this.threadManager = threadManager;
        this.gestureController = gestureController;
        this.turnManager = turnManager;
        this.interruptController = interruptController;
        this.audioPlayer = audioPlayer;
        this.eventHandler = eventHandler;
        this.sessionImageManager = sessionImageManager;

        setupSessionManagerListeners();
        setupAudioPlayerListener();
    }

    public void sendMessageToRealtimeAPI(String text, boolean requestResponse, boolean allowInterrupt) {
        if (sessionManager == null || !sessionManager.isConnected()) {
            Log.e(TAG, "WebSocket is not connected.");
            if (requestResponse) {
                viewModel.addMessage(new ChatMessage(viewModel.getApplication().getString(R.string.error_not_connected),
                        ChatMessage.Sender.ROBOT));
            }
            return;
        }

        if (allowInterrupt && requestResponse && turnManager != null
                && turnManager.getState() == TurnManager.State.SPEAKING) {
            // Simple explicit interrupt via controller if playing
            if (Boolean.TRUE.equals(viewModel.getIsAudioPlaying().getValue())
                    || (audioPlayer != null && audioPlayer.isPlaying())) {
                interruptController.interruptSpeech();
            }
        }

        if (requestResponse && turnManager != null) {
            turnManager.setState(TurnManager.State.THINKING);
        }

        threadManager.executeNetwork(() -> {
            try {
                boolean sentItem = sessionManager.sendUserTextMessage(text);
                if (!sentItem) {
                    Log.e(TAG, "Failed to send message - WebSocket connection broken");
                    viewModel.addMessage(new ChatMessage(
                            viewModel.getApplication().getString(R.string.error_connection_lost_message),
                            ChatMessage.Sender.ROBOT));
                    if (turnManager != null)
                        turnManager.setState(TurnManager.State.IDLE);
                    return;
                }

                if (requestResponse) {
                    if (Boolean.TRUE.equals(viewModel.getIsResponseGenerating().getValue())) {
                        interruptController.interruptSpeech();
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ignored) {
                        }
                    } else if (Boolean.TRUE.equals(viewModel.getIsAudioPlaying().getValue()) && allowInterrupt) {
                        if (audioPlayer != null) {
                            audioPlayer.interruptNow();
                            viewModel.setAudioPlaying(false);
                        }
                    }

                    viewModel.setResponseGenerating(true);
                    boolean sentResponse = sessionManager.requestResponse();
                    if (!sentResponse) {
                        viewModel.setResponseGenerating(false);
                        Log.e(TAG, "Failed to send response request");
                        viewModel.addMessage(new ChatMessage(
                                viewModel.getApplication().getString(R.string.error_connection_lost_response),
                                ChatMessage.Sender.ROBOT));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception in sendMessageToRealtimeAPI", e);
                if (requestResponse) {
                    viewModel.setResponseGenerating(false);
                    viewModel.addMessage(
                            new ChatMessage(viewModel.getApplication().getString(R.string.error_processing_message),
                                    ChatMessage.Sender.ROBOT));
                    if (turnManager != null)
                        turnManager.setState(TurnManager.State.IDLE);
                }
            }
        });
    }

    public void sendToolResult(String callId, String result) {
        if (sessionManager == null || !sessionManager.isConnected())
            return;
        try {
            viewModel.setExpectingFinalAnswerAfterToolCall(true);
            boolean sentTool = sessionManager.sendToolResult(callId, result);
            if (!sentTool) {
                Log.e(TAG, "Failed to send tool result");
                return;
            }
            viewModel.setResponseGenerating(true);
            boolean sentToolResponse = sessionManager.requestResponse();
            if (!sentToolResponse) {
                viewModel.setResponseGenerating(false);
                Log.e(TAG, "Failed to send tool response request");
                return;
            }
            if (turnManager != null && turnManager.getState() != TurnManager.State.SPEAKING) {
                turnManager.setState(TurnManager.State.THINKING);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending tool result", e);
            viewModel.setResponseGenerating(false);
        }
    }

    public void startNewSession() {
        Log.i(TAG, "Starting new session...");
        if (audioInputController.isMuted()) {
            audioInputController.resetMuteState();
        }
        viewModel.setLastChatBubbleResponseId(null);

        viewModel.setStatusText(viewModel.getApplication().getString(R.string.status_starting_new_session));
        viewModel.clearMessages();

        threadManager.executeNetwork(() -> {
            threadManager.executeIO(() -> this.sessionImageManager.deleteAllImages());
            audioInputController.cleanupForRestart();
            gestureController.stopNow();
            if (turnManager != null) {
                turnManager.setState(TurnManager.State.IDLE);
            }

            // Reset flags via ViewModel
            viewModel.setResponseGenerating(false);
            viewModel.setAudioPlaying(false);
            viewModel.setCurrentResponseId(null);
            viewModel.setCancelledResponseId(null);
            viewModel.setLastChatBubbleResponseId(null);
            viewModel.setExpectingFinalAnswerAfterToolCall(false);

            disconnectWebSocketGracefully();
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }

            connectWebSocket(new WebSocketConnectionCallback() {
                @Override
                public void onSuccess() {
                    Log.i(TAG, "New session started successfully.");
                    if (!settingsRepository.isUsingRealtimeAudioInput()) {
                        // Azure Speech Mode
                        if (!audioInputController.isSttRunning()) {
                            threadManager.executeAudio(() -> {
                                try {
                                    audioInputController.setupSpeechRecognizer();
                                    if (turnManager != null) {
                                        turnManager.setState(TurnManager.State.LISTENING);
                                    }
                                    audioInputController.startContinuousRecognition();
                                } catch (Exception e) {
                                    Log.e(TAG, "Failed to setup Azure Speech after session restart", e);
                                }
                            });
                        }
                    } else {
                        // Realtime API Mode
                        // Just set LISTENING state - ChatTurnListener will trigger audio start
                        if (turnManager != null) {
                            turnManager.setState(TurnManager.State.LISTENING);
                        }
                    }
                }

                @Override
                public void onError(Throwable error) {
                    if (error.getMessage() != null &&
                            error.getMessage().contains("WebSocket closed before session was updated")) {
                        Log.w(TAG, "Harmless race condition during new session start");
                    } else {
                        Log.e(TAG, "Failed to start new session", error);
                        viewModel.addMessage(
                                new ChatMessage(viewModel.getApplication().getString(R.string.new_session_error),
                                        ChatMessage.Sender.ROBOT));
                        viewModel.setStatusText(
                                viewModel.getApplication().getString(R.string.error_connection_failed_short));
                    }
                }
            });
        });
    }

    public void connectWebSocket(WebSocketConnectionCallback callback) {
        if (sessionManager.isConnected()) {
            if (callback != null)
                callback.onSuccess();
            return;
        }

        this.connectionCallback = callback;

        try {
            RealtimeApiProvider provider = settingsRepository.getApiProviderEnum();
            String selectedModel = settingsRepository.getModel();
            String azureEndpoint = keyManager.getAzureOpenAiEndpoint();

            String url = provider.getWebSocketUrl(azureEndpoint, selectedModel);

            java.util.HashMap<String, String> headers = new java.util.HashMap<>();
            if (provider.isAzureProvider()) {
                headers.put("api-key", keyManager.getAzureOpenAiKey());
            } else {
                headers.put("Authorization",
                        provider.getAuthorizationHeader(keyManager.getAzureOpenAiKey(), keyManager.getOpenAiApiKey()));
            }
            if (!"gpt-realtime".equals(selectedModel))
                headers.put("OpenAI-Beta", "realtime=v1");

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
            this.connectionCallback = null;

            boolean isGenerating = Boolean.TRUE.equals(viewModel.getIsResponseGenerating().getValue());
            boolean isPlaying = Boolean.TRUE.equals(viewModel.getIsAudioPlaying().getValue());
            String currentId = viewModel.getCurrentResponseId();

            if ((isGenerating || isPlaying) && currentId != null) {
                Log.d(TAG, "Cancelling active response before disconnect");
                viewModel.setCancelledResponseId(currentId);
                viewModel.setResponseGenerating(false);
                viewModel.setAudioPlaying(false);
                viewModel.setCurrentResponseId(null);
            }
            sessionManager.close(1000, "Provider switch");
        }
    }

    private void setupSessionManagerListeners() {
        sessionManager.setSessionConfigCallback((success, error) -> {
            if (success) {
                Log.i(TAG, "Session configured successfully - completing connection promise");
                completeConnectionPromise();
            } else {
                Log.e(TAG, "Session configuration failed: " + error);
                failConnectionPromise("Session config failed: " + error);
            }
        });
        sessionManager.setListener(new RealtimeSessionManager.Listener() {
            @Override
            public void onOpen(Response response) {
                Log.i(TAG, "WebSocket onOpen() - configuring initial session");
                sessionManager.configureInitialSession();
            }

            @Override
            public void onTextMessage(String text) {
                if (eventHandler != null)
                    eventHandler.handle(text);
            }

            @Override
            public void onBinaryMessage(okio.ByteString bytes) {
                // Handle audio input buffer if needed
            }

            @Override
            public void onClosing(int code, String reason) {
                Log.d(TAG, "WebSocket closing: " + code + " " + reason);
            }

            @Override
            public void onClosed(int code, String reason) {
                Log.d(TAG, "WebSocket closed: " + code + " " + reason);
                failConnectionPromise("Connection closed: " + reason);
            }

            @Override
            public void onFailure(Throwable t, Response response) {
                Log.e(TAG, "WebSocket Failure: " + t.getMessage());
                failConnectionPromise("Connection failed: " + t.getMessage());
            }
        });
    }

    private void setupAudioPlayerListener() {
        audioPlayer.setListener(new AudioPlayer.Listener() {
            @Override
            public void onPlaybackStarted() {
                if (turnManager != null)
                    turnManager.setState(TurnManager.State.SPEAKING);
            }

            @Override
            public void onPlaybackFinished() {
                viewModel.setAudioPlaying(false);
                if (turnManager != null) {
                    if (!viewModel.isExpectingFinalAnswerAfterToolCall()
                            && !Boolean.TRUE.equals(viewModel.getIsResponseGenerating().getValue())) {
                        turnManager.setState(TurnManager.State.LISTENING);
                    } else {
                        turnManager.setState(TurnManager.State.THINKING);
                    }
                }
            }
        });
    }

    public void failConnectionPromise(String message) {
        if (connectionCallback != null) {
            connectionCallback.onError(new Exception(message));
            connectionCallback = null;
        }
    }

    public void completeConnectionPromise() {
        if (connectionCallback != null) {
            connectionCallback.onSuccess();
            connectionCallback = null;
        }
    }
}
