package ch.fhnw.pepper_realtime.network

import android.util.Log
import ch.fhnw.pepper_realtime.manager.ApiKeyManager
import ch.fhnw.pepper_realtime.manager.SettingsRepository
import ch.fhnw.pepper_realtime.tools.ToolContext
import ch.fhnw.pepper_realtime.tools.ToolRegistry
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

    /**
     * Represents the current state of the WebSocket connection.
     * Used for robust state tracking without automatic reconnection.
     */
    enum class ConnectionState {
        DISCONNECTED,  // No connection
        CONNECTING,    // connect() called, waiting for onOpen
        CONNECTED,     // Session active
        CLOSING        // close() called, waiting for onClosed
    }

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

        // Pre-allocated components for audio chunk payloads to reduce GC pressure
        // Base64-encoded 4800 bytes (100ms @ 24kHz) = ~6400 chars, plus JSON wrapper ~50 chars
        private const val AUDIO_PAYLOAD_PREFIX = """{"type":"input_audio_buffer.append","audio":""""
        private const val AUDIO_PAYLOAD_SUFFIX = """"}"""
        private const val ESTIMATED_AUDIO_PAYLOAD_SIZE = 6500

        // Google Live API audio payload components (16kHz - Google's native input rate)
        private const val GOOGLE_AUDIO_PAYLOAD_PREFIX = """{"realtimeInput":{"audio":{"data":""""
        private const val GOOGLE_AUDIO_PAYLOAD_SUFFIX = """","mimeType":"audio/pcm;rate=16000"}}}"""
    }

    // Reusable StringBuilder for audio chunk payloads - reduces allocations from ~10/sec to 0
    private val audioPayloadBuilder = StringBuilder(ESTIMATED_AUDIO_PAYLOAD_SIZE)

    // Use optimized shared WebSocket client for better performance
    private val client = HttpClientManager.getInstance().getWebSocketClient()
    private var webSocket: WebSocket? = null
    var listener: Listener? = null
    private var sessionConfigCallback: SessionConfigCallback? = null

    // Connection state tracking - volatile for thread-safe access from callbacks
    @Volatile
    private var connectionState = ConnectionState.DISCONNECTED

    // Session configuration dependencies
    private var toolRegistry: ToolRegistry? = null
    private var toolContext: ToolContext? = null
    private var settingsRepository: SettingsRepository? = null
    private var keyManager: ApiKeyManager? = null

    fun setSessionConfigCallback(callback: SessionConfigCallback?) {
        this.sessionConfigCallback = callback
    }

    /**
     * Called by event handler when Google Live API sends setupComplete.
     * This confirms the session is ready for communication.
     */
    fun onGoogleSetupComplete() {
        Log.i(TAG, "Google setupComplete received - session is ready")
        sessionConfigCallback?.onSessionConfigured(true, null)
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

    /**
     * Returns true only when the WebSocket is fully connected and ready for communication.
     * Unlike a simple null check, this properly handles CONNECTING and CLOSING states.
     */
    val isConnected: Boolean
        get() = connectionState == ConnectionState.CONNECTED

    /**
     * Current connection state for diagnostic purposes.
     */
    val currentConnectionState: ConnectionState
        get() = connectionState

    fun connect(url: String, headers: Map<String, String>?) {
        if (connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.CONNECTED) {
            Log.w(TAG, "connect() called but already in state: $connectionState")
            return
        }

        connectionState = ConnectionState.CONNECTING
        Log.d(TAG, "Connection state: CONNECTING")

        val builder = okhttp3.Request.Builder().url(url)
        headers?.forEach { (key, value) ->
            builder.addHeader(key, value)
        }
        val request = builder.build()

        val wsListener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket onOpen: ${response.message} Code: ${response.code}")
                webSocket = ws
                connectionState = ConnectionState.CONNECTED
                Log.d(TAG, "Connection state: CONNECTED")
                listener?.onOpen(response) ?: Log.w(TAG, "WARNING: onOpen called but listener is null!")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                listener?.onTextMessage(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                listener?.onBinaryMessage(bytes)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                connectionState = ConnectionState.CLOSING
                Log.d(TAG, "Connection state: CLOSING (code=$code, reason=$reason)")
                listener?.onClosing(code, reason)
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                connectionState = ConnectionState.DISCONNECTED
                Log.d(TAG, "Connection state: DISCONNECTED (code=$code, reason=$reason)")
                listener?.onClosed(code, reason)
                if (webSocket == ws) webSocket = null
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                connectionState = ConnectionState.DISCONNECTED
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                Log.d(TAG, "Connection state: DISCONNECTED (failure)")
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
     * Send audio chunk to Realtime API input audio buffer.
     * 
     * Optimized for high-frequency calls (~10/sec at 24kHz with 100ms chunks):
     * - Reuses StringBuilder to avoid repeated allocations
     * - Uses string template instead of JSONObject for less overhead
     * - Reduces GC pressure on resource-constrained devices like Pepper's tablet
     *
     * @param base64Audio Base64-encoded PCM16 audio data
     * @return true if sent successfully
     */
    fun sendAudioChunk(base64Audio: String): Boolean {
        return try {
            // Reuse StringBuilder - clear and rebuild payload
            audioPayloadBuilder.setLength(0)
            audioPayloadBuilder.append(AUDIO_PAYLOAD_PREFIX)
            audioPayloadBuilder.append(base64Audio)
            audioPayloadBuilder.append(AUDIO_PAYLOAD_SUFFIX)
            
            send(audioPayloadBuilder.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error creating audio chunk payload", e)
            false
        }
    }

    // ==================== GOOGLE LIVE API METHODS ====================

    /**
     * Send audioStreamEnd event to Google Live API.
     * Should be called when audio input is paused for more than ~1 second,
     * e.g., when microphone is muted or when transitioning away from LISTENING state.
     * This signals to Google to flush any buffered audio and process it.
     *
     * @return true if sent successfully
     */
    fun sendGoogleAudioStreamEnd(): Boolean {
        return try {
            val payload = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("audioStreamEnd", true)
                })
            }
            Log.d(TAG, "Sending Google audioStreamEnd")
            send(payload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending Google audioStreamEnd", e)
            false
        }
    }

    /**
     * Send an image/video frame to Google Live API via realtimeInput.video.
     * This is the streaming format that adds to context WITHOUT triggering a response.
     * Use this for Drawing Game or other cases where you want silent context updates.
     * 
     * Format: {"realtimeInput": {"video": {"data": ..., "mimeType": "image/jpeg"}}}
     * Analogous to audio: {"realtimeInput": {"audio": {"data": ..., "mimeType": "audio/pcm"}}}
     *
     * @param base64 Base64-encoded image data (NO_WRAP)
     * @param mime MIME type (e.g., "image/jpeg", "image/png")
     * @return true if sent successfully
     */
    fun sendGoogleMediaFrame(base64: String, mime: String): Boolean {
        return try {
            val payload = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("video", JSONObject().apply {
                        put("data", base64)
                        put("mimeType", mime)
                    })
                })
            }
            Log.d(TAG, "Sending Google video frame (${mime}, ${base64.length} chars)")
            send(payload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error creating Google video frame payload", e)
            false
        }
    }

    /**
     * Send audio chunk to Google Live API.
     * Uses 16kHz PCM16 mono format for optimal Pepper tablet performance.
     *
     * @param base64Audio Base64-encoded PCM16 audio data at 16kHz
     * @return true if sent successfully
     */
    fun sendGoogleAudioChunk(base64Audio: String): Boolean {
        return try {
            audioPayloadBuilder.setLength(0)
            audioPayloadBuilder.append(GOOGLE_AUDIO_PAYLOAD_PREFIX)
            audioPayloadBuilder.append(base64Audio)
            audioPayloadBuilder.append(GOOGLE_AUDIO_PAYLOAD_SUFFIX)
            
            send(audioPayloadBuilder.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error creating Google audio chunk payload", e)
            false
        }
    }

    /**
     * Send text message to Google Live API.
     * Uses clientContent format which allows control over whether a response is triggered.
     *
     * @param text Text message to send
     * @param triggerResponse If true, triggers model response; if false, silent context update
     * @return true if sent successfully
     */
    fun sendGoogleTextMessage(text: String, triggerResponse: Boolean = true): Boolean {
        return try {
            val turns = JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", text)
                }))
            })
            val payload = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", turns)
                    put("turnComplete", triggerResponse)
                })
            }
            Log.d(TAG, "Sending Google text message (triggerResponse=$triggerResponse): ${text.take(50)}...")
            send(payload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error creating Google text message", e)
            false
        }
    }

    /**
     * Send an image to Google Live API via clientContent.
     * Uses inlineData format for binary image data.
     *
     * @param base64 Base64-encoded image data
     * @param mime MIME type (e.g., "image/jpeg", "image/png")
     * @param turnComplete If true, triggers model response; if false, silent context update (may be unreliable)
     * @return true if sent successfully
     */
    fun sendGoogleImageMessage(base64: String, mime: String, turnComplete: Boolean = true): Boolean {
        return try {
            val payload = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().apply {
                            put("inlineData", JSONObject().apply {
                                put("mimeType", mime)
                                put("data", base64)
                            })
                        }))
                    }))
                    put("turnComplete", turnComplete)
                })
            }
            Log.d(TAG, "Sending Google image message (${mime}, ${base64.length} chars, turnComplete=$turnComplete)")
            send(payload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error creating Google image message", e)
            false
        }
    }

    /**
     * Send tool response to Google Live API.
     * After receiving toolCall.functionCalls, execute tools and send results here.
     *
     * @param callId The function call ID from the toolCall
     * @param toolName The name of the tool that was called
     * @param resultJson JSON string containing the tool result
     * @param scheduling Optional scheduling mode for NON_BLOCKING tools: "INTERRUPT", "WHEN_IDLE", or "SILENT"
     * @return true if sent successfully
     */
    fun sendGoogleToolResult(callId: String, toolName: String, resultJson: String, scheduling: String? = null): Boolean {
        return try {
            // Normalize tool response to the format used in the reference Live API app:
            // response: { success: boolean, result: <any json>, error?: string, scheduling?: string }
            val parsedResult = try {
                JSONObject(resultJson)
            } catch (_: Exception) {
                null
            }
            val success = parsedResult?.optBoolean("success", true) ?: true
            val normalizedResponse = JSONObject().apply {
                put("success", success)
                put("result", parsedResult ?: resultJson)
                if (!success) {
                    val err = parsedResult?.optString("error", "") ?: ""
                    if (err.isNotEmpty()) put("error", err)
                }
                // Add scheduling for NON_BLOCKING tools (e.g., SILENT for analyze_vision)
                if (scheduling != null) {
                    put("scheduling", scheduling)
                }
            }

            val payload = JSONObject().apply {
                put("toolResponse", JSONObject().apply {
                    put("functionResponses", JSONArray().put(JSONObject().apply {
                        put("id", callId)
                        put("name", toolName)
                        put("response", normalizedResponse)
                    }))
                })
            }
            Log.d(TAG, "Sending Google tool result for $toolName (call_id=$callId, scheduling=$scheduling)")
            send(payload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error creating Google tool result", e)
            false
        }
    }

    /**
     * Create Google Live API setup payload.
     * This should be the first message sent after WebSocket connection.
     */
    private fun createGoogleSetupPayload(): JSONObject {
        val settings = settingsRepository!!

        val payload = JSONObject()
        val setup = JSONObject()

        // Model
        setup.put("model", settings.model)

        // Generation config with audio output and voice
        val generationConfig = JSONObject().apply {
            put("responseModalities", JSONArray().put("AUDIO"))
            put("speechConfig", JSONObject().apply {
                put("voiceConfig", JSONObject().apply {
                    put("prebuiltVoiceConfig", JSONObject().apply {
                        put("voiceName", settings.voice)
                    })
                })
            })
        }
        setup.put("generationConfig", generationConfig)

        // Enable transcriptions (empty object = subscribe to events)
        // Note: inputAudioConfig/outputAudioConfig rejected by v1alpha API, skipping
        setup.put("inputAudioTranscription", JSONObject())   // User speech -> text
        setup.put("outputAudioTranscription", JSONObject())  // Model speech -> text

        // Realtime input config (VAD) - using settings from UI
        val startSensitivity = if (settings.googleStartSensitivity == "HIGH") 
            "START_SENSITIVITY_HIGH" else "START_SENSITIVITY_LOW"
        val endSensitivity = if (settings.googleEndSensitivity == "HIGH") 
            "END_SENSITIVITY_HIGH" else "END_SENSITIVITY_LOW"
        
        val realtimeInputConfig = JSONObject().apply {
            put("automaticActivityDetection", JSONObject().apply {
                put("disabled", false)
                put("startOfSpeechSensitivity", startSensitivity)
                put("endOfSpeechSensitivity", endSensitivity)
                put("prefixPaddingMs", settings.googlePrefixPaddingMs)
                put("silenceDurationMs", settings.googleSilenceDurationMs)
            })
        }
        setup.put("realtimeInputConfig", realtimeInputConfig)
        
        // Thinking budget - always set to control thinking behavior
        // 0 = disabled, >0 = token budget for thinking
        generationConfig.put("thinkingConfig", JSONObject().apply {
            put("thinkingBudget", settings.googleThinkingBudget)
        })
        
        // Affective dialog (emotional speech)
        // Note: enableAffectiveDialog is rejected by v1alpha API with "Unknown name"
        // The JS SDK may handle this differently. Disabled until API supports it.
        // if (settings.googleAffectiveDialog) {
        //     setup.put("enableAffectiveDialog", true)
        // }
        
        // Proactive audio - allows Gemini to proactively decide not to respond when content is not relevant
        if (settings.googleProactiveAudio) {
            setup.put("proactivity", JSONObject().apply {
                put("proactiveAudio", true)
            })
        }
        
        // Context window compression - enables unlimited session length via sliding window
        // Without compression: 15min audio-only, 2min audio+video
        // With compression: unlimited
        if (settings.googleContextCompression) {
            setup.put("contextWindowCompression", JSONObject().apply {
                put("slidingWindow", JSONObject()) // Default parameters (80% trigger)
            })
            Log.i(TAG, "Context window compression enabled for long sessions")
        }

        // System instruction
        val systemPrompt = settings.systemPrompt
        if (systemPrompt.isNotEmpty()) {
            setup.put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", systemPrompt)
                }))
            })
        }

        // Tools (using Google's format: google_search and/or functionDeclarations)
        val enabledTools = settings.enabledTools
        val googleTools = buildGoogleToolsDefinition(enabledTools)
        if (googleTools.length() > 0) {
            setup.put("tools", googleTools)
        }

        payload.put("setup", setup)

        // Log full payload for debugging (truncated if too long)
        val payloadStr = payload.toString()
        val preview = if (payloadStr.length > 1000) payloadStr.take(1000) + "...[truncated]" else payloadStr
        Log.i(TAG, "Created Google setup payload - Model: ${settings.model}, Voice: ${settings.voice}")
        Log.d(TAG, "Google setup payload: $preview")
        return payload
    }

    /**
     * Build tools definition in Google's format.
     * Includes functionDeclarations for custom tools and optional google_search for grounding.
     */
    private fun buildGoogleToolsDefinition(enabledTools: Set<String>): JSONArray {
        val toolsArray = JSONArray()
        val functionDeclarations = JSONArray()

        // Add Google Search grounding if enabled
        if (settingsRepository?.googleSearchGrounding == true) {
            toolsArray.put(JSONObject().apply {
                put("google_search", JSONObject())
            })
            Log.d(TAG, "Google Search grounding enabled")
        }

        if (toolRegistry == null || toolContext == null) {
            return toolsArray
        }

        for (toolName in toolRegistry!!.getAllToolNames()) {
            if (toolName !in enabledTools) continue

            val tool = toolRegistry!!.getOrCreateTool(toolName) ?: continue
            if (!tool.isAvailable(toolContext!!)) continue

            try {
                val openAiDef = tool.getDefinition()
                
                // Convert OpenAI format to Google format
                val googleDecl = JSONObject().apply {
                    put("name", openAiDef.optString("name", toolName))
                    put("description", openAiDef.optString("description", ""))
                    
                    // analyze_vision should be non-blocking so image can be sent with turnComplete=true
                    // and the tool response won't trigger a second response (uses scheduling=SILENT)
                    if (toolName == "analyze_vision") {
                        put("behavior", "NON_BLOCKING")
                    }
                    
                    // Parameters
                    val params = openAiDef.optJSONObject("parameters")
                    if (params != null) {
                        put("parameters", params)
                    }
                }
                functionDeclarations.put(googleDecl)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to convert tool definition for Google: $toolName", e)
            }
        }

        if (functionDeclarations.length() > 0) {
            toolsArray.put(JSONObject().apply {
                put("functionDeclarations", functionDeclarations)
            })
        }

        Log.d(TAG, "Built Google tools: ${if (settingsRepository?.googleSearchGrounding == true) "google_search + " else ""}${functionDeclarations.length()} function declarations")
        return toolsArray
    }

    fun send(text: String): Boolean {
        // Check connection state first for better diagnostics
        if (connectionState != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot send - connection state: $connectionState")
            return false
        }

        val ws = webSocket
        if (ws == null) {
            // This shouldn't happen if state tracking is correct, but handle it defensively
            Log.w(TAG, "Cannot send - webSocket is null despite CONNECTED state (race condition?)")
            connectionState = ConnectionState.DISCONNECTED
            return false
        }

        return try {
            val result = ws.send(text)
            if (!result) {
                Log.w(TAG, "WebSocket.send() returned false - connection may be broken")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "WebSocket.send() threw exception", e)
            false
        }
    }

    fun close(code: Int, reason: String?) {
        if (connectionState == ConnectionState.DISCONNECTED) {
            Log.d(TAG, "close() called but already disconnected")
            return
        }

        connectionState = ConnectionState.CLOSING
        Log.d(TAG, "Connection state: CLOSING (explicit close, code=$code)")

        try {
            webSocket?.close(code, reason)
        } catch (_: Exception) {
            // Ignore exceptions during close
        }
        // Note: webSocket will be set to null in onClosed callback
        // Set it here too for immediate state consistency
        webSocket = null
        connectionState = ConnectionState.DISCONNECTED
    }

    /**
     * Configure initial session with tools, voice, temperature, and instructions
     * This should be called when WebSocket connection is established
     */
    fun configureInitialSession() {
        if (!isConnected) {
            Log.w(TAG, "Session config SKIPPED - connection state: $connectionState")
            sessionConfigCallback?.onSessionConfigured(false, "Not connected (state: $connectionState)")
            return
        }

        if (settingsRepository == null || toolRegistry == null || toolContext == null) {
            Log.w(TAG, "Session config SKIPPED - missing dependencies")
            sessionConfigCallback?.onSessionConfigured(false, "Missing dependencies")
            return
        }

        try {
            // Use different payload format for Google vs OpenAI/x.ai
            val isGoogle = settingsRepository!!.apiProviderEnum.isGoogleProvider()
            val payload = if (isGoogle) {
                createGoogleSetupPayload()
            } else {
                createSessionUpdatePayload("Initial session")
            }
            val sent = send(payload.toString())

            if (sent) {
                // For Google, we must wait for setupComplete event before session is ready
                // For OpenAI/Azure/x.ai, the session.update response confirms it
                if (!isGoogle) {
                    sessionConfigCallback?.onSessionConfigured(true, null)
                } else {
                    Log.d(TAG, "Google setup sent - waiting for setupComplete event")
                }
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
     * 
     * Note: Google Live API does not support mid-session updates.
     * For Google, settings changes require reconnecting.
     */
    fun updateSession() {
        if (!isConnected) {
            Log.w(TAG, "Session update SKIPPED - connection state: $connectionState")
            return
        }

        if (settingsRepository == null || toolRegistry == null || toolContext == null) {
            Log.w(TAG, "Session update SKIPPED - missing dependencies")
            return
        }

        // Google Live API doesn't support session.update - need to reconnect
        if (settingsRepository!!.apiProviderEnum.isGoogleProvider()) {
            Log.w(TAG, "Session update SKIPPED - Google Live API requires reconnection for config changes")
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

        // x.ai Grok Voice Agent API - uses similar structure to OpenAI GA API
        if (settings.apiProviderEnum == RealtimeApiProvider.XAI) {
            // x.ai audio configuration
            val audio = JSONObject()

            // Input format
            val input = JSONObject().apply {
                put("format", JSONObject().apply {
                    put("type", "audio/pcm")
                    put("rate", 24000)
                })
            }
            audio.put("input", input)

            // Output format  
            val output = JSONObject().apply {
                put("format", JSONObject().apply {
                    put("type", "audio/pcm")
                    put("rate", 24000)
                })
            }
            audio.put("output", output)

            sessionConfig.put("audio", audio)

            // x.ai voice: Use directly if it's an x.ai voice, otherwise map from OpenAI names
            val xaiVoices = setOf("Ara", "Rex", "Sal", "Eve", "Leo")
            val xaiVoice = if (voice in xaiVoices) {
                voice // Already an x.ai voice
            } else {
                // Fallback mapping from OpenAI voice names (for backwards compatibility)
                when (voice.lowercase()) {
                    "alloy", "ash" -> "Ara"
                    "echo", "ballad" -> "Rex"
                    "shimmer", "coral" -> "Eve"
                    "verse" -> "Sal"
                    "sage" -> "Leo"
                    else -> "Ara"
                }
            }
            sessionConfig.put("voice", xaiVoice)

            // Turn detection for x.ai (server VAD)
            if (settings.isUsingRealtimeAudioInput) {
                sessionConfig.put("turn_detection", JSONObject().apply {
                    put("type", "server_vad")
                })
            }

            Log.i(TAG, "x.ai config - Voice: $xaiVoice")
        }
        // For OpenAI Direct with gpt-realtime, use GA API structure
        else if (model == "gpt-realtime" && settings.apiProviderEnum == RealtimeApiProvider.OPENAI_DIRECT) {
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

        // Build tools array
        val toolsArray = toolRegistry!!.buildToolsDefinitionForAzure(toolContext!!, enabledTools)
        
        // Add x.ai native tools (web_search and x_search) if enabled
        if (settings.apiProviderEnum == RealtimeApiProvider.XAI) {
            val xaiToolsAdded = mutableListOf<String>()
            
            if (settings.xaiWebSearch) {
                toolsArray.put(JSONObject().apply {
                    put("type", "web_search")
                })
                xaiToolsAdded.add("web_search")
            }
            
            if (settings.xaiXSearch) {
                toolsArray.put(JSONObject().apply {
                    put("type", "x_search")
                })
                xaiToolsAdded.add("x_search")
            }
            
            if (xaiToolsAdded.isNotEmpty()) {
                Log.i(TAG, "x.ai: Added native tools: ${xaiToolsAdded.joinToString(", ")}")
            }
        }
        
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


