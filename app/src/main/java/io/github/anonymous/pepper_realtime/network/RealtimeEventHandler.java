package io.github.anonymous.pepper_realtime.network;

import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

public class RealtimeEventHandler {
    private static final String TAG = "RealtimeEvents";

    // Track if we've already measured latency for this response
    private static volatile boolean latencyMeasured = false;

    private final Gson gson;

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
        void onSessionUpdated(RealtimeEvents.SessionUpdated event);

        void onAudioTranscriptDelta(String delta, String responseId);

        void onAudioDelta(byte[] pcm16, String responseId);

        default void onResponseBoundary(String newResponseId) {
        }

        void onAudioDone();

        void onResponseDone(RealtimeEvents.ResponseDone event);

        void onAssistantItemAdded(String itemId);

        // Optional hooks for response IDs if needed later
        default void onResponseCreated(String responseId) {
        }

        void onError(RealtimeEvents.ErrorEvent error);

        void onUnknown(String type, JsonObject raw);

        // User audio input events (Realtime API audio mode)
        default void onUserSpeechStarted(String itemId) {
        }

        default void onUserSpeechStopped(String itemId) {
        }

        default void onAudioBufferCommitted(String itemId) {
        }

        default void onUserItemCreated(String itemId, RealtimeEvents.ConversationItemCreated event) {
        }

        default void onUserTranscriptCompleted(String itemId, String transcript) {
        }

        default void onUserTranscriptFailed(String itemId, RealtimeEvents.UserTranscriptFailed event) {
        }

