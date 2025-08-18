package com.example.pepper_test2;

import android.util.Log;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

public class WebSocketMessageHandler {

    private static final String TAG = "WebSocketMessageHandler";

    public interface MessageHandler {
        void handle(JSONObject message) throws Exception;
    }

    private final Map<String, MessageHandler> handlers = new HashMap<>();

    public void registerHandler(String type, MessageHandler handler) {
        handlers.put(type, handler);
    }

    public void handleMessage(String text) {
        try {
            JSONObject message = new JSONObject(text);
            String type = message.getString("type");
            MessageHandler handler = handlers.get(type);
            if (handler != null) {
                handler.handle(message);
            } else {
                Log.d(TAG, "Received unhandled message type: " + type);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing WebSocket message: " + text, e);
        }
    }
}
