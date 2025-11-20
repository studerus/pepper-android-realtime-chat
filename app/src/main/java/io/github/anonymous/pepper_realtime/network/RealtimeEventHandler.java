package io.github.anonymous.pepper_realtime.network;

import android.util.Log;

import org.json.JSONObject;

import io.github.anonymous.pepper_realtime.network.RealtimeSessionManager;

public class RealtimeEventHandler {
    private static final String TAG = "RealtimeEvents";

    // Track if we've already measured latency for this response
    private static volatile boolean latencyMeasured = false;

    /**
     * Measure and log latency from response.create to first audio chunk
     */
    private static void measureAudioLatency() {
        if (!latencyMeasured && RealtimeSessionManager.responseCreateTimestamp > 0) {
            long now = System.currentTimeMillis();
            long latency = now - RealtimeSessionManager.responseCreateTimestamp;
            Log.i(TAG, "🎵 AUDIO LATENCY: " + latency + "ms (response.create → first audio chunk)");
            latencyMeasured = true;
        }
    }

    /**
     * Reset latency measurement for new response
     */
    public static void resetLatencyMeasurement() {
        latencyMeasured = false;
    }

    public interface Listener {
        void onSessionUpdated(JSONObject session);

        void onAudioTranscriptDelta(String delta, @SuppressWarnings("unused") String responseId);

        void onAudioDelta(byte[] pcm16, String responseId);

        default void onResponseBoundary(@SuppressWarnings("unused") String newResponseId) {
        }

        void onAudioDone();

        void onResponseDone(JSONObject response);

        void onAssistantItemAdded(String itemId);

        // Optional hooks for response IDs if needed later
        default void onResponseCreated(@SuppressWarnings("unused") String responseId) {
        }

        void onError(JSONObject error);

        void onUnknown(String type, JSONObject raw);

        // User audio input events (Realtime API audio mode)
        default void onUserSpeechStarted(String itemId) {
        }

        default void onUserSpeechStopped(String itemId) {
        }

        default void onUserItemCreated(String itemId, JSONObject item) {
        }

        default void onUserTranscriptCompleted(String itemId, String transcript) {
        }

        default void onUserTranscriptFailed(String itemId, JSONObject error) {
        }
    }

    private final Listener listener;

    public RealtimeEventHandler(Listener listener) {
        this.listener = listener;
    }

