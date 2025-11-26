package io.github.anonymous.pepper_realtime.network

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

class RealtimeEventHandler(val listener: Listener) {

    companion object {
        private const val TAG = "RealtimeEvents"

        // Track if we've already measured latency for this response
        @Volatile
        private var latencyMeasured = false

        /**
         * Measure and log latency from response.create to first audio chunk
         */
        private fun measureAudioLatency() {
            if (!latencyMeasured && RealtimeSessionManager.responseCreateTimestamp > 0) {
                val now = System.currentTimeMillis()
                val latency = now - RealtimeSessionManager.responseCreateTimestamp
                Log.i(TAG, "🎵 AUDIO LATENCY: ${latency}ms (response.create → first audio chunk)")
                latencyMeasured = true
            }
        }

        /**
         * Reset latency measurement for new response
         */
        fun resetLatencyMeasurement() {
            latencyMeasured = false
        }
    }

    interface Listener {
        fun onSessionUpdated(event: RealtimeEvents.SessionUpdated)
        fun onAudioTranscriptDelta(delta: String?, responseId: String?)
        fun onAudioDelta(pcm16: ByteArray, responseId: String?)
        fun onResponseBoundary(newResponseId: String?)
        fun onAudioDone()
        fun onResponseDone(event: RealtimeEvents.ResponseDone)
        fun onAssistantItemAdded(itemId: String?)
        fun onResponseCreated(responseId: String?)
        fun onError(error: RealtimeEvents.ErrorEvent)
        fun onUnknown(type: String, raw: JsonObject?)

        // User audio input events (Realtime API audio mode)
        fun onUserSpeechStarted(itemId: String?)
        fun onUserSpeechStopped(itemId: String?)
        fun onAudioBufferCommitted(itemId: String?)
        fun onUserItemCreated(itemId: String?, event: RealtimeEvents.ConversationItemCreated)
        fun onUserTranscriptCompleted(itemId: String?, transcript: String?)
        fun onUserTranscriptFailed(itemId: String?, event: RealtimeEvents.UserTranscriptFailed)
        fun onAudioTranscriptDone(transcript: String?, responseId: String?)
    }

    private val gson = Gson()

