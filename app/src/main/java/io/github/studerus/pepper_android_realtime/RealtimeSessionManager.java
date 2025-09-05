package io.github.studerus.pepper_android_realtime;

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

import io.github.studerus.pepper_android_realtime.tools.ToolRegistryNew;
import io.github.studerus.pepper_android_realtime.tools.ToolContext;

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

    private final OkHttpClient client;
    private WebSocket webSocket;
    private Listener listener;
    private SessionConfigCallback sessionConfigCallback;
    
    // Session configuration dependencies
    private ToolRegistryNew toolRegistry;
    private ToolContext toolContext;
    private SettingsManager settingsManager;
    private ApiKeyManager keyManager;

    public RealtimeSessionManager() {
        // Use optimized shared WebSocket client for better performance
        this.client = OptimizedHttpClientManager.getInstance().getWebSocketClient();
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
    public void setSessionDependencies(ToolRegistryNew toolRegistry, ToolContext toolContext, 
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
                webSocket = ws;
                if (listener != null) listener.onOpen(response);
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
            
            JSONObject responseDetails = new JSONObject();
            responseDetails.put("modalities", new JSONArray().put("audio").put("text"));
            createResponsePayload.put("response", responseDetails);

            Log.d(TAG, "Sending response.create");
            return send(createResponsePayload.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error creating response request", e);
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
            String voice = settingsManager.getVoice();
            float temperature = settingsManager.getTemperature();
            String systemPrompt = settingsManager.getSystemPrompt();
            Set<String> enabledTools = settingsManager.getEnabledTools();
            
            Log.i(TAG, "Initial session configuration - Enabled tools: " + enabledTools);
            
            JSONObject payload = new JSONObject();
            payload.put("type", "session.update");
            
            JSONObject sessionConfig = new JSONObject();
            sessionConfig.put("voice", voice);
            sessionConfig.put("temperature", temperature);
            sessionConfig.put("output_audio_format", "pcm16");
            sessionConfig.put("turn_detection", JSONObject.NULL);
            sessionConfig.put("instructions", systemPrompt);
            
            JSONArray toolsArray = toolRegistry.buildToolsDefinitionForAzure(toolContext, enabledTools);
            sessionConfig.put("tools", toolsArray);
            
            payload.put("session", sessionConfig);
            
            boolean sent = send(payload.toString());
            if (sent) {
                Log.d(TAG, "Sent initial session.update with " + toolsArray.length() + " tools");
                logToolsDebug(toolsArray, "Initial session");
                
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
            String voice = settingsManager.getVoice();
            float temperature = settingsManager.getTemperature();
            String systemPrompt = settingsManager.getSystemPrompt();
            Set<String> enabledTools = settingsManager.getEnabledTools();
            
            Log.i(TAG, "Session update - Enabled tools: " + enabledTools);
            
            // Debug YouTube API key availability
            if (keyManager != null && enabledTools.contains("play_youtube_video")) {
                boolean hasYouTubeKey = keyManager.isYouTubeAvailable();
                Log.i(TAG, "YouTube tool enabled - API key available: " + hasYouTubeKey);
                if (!hasYouTubeKey) {
                    Log.w(TAG, "YouTube tool enabled but no API key found!");
                }
            }
            
            JSONObject payload = new JSONObject();
            payload.put("type", "session.update");
            
            JSONObject sessionConfig = new JSONObject();
            sessionConfig.put("voice", voice);
            sessionConfig.put("temperature", temperature);
            sessionConfig.put("instructions", systemPrompt);
            
            JSONArray toolsArray = toolRegistry.buildToolsDefinitionForAzure(toolContext, enabledTools);
            sessionConfig.put("tools", toolsArray);
            
            payload.put("session", sessionConfig);
            
            boolean sent = send(payload.toString());
            if (sent) {
                Log.d(TAG, "Sent session.update with " + toolsArray.length() + " tools");
                logToolsDebug(toolsArray, "Session update");
            } else {
                Log.e(TAG, "Failed to send session update");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error updating session config", e);
        }
    }
    
    /**
     * Log tools array for debugging
     */
    private void logToolsDebug(JSONArray toolsArray, String context) {
        try {
            Log.i(TAG, context + " tools: " + toolsArray.length() + " tools");
            for (int i = 0; i < toolsArray.length(); i++) {
                JSONObject tool = toolsArray.getJSONObject(i);
                Log.d(TAG, "  " + context + " tool " + i + ": " + tool.optString("name"));
            }
        } catch (Exception e) {
            Log.w(TAG, "Error logging tools debug info", e);
        }
    }
}