    public void handle(String text) {
        try {
            JSONObject obj = new JSONObject(text);
            String type = obj.optString("type", "");

            // Skip logging for high-frequency delta events to avoid log spam
            if (!type.endsWith(".delta")) {
                Log.d(TAG, "Received event type: " + type);
            }

            switch (type) {
                case "session.created":
                    JSONObject session = obj.optJSONObject("session");
                    if (session != null) {
                        String model = session.optString("model", "unknown");
                        String sessionId = session.optString("id", "unknown");
                        String voice = "unknown";
                        // Try both possible voice locations based on your examples
                        JSONObject audio = session.optJSONObject("audio");
                        if (audio != null) {
                            JSONObject output = audio.optJSONObject("output");
                            if (output != null) {
                                voice = output.optString("voice", "unknown");
                            }
                        }
                        // Fallback: try direct voice property
                        if ("unknown".equals(voice)) {
                            voice = session.optString("voice", "unknown");
                        }
                        Log.i(TAG, "Session created - Model: " + model + ", Voice: " + voice + ", ID: " + sessionId);
                    }
                    break;
                case "session.updated":
                    JSONObject sessionUpdated = obj.optJSONObject("session");
                    if (sessionUpdated != null) {
                        String model = sessionUpdated.optString("model", "unknown");
                        String sessionId = sessionUpdated.optString("id", "unknown");
                        String voice = sessionUpdated.optString("voice", "unknown");
                        String instructions = sessionUpdated.optString("instructions", "");
                        double temperature = sessionUpdated.optDouble("temperature", -1);
                        String outputAudioFormat = sessionUpdated.optString("output_audio_format", "unknown");

                        // Count tools
                        int toolCount = 0;
                        try {
                            org.json.JSONArray tools = sessionUpdated.optJSONArray("tools");
                            if (tools != null) {
                                toolCount = tools.length();
                            }
                        } catch (Exception ignored) {
                        }

                        Log.i(TAG, "Session updated - Model: " + model + ", Voice: " + voice + ", Tools: " + toolCount
                                + ", ID: " + sessionId);
                        Log.i(TAG, "  Temperature: " + (temperature >= 0 ? temperature : "not set") +
                                ", Audio Format: " + outputAudioFormat);
                        Log.i(TAG,
                                "  Instructions: "
                                        + (instructions.length() > 100 ? instructions.substring(0, 100) + "..."
                                                : instructions));
                    }
                    if (listener != null)
                        listener.onSessionUpdated(sessionUpdated);
                    break;
                case "response.audio_transcript.delta":
                    if (listener != null)
                        listener.onAudioTranscriptDelta(obj.optString("delta", ""), obj.optString("response_id", ""));
                    break;
                case "conversation.item.added":
                    // GA API equivalent of conversation.item.created
                    Log.d(TAG, "Conversation item added (GA API)");
                    break;
                case "conversation.item.done":
                    // GA API new event for item completion
                    Log.d(TAG, "Conversation item done (GA API)");
                    break;
                case "response.audio.delta":
                    measureAudioLatency(); // Measure latency on first audio chunk
                    try {
                        String b64 = obj.optString("delta", "");
                        String rid = obj.optString("response_id", "");
                        byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                        if (listener != null)
                            listener.onAudioDelta(bytes, rid);
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
                    } catch (Exception ignored) {
                    }
                    break;
                case "response.audio.done":
                case "response.output_audio.done":
                    // Both Beta API and GA API audio done events - same handling
                    if (listener != null)
                        listener.onAudioDone();
                    break;
                case "response.done":
                    if (listener != null)
                        listener.onResponseDone(obj.optJSONObject("response"));
                    break;
                case "response.output_item.added":
                    try {
                        JSONObject item = obj.optJSONObject("item");
                        if (item != null) {
                            String id = item.optString("id", "");
                            String typeItem = item.optString("type", "");
                            String role = item.optString("role", "");
                            if (!id.isEmpty() && "message".equals(typeItem) && "assistant".equals(role)) {
                                if (listener != null)
                                    listener.onAssistantItemAdded(id);
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to parse output_item.added", e);
                    }
                    break;
                case "conversation.item.created":
                    // Item was added to conversation
                    JSONObject createdItem = obj.optJSONObject("item");
                    if (createdItem != null && "user".equals(createdItem.optString("role"))) {
                        // User audio input item created
                        String userItemId = createdItem.optString("id", "");
                        Log.i(TAG, "User conversation item created: " + userItemId);
                        if (listener != null)
                            listener.onUserItemCreated(userItemId, createdItem);
                    } else {
                        Log.d(TAG, "Conversation item created");
                    }
                    break;
                case "conversation.item.truncated":
                    // Item was truncated (e.g., after interrupt) - log at debug level
                    Log.d(TAG, "Conversation item truncated");
                    break;
                case "response.content_part.added":
                    // Content part added to response - log at debug level
                    Log.d(TAG, "Response content part added");
                    break;
                case "response.content_part.done":
                    // Content part completed - log at debug level
                    Log.d(TAG, "Response content part done");
                    break;
                case "response.function_call_arguments.delta":
                    // Function call arguments streaming - too verbose, skip logging
                    break;
                case "response.function_call_arguments.done":
                    // Function call arguments complete - log at debug level
                    Log.d(TAG, "Function call arguments done");
                    break;
                case "response.audio_transcript.done":
                    // Audio transcript completed - log the transcript content
                    String transcript = obj.optString("transcript", "");
                    if (!transcript.isEmpty()) {
                        Log.d(TAG, "Audio transcript: \"" + transcript + "\"");
                    } else {
                        Log.d(TAG, "Audio transcript done (empty)");
                    }
                    break;
                // GA API audio events (new event names)
                case "response.output_audio.delta":
                    // GA API audio chunk - same handling as Beta API
                    measureAudioLatency(); // Measure latency on first audio chunk
                    try {
                        String b64 = obj.optString("delta", "");
                        String rid = obj.optString("response_id", "");
                        byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                        if (listener != null)
                            listener.onAudioDelta(bytes, rid);
                    } catch (Exception e) {
                        Log.e(TAG, "GA audio.delta decode failed", e);
                    }
                    break;
                case "response.output_audio_transcript.delta":
                    // GA API transcript delta - same handling as Beta API
                    String gaDelta = obj.optString("delta", "");
                    String gaResponseId = obj.optString("response_id", "");
                    if (listener != null)
                        listener.onAudioTranscriptDelta(gaDelta, gaResponseId);
                    break;
                case "response.output_audio_transcript.done":
                    // GA API transcript done - log the transcript content
                    String gaTranscript = obj.optString("transcript", "");
                    if (!gaTranscript.isEmpty()) {
                        Log.d(TAG, "GA Audio transcript: \"" + gaTranscript + "\"");
                    } else {
                        Log.d(TAG, "GA Audio transcript done (empty)");
                    }
                    break;
                case "response.output_item.done":
                    // Output item completed - log at debug level
                    Log.d(TAG, "Output item done");
                    break;
                case "rate_limits.updated":
                    // Rate limit info updated - log at debug level only
                    Log.d(TAG, "Rate limits updated");
                    break;

                // User audio input events (Realtime API audio mode)
                case "input_audio_buffer.speech_started":
                    String startItemId = obj.optString("item_id", "");
                    Log.i(TAG, "User speech started (item: " + startItemId + ")");
                    if (listener != null)
                        listener.onUserSpeechStarted(startItemId);
                    break;

                case "input_audio_buffer.speech_stopped":
                    String stopItemId = obj.optString("item_id", "");
                    Log.i(TAG, "User speech stopped (item: " + stopItemId + ")");
                    if (listener != null)
                        listener.onUserSpeechStopped(stopItemId);
                    break;

                case "conversation.item.input_audio_transcription.completed":
                    String transcriptItemId = obj.optString("item_id", "");
                    String userTranscript = obj.optString("transcript", "");
                    Log.i(TAG, "User transcript completed (item: " + transcriptItemId + "): " + userTranscript);
                    if (listener != null)
                        listener.onUserTranscriptCompleted(transcriptItemId, userTranscript);
                    break;

                case "conversation.item.input_audio_transcription.failed":
                    String failedItemId = obj.optString("item_id", "");
                    JSONObject transcriptError = obj.optJSONObject("error");
                    Log.w(TAG, "User transcript failed (item: " + failedItemId + "): " + transcriptError);
                    if (listener != null)
                        listener.onUserTranscriptFailed(failedItemId, transcriptError);
                    break;

                case "error":
                    if (listener != null)
                        listener.onError(obj.optJSONObject("error"));
                    break;
                default:
                    if (listener != null)
                        listener.onUnknown(type, obj);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse event: " + text, e);
            if (listener != null)
                listener.onUnknown("parse_error", null);
        }
    }
}