    fun handle(text: String) {
        try {
            val jsonObject = JsonParser.parseString(text).asJsonObject
            val type = if (jsonObject.has("type")) jsonObject.get("type").asString else ""

            // Skip logging for high-frequency delta events to avoid log spam
            if (!type.endsWith(".delta")) {
                Log.d(TAG, "Received event type: $type")
            }

            when (type) {
                "session.created" -> {
                    val sessionCreated = gson.fromJson(jsonObject, RealtimeEvents.SessionCreated::class.java)
                    sessionCreated.session?.let { session ->
                        Log.i(TAG, "Session created - Model: ${session.model}, ID: ${session.id}")
                    }
                }
                "session.updated" -> {
                    val sessionUpdated = gson.fromJson(jsonObject, RealtimeEvents.SessionUpdated::class.java)
                    sessionUpdated.session?.let { session ->
                        Log.i(TAG, "Session updated - Model: ${session.model}, ID: ${session.id}")
                    }
                    listener.onSessionUpdated(sessionUpdated)
                }
                "response.audio_transcript.delta" -> {
                    val transcriptDelta = gson.fromJson(jsonObject, RealtimeEvents.AudioTranscriptDelta::class.java)
                    listener.onAudioTranscriptDelta(transcriptDelta.delta, transcriptDelta.responseId)
                }
                "conversation.item.added" -> {
                    Log.d(TAG, "Conversation item added (GA API)")
                }
                "conversation.item.done" -> {
                    Log.d(TAG, "Conversation item done (GA API)")
                }
                "response.audio.delta" -> {
                    measureAudioLatency() // Measure latency on first audio chunk
                    try {
                        val audioDelta = gson.fromJson(jsonObject, RealtimeEvents.AudioDelta::class.java)
                        val bytes = Base64.decode(audioDelta.delta, Base64.DEFAULT)
                        listener.onAudioDelta(bytes, audioDelta.responseId)
                    } catch (e: Exception) {
                        Log.e(TAG, "audio.delta decode failed", e)
                    }
                }
                "response.created" -> {
                    val responseCreated = gson.fromJson(jsonObject, RealtimeEvents.ResponseCreated::class.java)
                    responseCreated.response?.id?.let { responseId ->
                        listener.onResponseCreated(responseId)
                        listener.onResponseBoundary(responseId)
                    }
                }
                "response.audio.done", "response.output_audio.done" -> {
                    listener.onAudioDone()
                }
                "response.done" -> {
                    val responseDone = gson.fromJson(jsonObject, RealtimeEvents.ResponseDone::class.java)
                    listener.onResponseDone(responseDone)
                }
                "response.output_item.added" -> {
                    val outputItemAdded = gson.fromJson(jsonObject, RealtimeEvents.ResponseOutputItemAdded::class.java)
                    outputItemAdded.item?.let { item ->
                        if (item.type == "message" && item.role == "assistant") {
                            listener.onAssistantItemAdded(item.id)
                        }
                    }
                }
                "conversation.item.created" -> {
                    val itemCreated = gson.fromJson(jsonObject, RealtimeEvents.ConversationItemCreated::class.java)
                    if (itemCreated.item?.role == "user") {
                        Log.i(TAG, "User conversation item created: ${itemCreated.item?.id}")
                        listener.onUserItemCreated(itemCreated.item?.id, itemCreated)
                    } else {
                        Log.d(TAG, "Conversation item created")
                    }
                }
                "conversation.item.truncated" -> {
                    Log.d(TAG, "Conversation item truncated")
                }
                "response.content_part.added" -> {
                    Log.d(TAG, "Response content part added")
                }
                "response.content_part.done" -> {
                    Log.d(TAG, "Response content part done")
                }
                "response.function_call_arguments.delta" -> {
                    // Skip logging for high-frequency events
                }
                "response.function_call_arguments.done" -> {
                    Log.d(TAG, "Function call arguments done")
                }
                "response.audio_transcript.done" -> {
                    val transcriptDone = gson.fromJson(jsonObject, RealtimeEvents.AudioTranscriptDone::class.java)
                    val transcript = transcriptDone.transcript ?: ""
                    if (transcript.isNotEmpty()) {
                        Log.d(TAG, "Audio transcript: \"$transcript\"")
                    }
                    listener.onAudioTranscriptDone(transcript, transcriptDone.responseId)
                }
                "response.output_audio.delta" -> {
                    measureAudioLatency()
                    try {
                        val gaAudioDelta = gson.fromJson(jsonObject, RealtimeEvents.AudioDelta::class.java)
                        val bytes = Base64.decode(gaAudioDelta.delta, Base64.DEFAULT)
                        listener.onAudioDelta(bytes, gaAudioDelta.responseId)
                    } catch (e: Exception) {
                        Log.e(TAG, "GA audio.delta decode failed", e)
                    }
                }
                "response.output_audio_transcript.delta" -> {
                    val gaTranscriptDelta = gson.fromJson(jsonObject, RealtimeEvents.AudioTranscriptDelta::class.java)
                    listener.onAudioTranscriptDelta(gaTranscriptDelta.delta, gaTranscriptDelta.responseId)
                }
                "response.output_audio_transcript.done" -> {
                    val gaTranscriptDone = gson.fromJson(jsonObject, RealtimeEvents.AudioTranscriptDone::class.java)
                    val gaTranscript = gaTranscriptDone.transcript ?: ""
                    if (gaTranscript.isNotEmpty()) {
                        Log.d(TAG, "GA Audio transcript: \"$gaTranscript\"")
                    }
                    listener.onAudioTranscriptDone(gaTranscript, gaTranscriptDone.responseId)
                }
                "response.output_item.done" -> {
                    Log.d(TAG, "Output item done")
                }
                "rate_limits.updated" -> {
                    Log.d(TAG, "Rate limits updated")
                }
                "input_audio_buffer.speech_started" -> {
                    val speechStarted = gson.fromJson(jsonObject, RealtimeEvents.UserSpeechStarted::class.java)
                    Log.i(TAG, "User speech started (item: ${speechStarted.itemId})")
                    listener.onUserSpeechStarted(speechStarted.itemId)
                }
                "input_audio_buffer.speech_stopped" -> {
                    val speechStopped = gson.fromJson(jsonObject, RealtimeEvents.UserSpeechStopped::class.java)
                    Log.i(TAG, "User speech stopped (item: ${speechStopped.itemId})")
                    listener.onUserSpeechStopped(speechStopped.itemId)
                }
                "input_audio_buffer.committed" -> {
                    // Server has accepted the audio input and will generate a response
                    val bufferCommitted = gson.fromJson(jsonObject, RealtimeEvents.AudioBufferCommitted::class.java)
                    Log.i(TAG, "Audio buffer committed (item: ${bufferCommitted.itemId})")
                    listener.onAudioBufferCommitted(bufferCommitted.itemId)
                }
                "conversation.item.input_audio_transcription.completed" -> {
                    val transcriptCompleted = gson.fromJson(jsonObject, RealtimeEvents.UserTranscriptCompleted::class.java)
                    Log.i(TAG, "User transcript completed (item: ${transcriptCompleted.itemId}): ${transcriptCompleted.transcript}")
                    listener.onUserTranscriptCompleted(transcriptCompleted.itemId, transcriptCompleted.transcript)
                }
                "conversation.item.input_audio_transcription.failed" -> {
                    val transcriptFailed = gson.fromJson(jsonObject, RealtimeEvents.UserTranscriptFailed::class.java)
                    Log.w(TAG, "User transcript failed (item: ${transcriptFailed.itemId})")
                    listener.onUserTranscriptFailed(transcriptFailed.itemId, transcriptFailed)
                }
                "error" -> {
                    val errorEvent = gson.fromJson(jsonObject, RealtimeEvents.ErrorEvent::class.java)
                    listener.onError(errorEvent)
                }
                else -> {
                    listener.onUnknown(type, jsonObject)
                }
            }
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "Failed to parse event JSON: $text", e)
            listener.onUnknown("parse_error", null)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling event: $text", e)
            listener.onUnknown("error", null)
        }
    }
}


