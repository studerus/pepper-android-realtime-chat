package ch.fhnw.pepper_realtime.network

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

        // Google Live API events
        fun onGoogleSetupComplete() {}
        fun onGoogleInterrupted() {}
        fun onGoogleModelTurnStarted() {}  // First modelTurn in a response - signal to enter THINKING
        fun onGoogleToolCall(functionCalls: List<GoogleLiveEvents.FunctionCall>) {}
        fun onGoogleToolCallCancellation(ids: List<String>) {}
    }

    private val gson = Gson()

    // Flag to indicate if we're handling Google Live API events
    var isGoogleProvider: Boolean = false
    
    // Track if we've already signaled the first modelTurn in current response
    private var hasSignaledModelTurnStart: Boolean = false
    
    // Collect Google transcriptions for logging at turn end
    private val googleInputBuffer = StringBuilder()
    private val googleThinkingBuffer = StringBuilder()
    private val googleOutputBuffer = StringBuilder()
    
    /**
     * Signal that model turn has started (first output or modelTurn).
     * This is used to mute the microphone when the model starts responding.
     */
    private fun signalModelTurnStartedIfNeeded() {
        if (!hasSignaledModelTurnStart) {
            // Log collected user input before model starts responding
            if (googleInputBuffer.isNotEmpty()) {
                Log.i(TAG, "Google input: \"${googleInputBuffer}\"")
                googleInputBuffer.clear()
            }
            hasSignaledModelTurnStart = true
            listener.onGoogleModelTurnStarted()
        }
    }

    fun handle(text: String) {
        try {
            val jsonObject = JsonParser.parseString(text).asJsonObject

            // Check if this is a Google Live API message (no "type" field, uses top-level fields)
            if (isGoogleProvider || !jsonObject.has("type")) {
                handleGoogleEvent(jsonObject)
                return
            }

            val type = jsonObject.get("type").asString

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

    /**
     * Handle Google Live API events.
     * Google uses top-level fields (serverContent, toolCall, setupComplete) instead of "type".
     */
    private fun handleGoogleEvent(jsonObject: JsonObject) {
        try {
            // Check for error response first
            if (jsonObject.has("error")) {
                val error = jsonObject.getAsJsonObject("error")
                val code = error?.get("code")?.asInt ?: -1
                val message = error?.get("message")?.asString ?: "Unknown error"
                val status = error?.get("status")?.asString ?: ""
                Log.e(TAG, "Google API Error: code=$code, status=$status, message=$message")
                listener.onError(RealtimeEvents.ErrorEvent().apply {
                    this.error = RealtimeEvents.Error().apply {
                        this.code = code.toString()
                        this.message = message
                    }
                })
                return
            }

            // Parse the server message
            val serverMessage = gson.fromJson(jsonObject, GoogleLiveEvents.ServerMessage::class.java)

            // Handle setupComplete
            if (serverMessage.setupComplete != null) {
                Log.i(TAG, "Google: Setup complete")
                listener.onGoogleSetupComplete()
                return
            }

            // Handle toolCall
            if (serverMessage.toolCall != null) {
                val functionCalls = serverMessage.toolCall.functionCalls
                if (!functionCalls.isNullOrEmpty()) {
                    Log.i(TAG, "Google: Tool call - ${functionCalls.map { it.name }.joinToString()}")
                    listener.onGoogleToolCall(functionCalls)
                }
                return
            }

            // Handle toolCallCancellation
            if (serverMessage.toolCallCancellation != null) {
                val ids = serverMessage.toolCallCancellation.ids
                if (!ids.isNullOrEmpty()) {
                    Log.i(TAG, "Google: Tool call cancellation for ${ids.size} call(s)")
                    listener.onGoogleToolCallCancellation(ids)
                }
                return
            }

            // Handle serverContent
            val serverContent = serverMessage.serverContent
            if (serverContent != null) {
                handleGoogleServerContent(serverContent)
                return
            }

            // Unknown Google event
            Log.w(TAG, "Google: Unknown event structure")
            listener.onUnknown("google_unknown", jsonObject)

        } catch (e: Exception) {
            Log.e(TAG, "Error handling Google event", e)
            listener.onUnknown("google_error", jsonObject)
        }
    }

    /**
     * Handle Google serverContent events (audio, text, transcriptions, interruptions).
     */
    private fun handleGoogleServerContent(content: GoogleLiveEvents.ServerContent) {
        // Handle interruption (barge-in)
        if (content.interrupted == true) {
            Log.i(TAG, "Google: Interrupted (barge-in)")
            // Clear buffers on interruption
            googleInputBuffer.clear()
            googleThinkingBuffer.clear()
            googleOutputBuffer.clear()
            hasSignaledModelTurnStart = false  // Reset for next response
            listener.onGoogleInterrupted()
            // Also trigger audio done to stop playback
            listener.onAudioDone()
        }

        // Handle input transcription (user speech-to-text) - collect for logging
        content.inputTranscription?.text?.let { transcript ->
            if (transcript.isNotEmpty()) {
                googleInputBuffer.append(transcript)
                listener.onUserTranscriptCompleted(null, transcript)
            }
        }

        // Handle output transcription (model speech-to-text)
        content.outputTranscription?.text?.let { transcript ->
            if (transcript.isNotEmpty()) {
                // Signal model turn started on first output (mutes microphone)
                signalModelTurnStartedIfNeeded()
                googleOutputBuffer.append(transcript)
                listener.onAudioTranscriptDelta(transcript, null)
            }
        }

        // Handle model turn (audio/text parts)
        if (content.modelTurn != null) {
            // Signal model turn started on first modelTurn (mutes microphone)
            signalModelTurnStartedIfNeeded()
        }
        
        content.modelTurn?.parts?.forEach { part ->
            // Handle audio data
            part.inlineData?.let { inlineData ->
                if (inlineData.data != null && inlineData.mimeType?.startsWith("audio/") == true) {
                    measureAudioLatency()
                    try {
                        val bytes = Base64.decode(inlineData.data, Base64.DEFAULT)
                        listener.onAudioDelta(bytes, null)
                    } catch (e: Exception) {
                        Log.e(TAG, "Google: Failed to decode audio data", e)
                    }
                }
            }

            // Handle text data
            part.text?.let { text ->
                if (text.isNotEmpty()) {
                    if (part.thought == true) {
                        // Collect thinking traces for logging at turn end
                        googleThinkingBuffer.append(text)
                    } else {
                        // Non-thought text (rarely used - outputTranscription is preferred)
                        listener.onAudioTranscriptDelta(text, null)
                    }
                }
            }
        }

        // Handle turn complete
        if (content.turnComplete == true) {
            // Log collected thinking and output
            if (googleThinkingBuffer.isNotEmpty()) {
                val thinking = googleThinkingBuffer.toString()
                val preview = if (thinking.length > 500) thinking.take(500) + "..." else thinking
                Log.i(TAG, "Google thinking: \"$preview\"")
                googleThinkingBuffer.clear()
            }
            if (googleOutputBuffer.isNotEmpty()) {
                Log.i(TAG, "Google output: \"${googleOutputBuffer}\"")
                googleOutputBuffer.clear()
            }
            
            hasSignaledModelTurnStart = false  // Reset for next response
            listener.onAudioDone()
            // Create a synthetic ResponseDone for compatibility
            listener.onResponseDone(RealtimeEvents.ResponseDone())
        }
    }
}


