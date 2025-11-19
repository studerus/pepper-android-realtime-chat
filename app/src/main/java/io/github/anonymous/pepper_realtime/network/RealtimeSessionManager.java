package io.github.anonymous.pepper_realtime.network;

import android.util.Log;

import androidx.annotation.NonNull;

import java.util.Map;
import java.util.Set;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import org.json.JSONArray;
import org.json.JSONObject;

import io.github.anonymous.pepper_realtime.network.RealtimeEventHandler;
import io.github.anonymous.pepper_realtime.manager.ApiKeyManager;
import io.github.anonymous.pepper_realtime.manager.SettingsManager;
import io.github.anonymous.pepper_realtime.tools.ToolRegistry;
import io.github.anonymous.pepper_realtime.tools.ToolContext;

public class RealtimeSessionManager {
    public interface Listener {
        void onOpen(Response response);
        void onTextMessage(String text);
        void onBinaryMessage(ByteString bytes);
        void onClosing(int code, String reason);
        void onClosed(int code, String reason);
        void onFailure(Throwable t, Response response);
    }
    
    public interface SessionConfigCallback {
        void onSessionConfigured(boolean success, String error);
    }

    private static final String TAG = "RealtimeSession";
    
    // Latency measurement for audio response time
    public static volatile long responseCreateTimestamp = 0;

    private final OkHttpClient client;
    private WebSocket webSocket;
    private Listener listener;
    private SessionConfigCallback sessionConfigCallback;
    
    // Session configuration dependencies
    private ToolRegistry toolRegistry;
    private ToolContext toolContext;
    private SettingsManager settingsManager;
    private ApiKeyManager keyManager;

    public RealtimeSessionManager() {
        // Use optimized shared WebSocket client for better performance
        this.client = HttpClientManager.getInstance().getWebSocketClient();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }
    
    public void setSessionConfigCallback(SessionConfigCallback callback) {
        this.sessionConfigCallback = callback;
    }
    
    /**
     * Set dependencies for session configuration
     */
    public void setSessionDependencies(ToolRegistry toolRegistry, ToolContext toolContext, 
                                      SettingsManager settingsManager, ApiKeyManager keyManager) {
        this.toolRegistry = toolRegistry;
        this.toolContext = toolContext;
        this.settingsManager = settingsManager;
        this.keyManager = keyManager;
    }

    public boolean isConnected() {
        // Check both WebSocket existence and actual connection state
        // Additional diagnostic info - but still rely on null check since we can't access internal state
        return webSocket != null;
    }

