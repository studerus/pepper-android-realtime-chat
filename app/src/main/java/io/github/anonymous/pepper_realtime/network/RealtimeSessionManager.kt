package io.github.anonymous.pepper_realtime.network

import android.util.Log
import io.github.anonymous.pepper_realtime.manager.ApiKeyManager
import io.github.anonymous.pepper_realtime.manager.SettingsRepository
import io.github.anonymous.pepper_realtime.tools.ToolContext
import io.github.anonymous.pepper_realtime.tools.ToolRegistry
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@Singleton
class RealtimeSessionManager @Inject constructor() {

    interface Listener {
        fun onOpen(response: Response)
        fun onTextMessage(text: String)
        fun onBinaryMessage(bytes: ByteString)
        fun onClosing(code: Int, reason: String)
        fun onClosed(code: Int, reason: String)
        fun onFailure(t: Throwable, response: Response?)
    }

    fun interface SessionConfigCallback {
        fun onSessionConfigured(success: Boolean, error: String?)
    }

    companion object {
        private const val TAG = "RealtimeSession"

        // Latency measurement for audio response time
        @Volatile
        var responseCreateTimestamp: Long = 0
    }

    // Use optimized shared WebSocket client for better performance
    private val client = HttpClientManager.getInstance().getWebSocketClient()
    private var webSocket: WebSocket? = null
    var listener: Listener? = null
    private var sessionConfigCallback: SessionConfigCallback? = null

    // Session configuration dependencies
    private var toolRegistry: ToolRegistry? = null
    private var toolContext: ToolContext? = null
    private var settingsRepository: SettingsRepository? = null
    private var keyManager: ApiKeyManager? = null

    fun setSessionConfigCallback(callback: SessionConfigCallback?) {
        this.sessionConfigCallback = callback
    }

    /**
     * Set dependencies for session configuration
     */
    fun setSessionDependencies(
        toolRegistry: ToolRegistry,
        toolContext: ToolContext,
        settingsRepository: SettingsRepository,
        keyManager: ApiKeyManager
    ) {
        this.toolRegistry = toolRegistry
        this.toolContext = toolContext
        this.settingsRepository = settingsRepository
        this.keyManager = keyManager
    }

    val isConnected: Boolean
        get() = webSocket != null

