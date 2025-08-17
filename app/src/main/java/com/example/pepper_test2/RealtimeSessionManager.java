package com.example.pepper_test2;

import android.util.Log;

import androidx.annotation.NonNull;

import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class RealtimeSessionManager {
    public interface Listener {
        void onOpen(Response response);
        void onTextMessage(String text);
        void onBinaryMessage(ByteString bytes);
        void onClosing(int code, String reason);
        void onClosed(int code, String reason);
        void onFailure(Throwable t, Response response);
    }

    private static final String TAG = "RealtimeSession";

    private final OkHttpClient client;
    private WebSocket webSocket;
    private Listener listener;

    public RealtimeSessionManager() {
        this.client = new OkHttpClient.Builder().retryOnConnectionFailure(true).build();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isConnected() {
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

    public void send(String text) {
        if (webSocket != null) webSocket.send(text);
    }

    public void close(int code, String reason) {
        try { if (webSocket != null) webSocket.close(code, reason); } catch (Exception ignored) {}
        webSocket = null;
    }
}