    public void connect(String url, Map<String, String> headers) {
        Request.Builder b = new Request.Builder().url(url);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) b.addHeader(e.getKey(), e.getValue());
        }
        Request request = b.build();
        WebSocketListener wsListener = new WebSocketListener() {
            @Override public void onOpen(@NonNull WebSocket ws, @NonNull Response response) {
                Log.i(TAG, "WebSocket onOpen: " + response.message() + " Code: " + response.code());
                webSocket = ws;
                if (listener != null) listener.onOpen(response);
                else Log.w(TAG, "WARNING: onOpen called but listener is null!");
            }
            @Override public void onMessage(@NonNull WebSocket ws, @NonNull String text) {
                if (listener != null) listener.onTextMessage(text);
            }
            @Override public void onMessage(@NonNull WebSocket ws, @NonNull ByteString bytes) {
                if (listener != null) listener.onBinaryMessage(bytes);
            }
            @Override public void onClosing(@NonNull WebSocket ws, int code, @NonNull String reason) {
                if (listener != null) listener.onClosing(code, reason);
                ws.close(1000, null);
            }
            @Override public void onClosed(@NonNull WebSocket ws, int code, @NonNull String reason) {
                if (listener != null) listener.onClosed(code, reason);
                if (webSocket == ws) webSocket = null;
            }
            @Override public void onFailure(@NonNull WebSocket ws, @NonNull Throwable t, Response response) {
                Log.e(TAG, "WebSocket failure: " + t.getMessage(), t);
                if (listener != null) listener.onFailure(t, response);
                else Log.w(TAG, "WARNING: onFailure called but listener is null!");
                if (webSocket == ws) webSocket = null;
            }
        };
        client.newWebSocket(request, wsListener);
    }

    public boolean sendUserTextMessage(String text) {
        try {
            JSONObject createItemPayload = new JSONObject();
            createItemPayload.put("type", "conversation.item.create");

            JSONObject item = new JSONObject();
            item.put("type", "message");
            item.put("role", "user");

            JSONArray contentArray = new JSONArray();
            JSONObject content = new JSONObject();
            content.put("type", "input_text");
            content.put("text", text);
            contentArray.put(content);

            item.put("content", contentArray);
            createItemPayload.put("item", item);

            Log.d(TAG, "Sending conversation.item.create: " + text);
            return send(createItemPayload.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error creating user text message", e);
            return false;
        }
    }

    public boolean requestResponse() {
        try {
            JSONObject createResponsePayload = new JSONObject();
            createResponsePayload.put("type", "response.create");
            
            // GA API doesn't support response.modalities - let it use session defaults
            // JSONObject responseDetails = new JSONObject();
            // responseDetails.put("modalities", new JSONArray().put("audio").put("text"));
            // createResponsePayload.put("response", responseDetails);

            // Reset latency measurement for new response and record timestamp
            RealtimeEventHandler.resetLatencyMeasurement();
            responseCreateTimestamp = System.currentTimeMillis();
            Log.d(TAG, "Sending response.create at " + responseCreateTimestamp);
            return send(createResponsePayload.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error creating response request", e);
            return false;
        }
    }

    /**
     * Send a user image message to the Realtime API (GA/Beta) as a conversation item
     * Uses input_image content with a data URL to avoid separate upload steps.
     *
     * @param base64 Base64-encoded JPEG/PNG without data URL prefix
     * @param mime   MIME type, e.g. "image/jpeg" (default recommended)
     */
    public boolean sendUserImageMessage(String base64, String mime) {
        try {
            String safeMime = (mime == null || mime.isEmpty()) ? "image/jpeg" : mime;

            JSONObject payload = new JSONObject();
            payload.put("type", "conversation.item.create");

            JSONObject item = new JSONObject();
            item.put("type", "message");
            item.put("role", "user");

            JSONArray contentArray = new JSONArray();
            JSONObject img = new JSONObject();
            img.put("type", "input_image");
            img.put("image_url", "data:" + safeMime + ";base64," + base64);
            contentArray.put(img);
            item.put("content", contentArray);

            payload.put("item", item);

            Log.d(TAG, "Sending conversation.item.create with image content");
            return send(payload.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error creating user image message", e);
            return false;
        }
    }

    public boolean sendToolResult(String callId, String result) {
        try {
            JSONObject toolResultPayload = new JSONObject();
            toolResultPayload.put("type", "conversation.item.create");
            
            JSONObject item = new JSONObject();
            item.put("type", "function_call_output");
            item.put("call_id", callId);
            item.put("output", result);
            
            toolResultPayload.put("item", item);
            
            Log.d(TAG, "Sending tool result: " + toolResultPayload);
            return send(toolResultPayload.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error creating tool result message", e);
            return false;
        }
    }
    
    /**
     * Send audio chunk to Realtime API input audio buffer
     * @param base64Audio Base64-encoded PCM16 audio data
     * @return true if sent successfully
     */
    public boolean sendAudioChunk(String base64Audio) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("type", "input_audio_buffer.append");
            payload.put("audio", base64Audio);
            
            return send(payload.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error creating audio chunk payload", e);
            return false;
        }
    }

    public boolean send(String text) {
        if (webSocket == null) {
            android.util.Log.w("RealtimeSessionManager", "🚨 DIAGNOSTIC: Cannot send - webSocket is null");
            return false;
        }
        
        try {
            boolean result = webSocket.send(text);
            if (!result) {
                android.util.Log.w("RealtimeSessionManager", "🚨 DIAGNOSTIC: WebSocket.send() returned false - connection may be broken");
            }
            return result;
        } catch (Exception e) {
            android.util.Log.e("RealtimeSessionManager", "🚨 DIAGNOSTIC: WebSocket.send() threw exception", e);
            return false;
        }
    }

    public void close(int code, String reason) {
        try { if (webSocket != null) webSocket.close(code, reason); } catch (Exception ignored) {}
        webSocket = null;
    }
    
    /**
     * Configure initial session with tools, voice, temperature, and instructions
     * This should be called when WebSocket connection is established
     */
    public void configureInitialSession() {
        if (!isConnected()) {
            Log.w(TAG, "Session config SKIPPED - not connected");
            if (sessionConfigCallback != null) {
                sessionConfigCallback.onSessionConfigured(false, "Not connected");
            }
            return;
        }
        
        if (settingsManager == null || toolRegistry == null || toolContext == null) {
            Log.w(TAG, "Session config SKIPPED - missing dependencies");
            if (sessionConfigCallback != null) {
                sessionConfigCallback.onSessionConfigured(false, "Missing dependencies");
            }
            return;
        }
        
        try {
            JSONObject payload = createSessionUpdatePayload("Initial session");
            boolean sent = send(payload.toString());
            
            if (sent) {
                if (sessionConfigCallback != null) {
                    sessionConfigCallback.onSessionConfigured(true, null);
                }
            } else {
                String error = "Failed to send initial session config";
                Log.e(TAG, error);
                if (sessionConfigCallback != null) {
                    sessionConfigCallback.onSessionConfigured(false, error);
                }
            }
            
        } catch (Exception e) {
            String error = "Error creating initial session config: " + e.getMessage();
            Log.e(TAG, error, e);
            if (sessionConfigCallback != null) {
                sessionConfigCallback.onSessionConfigured(false, error);
            }
        }
    }
    
    /**
     * Update session configuration with current settings
     * This should be called when settings change during an active session
     */
    public void updateSession() {
        if (!isConnected()) {
            Log.w(TAG, "Session update SKIPPED - not connected");
            return;
        }
        
        if (settingsManager == null || toolRegistry == null || toolContext == null) {
            Log.w(TAG, "Session update SKIPPED - missing dependencies");
            return;
        }
        
        try {
            // Debug YouTube API key availability
            Set<String> enabledTools = settingsManager.getEnabledTools();
            if (keyManager != null && enabledTools.contains("play_youtube_video")) {
                boolean hasYouTubeKey = keyManager.isYouTubeAvailable();
                if (!hasYouTubeKey) {
                    Log.w(TAG, "YouTube tool enabled but no API key found!");
                }
            }
            
            JSONObject payload = createSessionUpdatePayload("Session update");
            boolean sent = send(payload.toString());
            
            if (!sent) {
                Log.e(TAG, "Failed to send session update");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error updating session config", e);
        }
    }

    /**
     * Creates the session.update payload based on current settings.
     * Eliminates duplication between initial config and updates.
     */
    private JSONObject createSessionUpdatePayload(String contextLog) throws Exception {
        String voice = settingsManager.getVoice();
        float speed = settingsManager.getSpeed();
        String model = settingsManager.getModel();
        float temperature = settingsManager.getTemperature();
        String systemPrompt = settingsManager.getSystemPrompt();
        Set<String> enabledTools = settingsManager.getEnabledTools();
        
        Log.i(TAG, contextLog + " - Model: " + model + ", Enabled tools: " + enabledTools);
        
        JSONObject payload = new JSONObject();
        payload.put("type", "session.update");
        
        JSONObject sessionConfig = new JSONObject();
        
        // For OpenAI Direct with gpt-realtime, use GA API structure
        if ("gpt-realtime".equals(model) && settingsManager.getApiProvider() == RealtimeApiProvider.OPENAI_DIRECT) {
            sessionConfig.put("type", "realtime");
            
            // GA API uses structured audio configuration
            JSONObject audio = new JSONObject();
            
            // Output configuration
            JSONObject output = new JSONObject();
            output.put("voice", voice);
            output.put("speed", speed);
            JSONObject format = new JSONObject();
            format.put("type", "audio/pcm");
            format.put("rate", 24000);
            output.put("format", format);
            audio.put("output", output);
            
            // Input configuration with turn detection
            JSONObject input = new JSONObject();
            
            // Enable input audio configuration if using Realtime API audio mode
            if (settingsManager.isUsingRealtimeAudioInput()) {
                // Turn Detection configuration
                JSONObject turnDetection = new JSONObject();
                String turnDetType = settingsManager.getTurnDetectionType();
                turnDetection.put("type", turnDetType);
                turnDetection.put("create_response", true);
                turnDetection.put("interrupt_response", true);
                
                if ("server_vad".equals(turnDetType)) {
                    turnDetection.put("threshold", settingsManager.getVadThreshold());
                    turnDetection.put("prefix_padding_ms", settingsManager.getPrefixPadding());
                    turnDetection.put("silence_duration_ms", settingsManager.getSilenceDuration());
                    
                    // Idle timeout only supported for server_vad
                    Integer idleTimeout = settingsManager.getIdleTimeout();
                    if (idleTimeout != null && idleTimeout > 0) {
                        turnDetection.put("idle_timeout_ms", idleTimeout);
                    }
                } else if ("semantic_vad".equals(turnDetType)) {
                    String eagerness = settingsManager.getEagerness();
                    turnDetection.put("eagerness", eagerness);
                }
                
                input.put("turn_detection", turnDetection);
                
                // Transcription configuration
                JSONObject transcription = new JSONObject();
                transcription.put("model", settingsManager.getTranscriptionModel());
                String transcriptLang = settingsManager.getTranscriptionLanguage();
                if (transcriptLang != null && !transcriptLang.isEmpty()) {
                    transcription.put("language", transcriptLang);
                }
                String transcriptPrompt = settingsManager.getTranscriptionPrompt();
                if (transcriptPrompt != null && !transcriptPrompt.isEmpty()) {
                    transcription.put("prompt", transcriptPrompt);
                }
                input.put("transcription", transcription);
                
                // Noise Reduction configuration
                String noiseReduction = settingsManager.getNoiseReduction();
                if (!"off".equals(noiseReduction)) {
                    JSONObject noiseReductionObj = new JSONObject();
                    noiseReductionObj.put("type", noiseReduction);
                    input.put("noise_reduction", noiseReductionObj);
                } else {
                    input.put("noise_reduction", JSONObject.NULL);
                }
                
                // Input format
                JSONObject inputFormat = new JSONObject();
                inputFormat.put("type", "audio/pcm");
                inputFormat.put("rate", 24000);
                input.put("format", inputFormat);
                
                Log.i(TAG, "Input audio enabled - Turn: " + turnDetType + ", Transcription: " + settingsManager.getTranscriptionModel());
            } else {
                // Azure Speech mode - disable turn detection
                input.put("turn_detection", JSONObject.NULL);
            }
            
            audio.put("input", input);
            
            sessionConfig.put("audio", audio);
            // Note: temperature not supported in GA API
        } else {
            // Preview/Mini models (Beta API) - use legacy parameters with server VAD
            sessionConfig.put("voice", voice);
            sessionConfig.put("speed", speed);
            sessionConfig.put("temperature", temperature);
            sessionConfig.put("output_audio_format", "pcm16");
            
            // Configure audio input for Realtime API audio mode
            if (settingsManager.isUsingRealtimeAudioInput()) {
                // Turn Detection configuration
                JSONObject turnDetection = new JSONObject();
                String turnDetType = settingsManager.getTurnDetectionType();
                turnDetection.put("type", turnDetType);
                turnDetection.put("create_response", true);
                turnDetection.put("interrupt_response", true);
                
                if ("server_vad".equals(turnDetType)) {
                    turnDetection.put("threshold", settingsManager.getVadThreshold());
                    turnDetection.put("prefix_padding_ms", settingsManager.getPrefixPadding());
                    turnDetection.put("silence_duration_ms", settingsManager.getSilenceDuration());
                }
                
                Integer idleTimeout = settingsManager.getIdleTimeout();
                if (idleTimeout != null && idleTimeout > 0) {
                    turnDetection.put("idle_timeout_ms", idleTimeout);
                }
                
                sessionConfig.put("turn_detection", turnDetection);
                
                // Configure input audio format
                sessionConfig.put("input_audio_format", "pcm16");
                
                // Enable input audio transcription
                JSONObject inputTranscription = new JSONObject();
                inputTranscription.put("model", settingsManager.getTranscriptionModel());
                String transcriptLang = settingsManager.getTranscriptionLanguage();
                if (transcriptLang != null && !transcriptLang.isEmpty()) {
                    inputTranscription.put("language", transcriptLang);
                }
                String transcriptPrompt = settingsManager.getTranscriptionPrompt();
                if (transcriptPrompt != null && !transcriptPrompt.isEmpty()) {
                    inputTranscription.put("prompt", transcriptPrompt);
                }
                sessionConfig.put("input_audio_transcription", inputTranscription);
                
                Log.i(TAG, "Beta API: Turn detection=" + turnDetType + ", Transcription=" + settingsManager.getTranscriptionModel());
            } else {
                // Azure Speech mode - disable server VAD
                sessionConfig.put("turn_detection", JSONObject.NULL);
            }
        }
        sessionConfig.put("instructions", systemPrompt);
        
        JSONArray toolsArray = toolRegistry.buildToolsDefinitionForAzure(toolContext, enabledTools);
        sessionConfig.put("tools", toolsArray);
        
        payload.put("session", sessionConfig);
        
        Log.d(TAG, contextLog + " payload built with " + toolsArray.length() + " tools");
        logToolsDebug(toolsArray, contextLog);
        
        return payload;
    }
    
    /**
     * Log tools array for debugging
     */
    private void logToolsDebug(JSONArray toolsArray, String context) {
        try {
            Log.d(TAG, context + " tools: " + toolsArray.length() + " tools");
            // Reduced verbosity - only log first 3 tools
            int logLimit = Math.min(3, toolsArray.length());
            for (int i = 0; i < logLimit; i++) {
                JSONObject tool = toolsArray.getJSONObject(i);
                Log.d(TAG, "  " + context + " tool " + i + ": " + tool.optString("name"));
            }
            if (toolsArray.length() > logLimit) {
                Log.d(TAG, "  ... and " + (toolsArray.length() - logLimit) + " more tools");
            }
        } catch (Exception e) {
            Log.w(TAG, "Error logging tools debug info", e);
        }
    }
}