    fun connect(url: String, headers: Map<String, String>?) {
        val builder = okhttp3.Request.Builder().url(url)
        headers?.forEach { (key, value) ->
            builder.addHeader(key, value)
        }
        val request = builder.build()

        val wsListener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket onOpen: ${response.message} Code: ${response.code}")
                webSocket = ws
                listener?.onOpen(response) ?: Log.w(TAG, "WARNING: onOpen called but listener is null!")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                listener?.onTextMessage(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                listener?.onBinaryMessage(bytes)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                listener?.onClosing(code, reason)
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                listener?.onClosed(code, reason)
                if (webSocket == ws) webSocket = null
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                listener?.onFailure(t, response) ?: Log.w(TAG, "WARNING: onFailure called but listener is null!")
                if (webSocket == ws) webSocket = null
            }
        }
        client.newWebSocket(request, wsListener)
    }

    fun sendUserTextMessage(text: String): Boolean {
        return try {
            val createItemPayload = JSONObject().apply {
                put("type", "conversation.item.create")
                put("item", JSONObject().apply {
                    put("type", "message")
                    put("role", "user")
                    put("content", JSONArray().put(JSONObject().apply {
                        put("type", "input_text")
                        put("text", text)
                    }))
                })
            }
            Log.d(TAG, "Sending conversation.item.create: $text")
            send(createItemPayload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error creating user text message", e)
            false
        }
    }

    fun requestResponse(): Boolean {
        return try {
            val createResponsePayload = JSONObject().apply {
                put("type", "response.create")
            }
            // Reset latency measurement for new response and record timestamp
            RealtimeEventHandler.resetLatencyMeasurement()
            responseCreateTimestamp = System.currentTimeMillis()
            Log.d(TAG, "Sending response.create at $responseCreateTimestamp")
            send(createResponsePayload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error creating response request", e)
            false
        }
    }

    /**
     * Send a user image message to the Realtime API (GA/Beta) as a conversation item
     * Uses input_image content with a data URL to avoid separate upload steps.
     *
     * @param base64 Base64-encoded JPEG/PNG without data URL prefix
     * @param mime MIME type, e.g. "image/jpeg" (default recommended)
     */
    fun sendUserImageMessage(base64: String, mime: String?): Boolean {
        return try {
            val safeMime = if (mime.isNullOrEmpty()) "image/jpeg" else mime

            val payload = JSONObject().apply {
                put("type", "conversation.item.create")
                put("item", JSONObject().apply {
                    put("type", "message")
                    put("role", "user")
                    put("content", JSONArray().put(JSONObject().apply {
                        put("type", "input_image")
                        put("image_url", "data:$safeMime;base64,$base64")
                    }))
                })
            }
            Log.d(TAG, "Sending conversation.item.create with image content")
            send(payload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error creating user image message", e)
            false
        }
    }

    fun sendToolResult(callId: String, result: String): Boolean {
        return try {
            val toolResultPayload = JSONObject().apply {
                put("type", "conversation.item.create")
                put("item", JSONObject().apply {
                    put("type", "function_call_output")
                    put("call_id", callId)
                    put("output", result)
                })
            }
            Log.d(TAG, "Sending tool result: $toolResultPayload")
            send(toolResultPayload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error creating tool result message", e)
            false
        }
    }

    /**
     * Send audio chunk to Realtime API input audio buffer
     *
     * @param base64Audio Base64-encoded PCM16 audio data
     * @return true if sent successfully
     */
    fun sendAudioChunk(base64Audio: String): Boolean {
        return try {
            val payload = JSONObject().apply {
                put("type", "input_audio_buffer.append")
                put("audio", base64Audio)
            }
            send(payload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error creating audio chunk payload", e)
            false
        }
    }

    fun send(text: String): Boolean {
        val ws = webSocket
        if (ws == null) {
            Log.w(TAG, "🚨 DIAGNOSTIC: Cannot send - webSocket is null")
            return false
        }

        return try {
            val result = ws.send(text)
            if (!result) {
                Log.w(TAG, "🚨 DIAGNOSTIC: WebSocket.send() returned false - connection may be broken")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "🚨 DIAGNOSTIC: WebSocket.send() threw exception", e)
            false
        }
    }

    fun close(code: Int, reason: String?) {
        try {
            webSocket?.close(code, reason)
        } catch (_: Exception) {
        }
        webSocket = null
    }

    /**
     * Configure initial session with tools, voice, temperature, and instructions
     * This should be called when WebSocket connection is established
     */
    fun configureInitialSession() {
        if (!isConnected) {
            Log.w(TAG, "Session config SKIPPED - not connected")
            sessionConfigCallback?.onSessionConfigured(false, "Not connected")
            return
        }

        if (settingsRepository == null || toolRegistry == null || toolContext == null) {
            Log.w(TAG, "Session config SKIPPED - missing dependencies")
            sessionConfigCallback?.onSessionConfigured(false, "Missing dependencies")
            return
        }

        try {
            val payload = createSessionUpdatePayload("Initial session")
            val sent = send(payload.toString())

            if (sent) {
                sessionConfigCallback?.onSessionConfigured(true, null)
            } else {
                val error = "Failed to send initial session config"
                Log.e(TAG, error)
                sessionConfigCallback?.onSessionConfigured(false, error)
            }
        } catch (e: Exception) {
            val error = "Error creating initial session config: ${e.message}"
            Log.e(TAG, error, e)
            sessionConfigCallback?.onSessionConfigured(false, error)
        }
    }

    /**
     * Update session configuration with current settings
     * This should be called when settings change during an active session
     */
    fun updateSession() {
        if (!isConnected) {
            Log.w(TAG, "Session update SKIPPED - not connected")
            return
        }

        if (settingsRepository == null || toolRegistry == null || toolContext == null) {
            Log.w(TAG, "Session update SKIPPED - missing dependencies")
            return
        }

        try {
            // Debug YouTube API key availability
            val enabledTools = settingsRepository!!.enabledTools
            if (keyManager != null && enabledTools.contains("play_youtube_video")) {
                val hasYouTubeKey = keyManager!!.isYouTubeAvailable()
                if (!hasYouTubeKey) {
                    Log.w(TAG, "YouTube tool enabled but no API key found!")
                }
            }

            val payload = createSessionUpdatePayload("Session update")
            val sent = send(payload.toString())

            if (!sent) {
                Log.e(TAG, "Failed to send session update")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating session config", e)
        }
    }

    /**
     * Creates the session.update payload based on current settings.
     * Eliminates duplication between initial config and updates.
     */
    @Throws(Exception::class)
    private fun createSessionUpdatePayload(contextLog: String): JSONObject {
        val settings = settingsRepository!!
        val voice = settings.voice
        val speed = settings.speed
        val model = settings.model
        val temperature = settings.temperature
        val systemPrompt = settings.systemPrompt
        val enabledTools = settings.enabledTools

        Log.i(TAG, "$contextLog - Model: $model, Enabled tools: $enabledTools")

        val payload = JSONObject()
        payload.put("type", "session.update")

        val sessionConfig = JSONObject()

        // For OpenAI Direct with gpt-realtime, use GA API structure
        if (model == "gpt-realtime" && settings.apiProviderEnum == RealtimeApiProvider.OPENAI_DIRECT) {
            sessionConfig.put("type", "realtime")

            // GA API uses structured audio configuration
            val audio = JSONObject()

            // Output configuration
            val output = JSONObject().apply {
                put("voice", voice)
                put("speed", speed)
                put("format", JSONObject().apply {
                    put("type", "audio/pcm")
                    put("rate", 24000)
                })
            }
            audio.put("output", output)

            // Input configuration with turn detection
            val input = JSONObject()

            // Enable input audio configuration if using Realtime API audio mode
            if (settings.isUsingRealtimeAudioInput) {
                // Turn Detection configuration
                val turnDetection = JSONObject()
                val turnDetType = settings.turnDetectionType
                turnDetection.put("type", turnDetType)
                turnDetection.put("create_response", true)
                turnDetection.put("interrupt_response", true)

                if (turnDetType == "server_vad") {
                    turnDetection.put("threshold", settings.vadThreshold)
                    turnDetection.put("prefix_padding_ms", settings.prefixPadding)
                    turnDetection.put("silence_duration_ms", settings.silenceDuration)

                    // Idle timeout only supported for server_vad
                    val idleTimeout = settings.idleTimeout
                    if (idleTimeout != null && idleTimeout > 0) {
                        turnDetection.put("idle_timeout_ms", idleTimeout)
                    }
                } else if (turnDetType == "semantic_vad") {
                    val eagerness = settings.eagerness
                    turnDetection.put("eagerness", eagerness)
                }

                input.put("turn_detection", turnDetection)

                // Transcription configuration
                val transcription = JSONObject().apply {
                    put("model", settings.transcriptionModel)
                    val transcriptLang = settings.transcriptionLanguage
                    if (!transcriptLang.isNullOrEmpty()) {
                        put("language", transcriptLang)
                    }
                    val transcriptPrompt = settings.transcriptionPrompt
                    if (!transcriptPrompt.isNullOrEmpty()) {
                        put("prompt", transcriptPrompt)
                    }
                }
                input.put("transcription", transcription)

                // Noise Reduction configuration
                val noiseReduction = settings.noiseReduction
                if (noiseReduction != "off") {
                    input.put("noise_reduction", JSONObject().apply {
                        put("type", noiseReduction)
                    })
                } else {
                    input.put("noise_reduction", JSONObject.NULL)
                }

                // Input format
                input.put("format", JSONObject().apply {
                    put("type", "audio/pcm")
                    put("rate", 24000)
                })

                Log.i(TAG, "Input audio enabled - Turn: $turnDetType, Transcription: ${settings.transcriptionModel}")
            } else {
                // Azure Speech mode - disable turn detection
                input.put("turn_detection", JSONObject.NULL)
            }

            audio.put("input", input)
            sessionConfig.put("audio", audio)
            // Note: temperature not supported in GA API
        } else {
            // Preview/Mini models (Beta API) - use legacy parameters with server VAD
            sessionConfig.put("voice", voice)
            sessionConfig.put("speed", speed)
            sessionConfig.put("temperature", temperature)
            sessionConfig.put("output_audio_format", "pcm16")

            // Configure audio input for Realtime API audio mode
            if (settings.isUsingRealtimeAudioInput) {
                // Turn Detection configuration
                val turnDetection = JSONObject()
                val turnDetType = settings.turnDetectionType
                turnDetection.put("type", turnDetType)
                turnDetection.put("create_response", true)
                turnDetection.put("interrupt_response", true)

                if (turnDetType == "server_vad") {
                    turnDetection.put("threshold", settings.vadThreshold)
                    turnDetection.put("prefix_padding_ms", settings.prefixPadding)
                    turnDetection.put("silence_duration_ms", settings.silenceDuration)
                }

                val idleTimeout = settings.idleTimeout
                if (idleTimeout != null && idleTimeout > 0) {
                    turnDetection.put("idle_timeout_ms", idleTimeout)
                }

                sessionConfig.put("turn_detection", turnDetection)

                // Configure input audio format
                sessionConfig.put("input_audio_format", "pcm16")

                // Enable input audio transcription
                val inputTranscription = JSONObject().apply {
                    put("model", settings.transcriptionModel)
                    val transcriptLang = settings.transcriptionLanguage
                    if (!transcriptLang.isNullOrEmpty()) {
                        put("language", transcriptLang)
                    }
                    val transcriptPrompt = settings.transcriptionPrompt
                    if (!transcriptPrompt.isNullOrEmpty()) {
                        put("prompt", transcriptPrompt)
                    }
                }
                sessionConfig.put("input_audio_transcription", inputTranscription)

                Log.i(TAG, "Beta API: Turn detection=$turnDetType, Transcription=${settings.transcriptionModel}")
            } else {
                // Azure Speech mode - disable server VAD
                sessionConfig.put("turn_detection", JSONObject.NULL)
            }
        }
        sessionConfig.put("instructions", systemPrompt)

        val toolsArray = toolRegistry!!.buildToolsDefinitionForAzure(toolContext!!, enabledTools)
        sessionConfig.put("tools", toolsArray)

        payload.put("session", sessionConfig)

        Log.d(TAG, "$contextLog payload built with ${toolsArray.length()} tools")
        logToolsDebug(toolsArray, contextLog)

        return payload
    }

    /**
     * Log tools array for debugging
     */
    private fun logToolsDebug(toolsArray: JSONArray, context: String) {
        try {
            Log.d(TAG, "$context tools: ${toolsArray.length()} tools")
            // Reduced verbosity - only log first 3 tools
            val logLimit = min(3, toolsArray.length())
            for (i in 0 until logLimit) {
                val tool = toolsArray.getJSONObject(i)
                Log.d(TAG, "  $context tool $i: ${tool.optString("name")}")
            }
            if (toolsArray.length() > logLimit) {
                Log.d(TAG, "  ... and ${toolsArray.length() - logLimit} more tools")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error logging tools debug info", e)
        }
    }
}


