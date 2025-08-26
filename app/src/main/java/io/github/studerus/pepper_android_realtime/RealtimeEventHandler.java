package io.github.studerus.pepper_android_realtime;

import android.util.Log;

import org.json.JSONObject;

public class RealtimeEventHandler {
    public interface Listener {
        void onSessionUpdated(JSONObject session);
        void onAudioTranscriptDelta(String delta, @SuppressWarnings("unused") String responseId);
        void onAudioDelta(byte[] pcm16, String responseId);
        default void onResponseBoundary(@SuppressWarnings("unused") String newResponseId) {}
        void onAudioDone();
        void onResponseDone(JSONObject response);
        void onAssistantItemAdded(String itemId);
        // Optional hooks for response IDs if needed later
        default void onResponseCreated(@SuppressWarnings("unused") String responseId) {}
        void onError(JSONObject error);
        void onUnknown(String type, JSONObject raw);
    }

    private static final String TAG = "RealtimeEvents";
    private final Listener listener;

    public RealtimeEventHandler(Listener listener) {
        this.listener = listener;
    }

    public void handle(String text) {
        try {
            JSONObject obj = new JSONObject(text);
            String type = obj.optString("type", "");
            switch (type) {
                case "session.updated":
                    if (listener != null) listener.onSessionUpdated(obj.optJSONObject("session"));
                    break;
                case "response.audio_transcript.delta":
                    if (listener != null) listener.onAudioTranscriptDelta(obj.optString("delta", ""), obj.optString("response_id", ""));
                    break;
                case "response.audio.delta":
                    try {
                        String b64 = obj.optString("delta", "");
                        String rid = obj.optString("response_id", "");
                        byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                        if (listener != null) listener.onAudioDelta(bytes, rid);
                    } catch (Exception e) {
                        Log.e(TAG, "audio.delta decode failed", e);
                    }
                    break;
                case "response.created":
                    try {
                        JSONObject resp = obj.optJSONObject("response");
                        if (resp != null) {
                            String rid = resp.optString("id", "");
                            if (!rid.isEmpty()) {
                                listener.onResponseCreated(rid);
                                // Signal boundary early so clients can reset state
                                listener.onResponseBoundary(rid);
                            }
                        }
                    } catch (Exception ignored) {}
                    break;
                case "response.audio.done":
                    if (listener != null) listener.onAudioDone();
                    break;
                case "response.done":
                    if (listener != null) listener.onResponseDone(obj.optJSONObject("response"));
                    break;
                case "response.output_item.added":
                    try {
                        JSONObject item = obj.optJSONObject("item");
                        if (item != null) {
                            String id = item.optString("id", "");
                            String typeItem = item.optString("type", "");
                            String role = item.optString("role", "");
                            if (!id.isEmpty() && "message".equals(typeItem) && "assistant".equals(role)) {
                                if (listener != null) listener.onAssistantItemAdded(id);
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to parse output_item.added", e);
                    }
                    break;
                case "error":
                    if (listener != null) listener.onError(obj.optJSONObject("error"));
                    break;
                default:
                    if (listener != null) listener.onUnknown(type, obj);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse event: " + text, e);
            if (listener != null) listener.onUnknown("parse_error", null);
        }
    }
}