        default void onAudioTranscriptDone(String transcript, String responseId) {
        }
    }

    private final Listener listener;

    public RealtimeEventHandler(Listener listener) {
        this.listener = listener;
        this.gson = new Gson();
    }

    public Listener getListener() {
        return listener;
    }

    public void handle(String text) {
        try {
            JsonObject jsonObject = JsonParser.parseString(text).getAsJsonObject();
            String type = jsonObject.has("type") ? jsonObject.get("type").getAsString() : "";

            // Skip logging for high-frequency delta events to avoid log spam
            if (!type.endsWith(".delta")) {
                Log.d(TAG, "Received event type: " + type);
            }

            switch (type) {
                case "session.created":
                    RealtimeEvents.SessionCreated sessionCreated = gson.fromJson(jsonObject,
                            RealtimeEvents.SessionCreated.class);
                    if (sessionCreated.session != null) {
                        Log.i(TAG, "Session created - Model: " + sessionCreated.session.model +
                                ", ID: " + sessionCreated.session.id);
                    }
                    break;
                case "session.updated":
                    RealtimeEvents.SessionUpdated sessionUpdated = gson.fromJson(jsonObject,
                            RealtimeEvents.SessionUpdated.class);
                    if (sessionUpdated.session != null) {
                        Log.i(TAG, "Session updated - Model: " + sessionUpdated.session.model +
                                ", ID: " + sessionUpdated.session.id);
                    }
                    if (listener != null)
                        listener.onSessionUpdated(sessionUpdated);
                    break;
                case "response.audio_transcript.delta":
                    RealtimeEvents.AudioTranscriptDelta transcriptDelta = gson.fromJson(jsonObject,
                            RealtimeEvents.AudioTranscriptDelta.class);
                    if (listener != null)
                        listener.onAudioTranscriptDelta(transcriptDelta.delta, transcriptDelta.responseId);
                    break;
                case "conversation.item.added":
                    Log.d(TAG, "Conversation item added (GA API)");
                    break;
                case "conversation.item.done":
                    Log.d(TAG, "Conversation item done (GA API)");
                    break;
                case "response.audio.delta":
                    measureAudioLatency(); // Measure latency on first audio chunk
                    try {
                        RealtimeEvents.AudioDelta audioDelta = gson.fromJson(jsonObject,
                                RealtimeEvents.AudioDelta.class);
                        byte[] bytes = android.util.Base64.decode(audioDelta.delta, android.util.Base64.DEFAULT);
                        if (listener != null)
                            listener.onAudioDelta(bytes, audioDelta.responseId);
                    } catch (Exception e) {
                        Log.e(TAG, "audio.delta decode failed", e);
                    }
                    break;
                case "response.created":
                    RealtimeEvents.ResponseCreated responseCreated = gson.fromJson(jsonObject,
                            RealtimeEvents.ResponseCreated.class);
                    if (responseCreated.response != null && responseCreated.response.id != null) {
                        if (listener != null) {
                            listener.onResponseCreated(responseCreated.response.id);
                            listener.onResponseBoundary(responseCreated.response.id);
                        }
                    }
                    break;
                case "response.audio.done":
                case "response.output_audio.done":
                    if (listener != null)
                        listener.onAudioDone();
                    break;
                case "response.done":
                    RealtimeEvents.ResponseDone responseDone = gson.fromJson(jsonObject,
                            RealtimeEvents.ResponseDone.class);
                    if (listener != null)
                        listener.onResponseDone(responseDone);
                    break;
                case "response.output_item.added":
                    RealtimeEvents.ResponseOutputItemAdded outputItemAdded = gson.fromJson(jsonObject,
                            RealtimeEvents.ResponseOutputItemAdded.class);
                    if (outputItemAdded.item != null && "message".equals(outputItemAdded.item.type)
                            && "assistant".equals(outputItemAdded.item.role)) {
                        if (listener != null)
                            listener.onAssistantItemAdded(outputItemAdded.item.id);
                    }
                    break;
                case "conversation.item.created":
                    RealtimeEvents.ConversationItemCreated itemCreated = gson.fromJson(jsonObject,
                            RealtimeEvents.ConversationItemCreated.class);
                    if (itemCreated.item != null && "user".equals(itemCreated.item.role)) {
                        Log.i(TAG, "User conversation item created: " + itemCreated.item.id);
                        if (listener != null)
                            listener.onUserItemCreated(itemCreated.item.id, itemCreated);
                    } else {
                        Log.d(TAG, "Conversation item created");
                    }
                    break;
                case "conversation.item.truncated":
                    Log.d(TAG, "Conversation item truncated");
                    break;
                case "response.content_part.added":
                    Log.d(TAG, "Response content part added");
                    break;
                case "response.content_part.done":
                    Log.d(TAG, "Response content part done");
                    break;
                case "response.function_call_arguments.delta":
                    break;
                case "response.function_call_arguments.done":
                    Log.d(TAG, "Function call arguments done");
                    break;
                case "response.audio_transcript.done":
                    RealtimeEvents.AudioTranscriptDone transcriptDone = gson.fromJson(jsonObject,
                            RealtimeEvents.AudioTranscriptDone.class);
                    String transcript = transcriptDone.transcript != null ? transcriptDone.transcript : "";
                    if (!transcript.isEmpty()) {
                        Log.d(TAG, "Audio transcript: \"" + transcript + "\"");
                    }
                    if (listener != null) {
                        listener.onAudioTranscriptDone(transcript, transcriptDone.responseId);
                    }
                    break;
                case "response.output_audio.delta":
                    measureAudioLatency();
                    try {
                        RealtimeEvents.AudioDelta gaAudioDelta = gson.fromJson(jsonObject,
                                RealtimeEvents.AudioDelta.class);
                        byte[] bytes = android.util.Base64.decode(gaAudioDelta.delta, android.util.Base64.DEFAULT);
                        if (listener != null)
                            listener.onAudioDelta(bytes, gaAudioDelta.responseId);
                    } catch (Exception e) {
                        Log.e(TAG, "GA audio.delta decode failed", e);
                    }
                    break;
                case "response.output_audio_transcript.delta":
                    RealtimeEvents.AudioTranscriptDelta gaTranscriptDelta = gson.fromJson(jsonObject,
                            RealtimeEvents.AudioTranscriptDelta.class);
                    if (listener != null)
                        listener.onAudioTranscriptDelta(gaTranscriptDelta.delta, gaTranscriptDelta.responseId);
                    break;
                case "response.output_audio_transcript.done":
                    RealtimeEvents.AudioTranscriptDone gaTranscriptDone = gson.fromJson(jsonObject,
                            RealtimeEvents.AudioTranscriptDone.class);
                    String gaTranscript = gaTranscriptDone.transcript != null ? gaTranscriptDone.transcript : "";
                    if (!gaTranscript.isEmpty()) {
                        Log.d(TAG, "GA Audio transcript: \"" + gaTranscript + "\"");
                    }
                    if (listener != null) {
                        listener.onAudioTranscriptDone(gaTranscript, gaTranscriptDone.responseId);
                    }
                    break;
                case "response.output_item.done":
                    Log.d(TAG, "Output item done");
                    break;
                case "rate_limits.updated":
                    Log.d(TAG, "Rate limits updated");
                    break;
                case "input_audio_buffer.speech_started":
                    RealtimeEvents.UserSpeechStarted speechStarted = gson.fromJson(jsonObject,
                            RealtimeEvents.UserSpeechStarted.class);
                    Log.i(TAG, "User speech started (item: " + speechStarted.itemId + ")");
                    if (listener != null)
                        listener.onUserSpeechStarted(speechStarted.itemId);
                    break;
                case "input_audio_buffer.speech_stopped":
                    RealtimeEvents.UserSpeechStopped speechStopped = gson.fromJson(jsonObject,
                            RealtimeEvents.UserSpeechStopped.class);
                    Log.i(TAG, "User speech stopped (item: " + speechStopped.itemId + ")");
                    if (listener != null)
                        listener.onUserSpeechStopped(speechStopped.itemId);
                    break;
                case "input_audio_buffer.committed":
                    // Server has accepted the audio input and will generate a response
                    RealtimeEvents.AudioBufferCommitted bufferCommitted = gson.fromJson(jsonObject,
                            RealtimeEvents.AudioBufferCommitted.class);
                    Log.i(TAG, "Audio buffer committed (item: " + bufferCommitted.itemId + ")");
                    if (listener != null)
                        listener.onAudioBufferCommitted(bufferCommitted.itemId);
                    break;
                case "conversation.item.input_audio_transcription.completed":
                    RealtimeEvents.UserTranscriptCompleted transcriptCompleted = gson.fromJson(jsonObject,
                            RealtimeEvents.UserTranscriptCompleted.class);
                    Log.i(TAG, "User transcript completed (item: " + transcriptCompleted.itemId + "): "
                            + transcriptCompleted.transcript);
                    if (listener != null)
                        listener.onUserTranscriptCompleted(transcriptCompleted.itemId, transcriptCompleted.transcript);
                    break;
                case "conversation.item.input_audio_transcription.failed":
                    RealtimeEvents.UserTranscriptFailed transcriptFailed = gson.fromJson(jsonObject,
                            RealtimeEvents.UserTranscriptFailed.class);
                    Log.w(TAG, "User transcript failed (item: " + transcriptFailed.itemId + ")");
                    if (listener != null)
                        listener.onUserTranscriptFailed(transcriptFailed.itemId, transcriptFailed);
                    break;
                case "error":
                    RealtimeEvents.ErrorEvent errorEvent = gson.fromJson(jsonObject, RealtimeEvents.ErrorEvent.class);
                    if (listener != null)
                        listener.onError(errorEvent);
                    break;
                default:
                    if (listener != null)
                        listener.onUnknown(type, jsonObject);
            }
        } catch (JsonSyntaxException e) {
            Log.e(TAG, "Failed to parse event JSON: " + text, e);
            if (listener != null)
                listener.onUnknown("parse_error", null);
        } catch (Exception e) {
            Log.e(TAG, "Error handling event: " + text, e);
            if (listener != null)
                listener.onUnknown("error", null);
        }
    }
}
