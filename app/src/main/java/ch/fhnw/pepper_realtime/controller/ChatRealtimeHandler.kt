package ch.fhnw.pepper_realtime.controller

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.JsonObject
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.di.ApplicationScope
import ch.fhnw.pepper_realtime.di.IoDispatcher
import ch.fhnw.pepper_realtime.manager.AudioPlayer
import ch.fhnw.pepper_realtime.manager.TurnManager
import ch.fhnw.pepper_realtime.network.GoogleLiveEvents
import ch.fhnw.pepper_realtime.network.RealtimeEventHandler
import ch.fhnw.pepper_realtime.network.RealtimeEvents
import ch.fhnw.pepper_realtime.network.RealtimeSessionManager
import ch.fhnw.pepper_realtime.tools.ToolContext
import ch.fhnw.pepper_realtime.tools.ToolRegistry
import ch.fhnw.pepper_realtime.ui.ChatMessage
import ch.fhnw.pepper_realtime.ui.ChatViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ChatRealtimeHandler(
    private val viewModel: ChatViewModel,
    private val audioPlayer: AudioPlayer,
    private val turnManager: TurnManager?,
    private val ioDispatcher: CoroutineDispatcher,
    private val applicationScope: CoroutineScope,
    private val toolRegistry: ToolRegistry,
    private var toolContext: ToolContext?,
    private val sessionManager: RealtimeSessionManager,
    private val settingsRepository: ch.fhnw.pepper_realtime.manager.SettingsRepository
) : RealtimeEventHandler.Listener {

    companion object {
        private const val TAG = "ChatRealtimeHandler"
    }

    var sessionController: ChatSessionController? = null

    // Track pending Google tool calls to cancel them if interrupted
    private val pendingGoogleToolCalls = mutableSetOf<String>()
    
    // Google Live API: counter to generate unique turn IDs
    private var googleTurnCounter = 0

    fun setToolContext(context: ToolContext?) {
        this.toolContext = context
    }

    override fun onSessionUpdated(event: RealtimeEvents.SessionUpdated) {
        Log.i(TAG, "Session configured successfully - completing connection promise.")

        if (viewModel.isWarmingUp.value != true) {
            // Status update removed to prevent "Ready" state flickering before "Listening"
        }
        sessionController?.completeConnectionPromise()
            ?: Log.i(TAG, "SessionController null, cannot complete promise explicitly")
    }

    override fun onAudioTranscriptDelta(delta: String?, responseId: String?) {
        // For Google Live API, responseId is null - use a synthetic ID based on turn counter
        val effectiveResponseId = responseId ?: getGoogleTurnId()
        
        if (responseId != null && responseId == viewModel.cancelledResponseId) {
            return // drop transcript of cancelled response (only for non-Google)
        }

        // Debug: Log first delta to verify transcript is being processed
        val isFirstDelta = effectiveResponseId != viewModel.lastChatBubbleResponseId
        if (isFirstDelta) {
            Log.d(TAG, "First transcript delta received for response: $effectiveResponseId, expectingFinalAnswer=${viewModel.isExpectingFinalAnswerAfterToolCall}")
            // Set status to Speaking (without transcript)
            val speakingText = viewModel.getApplication<android.app.Application>().getString(R.string.status_speaking)
            viewModel.setStatusText(speakingText)
        }

        // Check if we need a new bubble for the final answer after a tool call
        // This must be checked BEFORE the early return for same responseId
        if (viewModel.isExpectingFinalAnswerAfterToolCall) {
            Log.d(TAG, "Creating new chat bubble for final answer after tool call")
            viewModel.addMessage(ChatMessage(delta ?: "", ChatMessage.Sender.ROBOT))
            viewModel.isExpectingFinalAnswerAfterToolCall = false
            viewModel.lastChatBubbleResponseId = effectiveResponseId
            return
        }

        // Fix for double bubble creation:
        // Check if we are already handling this response ID.
        // If so, append to the last message.
        if (effectiveResponseId == viewModel.lastChatBubbleResponseId) {
            viewModel.appendToLastRobotMessage(delta ?: "")
            return
        }

        // Need new bubble for new response or first message
        val needNew = isMessageListEmpty()
                || !isLastMessageFromRobot()
                || effectiveResponseId != viewModel.lastChatBubbleResponseId

        if (needNew) {
            Log.d(TAG, "Creating new chat bubble for transcript (needNew=true, responseId=$effectiveResponseId)")
            viewModel.addMessage(ChatMessage(delta ?: "", ChatMessage.Sender.ROBOT))
            viewModel.lastChatBubbleResponseId = effectiveResponseId
        } else {
            viewModel.appendToLastRobotMessage(delta ?: "")
        }
    }

    override fun onAudioTranscriptDone(transcript: String?, responseId: String?) {
        if (responseId == viewModel.cancelledResponseId) {
            return
        }
        
        // Status bar update removed - transcript is in bubble

        // Update chat bubble with complete transcript
        if (isLastMessageFromRobot() && responseId == viewModel.lastChatBubbleResponseId) {
            viewModel.updateLastRobotMessage(transcript ?: "")
        }
    }

    private fun isMessageListEmpty(): Boolean {
        val list = viewModel.messageList.value
        return list.isNullOrEmpty()
    }

    private fun isLastMessageFromRobot(): Boolean {
        val list = viewModel.messageList.value
        if (list.isNullOrEmpty()) return false
        return list.last().sender == ChatMessage.Sender.ROBOT
    }

    override fun onAudioDelta(pcm16: ByteArray, responseId: String?) {
        // For Google Live API, responseId is null - use a synthetic ID based on turn counter
        val effectiveResponseId = responseId ?: getGoogleTurnId()
        
        // Ignore audio from cancelled response (only for non-Google with actual responseId)
        if (responseId != null && responseId == viewModel.cancelledResponseId) {
            return
        }
        
        // Google Live API: Ignore audio after manual interrupt until next turn
        // This prevents continued playback after user taps interrupt button
        if (responseId == null && viewModel.ignoreGoogleAudioUntilNextTurn.value) {
            Log.d(TAG, "Ignoring Google audio chunk (manual interrupt active)")
            return
        }

        // Note: State transition to SPEAKING is handled by AudioPlayer.Listener.onPlaybackStarted()
        // when playback actually begins, not here when audio chunks arrive
        if (viewModel.currentResponseId != effectiveResponseId) {
            try {
                audioPlayer.onResponseBoundary()
            } catch (_: Exception) {
            }
            viewModel.currentResponseId = effectiveResponseId
        }

        viewModel.setAudioPlaying(true) // Audio chunks are being played
        audioPlayer.addChunk(pcm16)
        audioPlayer.startIfNeeded()
    }

    override fun onAudioDone() {
        audioPlayer.markResponseDone()
    }

    override fun onResponseDone(event: RealtimeEvents.ResponseDone) {
        viewModel.setResponseGenerating(false) // API finished generating
        Log.i(TAG, "Full response received. Processing final output.")
        try {
            if (event.response?.output.isNullOrEmpty()) {
                Log.i(TAG, "Response.done with no output. Finishing turn and returning to LISTENING.")
                if (turnManager != null && !audioPlayer.isPlaying()) {
                    turnManager.setState(TurnManager.State.LISTENING)
                }
                return
            }

            val functionCalls = mutableListOf<RealtimeEvents.Item>()
            val messageItems = mutableListOf<RealtimeEvents.Item>()

            for (item in event.response!!.output!!) {
                when (item.type) {
                    "function_call" -> functionCalls.add(item)
                    "message" -> messageItems.add(item)
                }
            }

            if (functionCalls.isNotEmpty()) {
                // x.ai server-side tools that are executed by the API, not locally
                val serverSideTools = setOf("web_search", "web_search_with_snippets", "x_search", "x_keyword_search", "x_user_search", "file_search")
                
                for (fc in functionCalls) {
                    val toolName = fc.name
                    val callId = fc.callId
                    val argsString = fc.arguments

                    // Convert to JSONObject for ToolRegistry compatibility
                    val args = JSONObject(argsString ?: "{}")

                    // Always show function call card in UI
                    val functionCall = ChatMessage.createFunctionCall(toolName.orEmpty(), args.toString(), ChatMessage.Sender.ROBOT)
                    viewModel.addMessage(functionCall)

                    // Check if this is a server-side tool (executed by x.ai, not locally)
                    if (toolName in serverSideTools) {
                        Log.i(TAG, "Server-side tool '$toolName' - handled by API, no local execution needed")
                        // Update UI to show it's being handled server-side
                        Handler(Looper.getMainLooper()).post {
                            viewModel.updateLatestFunctionCallResult("{\"status\":\"Executed by server\"}")
                        }
                        // Do NOT send result back - server handles this
                        continue
                    }

                    viewModel.isExpectingFinalAnswerAfterToolCall = true
                    
                    applicationScope.launch(ioDispatcher) {
                        val toolResult: String = try {
                            toolRegistry.executeTool(toolName.orEmpty(), args, toolContext!!)
                                ?: "{\"error\":\"Tool returned no result.\"}"
                        } catch (toolEx: Exception) {
                            Log.e(TAG, "Tool execution crashed for $toolName", toolEx)
                            try {
                                JSONObject()
                                    .put("error", "Tool execution failed: ${toolEx.message ?: "Unknown error"}")
                                    .toString()
                            } catch (_: Exception) {
                                "{\"error\":\"Tool execution failed.\"}"
                            }
                        }

                        val fResult = toolResult
                        // Update UI on main thread to ensure RecyclerView updates even with overlays
                        Handler(Looper.getMainLooper()).post {
                            viewModel.updateLatestFunctionCallResult(fResult)
                        }
                        sessionController?.sendToolResult(callId ?: "", fResult)
                            ?: Log.e(TAG, "SessionController is null, cannot send tool result")
                    }
                }
            }

            if (messageItems.isNotEmpty()) {
                Log.d(TAG, "Final assistant message added to local history.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing response.done message. Attempting to recover.", e)
        }
    }

    override fun onAssistantItemAdded(itemId: String?) {
        try {
            viewModel.lastAssistantItemId = itemId
        } catch (_: Exception) {
        }
    }

    override fun onResponseBoundary(newResponseId: String?) {
        try {
            Log.d(TAG, "Response boundary detected - new ID: $newResponseId, previous ID: ${viewModel.currentResponseId}")
            audioPlayer.onResponseBoundary()
        } catch (e: Exception) {
            Log.w(TAG, "Error during response boundary reset", e)
        }
    }

    override fun onResponseCreated(responseId: String?) {
        try {
            viewModel.setResponseGenerating(true) // API started generating
            Log.d(TAG, "New response created with ID: $responseId")
        } catch (_: Exception) {
        }
    }

    override fun onError(error: RealtimeEvents.ErrorEvent) {
        val code = error.error?.code ?: "Unknown"
        val msg = error.error?.message ?: "An unknown error occurred."

        // Handle harmless errors that can occur during normal operation
        if (code == "response_cancel_not_active") {
            // This happens when trying to cancel a response that already finished - harmless race condition
            Log.d(TAG, "Interrupt race condition (harmless): $msg")
            return
        }
        if (code == "invalid_value" && msg.contains("already shorter than")) {
            // This happens when truncating audio that's shorter than the truncate position - harmless
            Log.d(TAG, "Truncate position beyond audio length (harmless): $msg")
            return
        }

        Log.e(TAG, "WebSocket Error Received - Code: $code, Message: $msg")

        viewModel.addMessage(
            ChatMessage(
                viewModel.getApplication<android.app.Application>().getString(R.string.server_error_prefix, msg),
                ChatMessage.Sender.ROBOT
            )
        )
        viewModel.setStatusText(viewModel.getApplication<android.app.Application>().getString(R.string.error_generic, code))

        sessionController?.failConnectionPromise("Server returned an error during setup: $msg")
            ?: Log.e(TAG, "SessionController null, cannot fail promise explicitly")
    }

    override fun onUserSpeechStarted(itemId: String?) {
        viewModel.setStatusText(viewModel.getApplication<android.app.Application>().getString(R.string.status_listening))
        Log.d(TAG, "User speech started (Realtime API): $itemId")

        val placeholder = ChatMessage("...", ChatMessage.Sender.USER)
        placeholder.itemId = itemId
        Log.d(TAG, "Created placeholder with itemId: $itemId, UUID: ${placeholder.uuid}")
        viewModel.addMessage(placeholder)
    }

    override fun onUserSpeechStopped(itemId: String?) {
        // No action needed
    }

    override fun onAudioBufferCommitted(itemId: String?) {
        // Server has accepted the audio input and will generate a response
        // Transition to THINKING state (equivalent to after Azure speech recognition sends transcript)
        if (turnManager != null && turnManager.state == TurnManager.State.LISTENING) {
            turnManager.setState(TurnManager.State.THINKING)
        }
    }

    override fun onUserTranscriptCompleted(itemId: String?, transcript: String?) {
        if (!transcript.isNullOrEmpty()) {
            // For Google Live API (itemId is null): append to last user message or create new
            if (itemId == null) {
                val list = viewModel.messageList.value
                val lastMessage = list?.lastOrNull()
                
                if (lastMessage != null && lastMessage.sender == ChatMessage.Sender.USER) {
                    // Append to existing user message
                    viewModel.appendToLastUserMessage(transcript)
                } else {
                    // Create new user message
                    val msg = ChatMessage(transcript, ChatMessage.Sender.USER)
                    viewModel.addMessage(msg)
                }
                return
            }
            
            // For OpenAI/Azure (has itemId): use placeholder system
            val updated = viewModel.updateMessageByItemId(itemId, transcript)

            if (!updated) {
                Log.w(TAG, "No placeholder found for item $itemId, adding new message")
                val msg = ChatMessage(transcript, ChatMessage.Sender.USER)
                msg.itemId = itemId
                viewModel.addMessage(msg)
            }
        }
    }

    override fun onUserTranscriptFailed(itemId: String?, event: RealtimeEvents.UserTranscriptFailed) {
        val msg = event.error?.message ?: "Unknown error"
        Log.w(TAG, "User transcript failed: $msg")

        viewModel.updateMessageByItemId(itemId, "(Transcription failed)")
    }

    override fun onUnknown(type: String, raw: JsonObject?) {
        Log.w(TAG, "Unknown WebSocket message type: $type")
    }

    override fun onUserItemCreated(itemId: String?, event: RealtimeEvents.ConversationItemCreated) {
        // Default: no action needed
    }

    // ==================== GOOGLE LIVE API EVENT HANDLERS ====================

    override fun onGoogleSetupComplete() {
        Log.i(TAG, "Google Live API setup complete")
        // Reset interrupt flag for fresh session
        viewModel.setIgnoreGoogleAudio(false)
        // Notify session manager that Google setup is confirmed
        sessionManager.onGoogleSetupComplete()
    }

    override fun onGoogleModelTurnStarted() {
        // First modelTurn received - Google has started responding
        // Increment turn counter for new response ID
        googleTurnCounter++
        
        // Clear the manual interrupt flag - new turn means user triggered new response
        viewModel.setIgnoreGoogleAudio(false)
        
        // Reset thinking bubble tracking for new turn
        currentThinkingBubbleId = null
        
        // Transition to THINKING state (this mutes the microphone)
        if (turnManager != null && turnManager.state == TurnManager.State.LISTENING) {
            turnManager.setState(TurnManager.State.THINKING)
        }
    }
    
    // Helper to get current Google turn ID
    private fun getGoogleTurnId(): String = "google_turn_$googleTurnCounter"

    override fun onGoogleInterrupted() {
        Log.i(TAG, "Google: Barge-in detected - stopping audio playback")
        audioPlayer.interruptNow()
        viewModel.setAudioPlaying(false)
        
        // Clear pending tool calls on interrupt
        pendingGoogleToolCalls.clear()
        
        // Clear the manual interrupt flag - server confirmed interruption
        viewModel.setIgnoreGoogleAudio(false)
    }

    override fun onGoogleToolCall(functionCalls: List<GoogleLiveEvents.FunctionCall>) {
        Log.i(TAG, "Google: Processing ${functionCalls.size} tool call(s)")
        
        for (fc in functionCalls) {
            val toolName = fc.name ?: continue
            val callId = fc.id ?: continue
            
            // Track pending tool call
            pendingGoogleToolCalls.add(callId)
            
            // Convert args map to JSONObject (handling nested objects and arrays)
            val args = convertToJsonObject(fc.args ?: emptyMap())

            Log.i(TAG, "Google tool call: $toolName (id=$callId), args=$args")

            // Show function call card in UI
            val functionCall = ChatMessage.createFunctionCall(toolName, args.toString(), ChatMessage.Sender.ROBOT)
            viewModel.addMessage(functionCall)

            viewModel.isExpectingFinalAnswerAfterToolCall = true

            applicationScope.launch(ioDispatcher) {
                val toolResult: String = try {
                    toolRegistry.executeTool(toolName, args, toolContext!!)
                        ?: "{\"error\":\"Tool returned no result.\"}"
                } catch (toolEx: Exception) {
                    Log.e(TAG, "Google tool execution crashed for $toolName", toolEx)
                    try {
                        JSONObject()
                            .put("error", "Tool execution failed: ${toolEx.message ?: "Unknown error"}")
                            .toString()
                    } catch (_: Exception) {
                        "{\"error\":\"Tool execution failed.\"}"
                    }
                }

                // Check if this call was cancelled while executing
                if (callId !in pendingGoogleToolCalls) {
                    Log.w(TAG, "Google tool call $callId was cancelled, not sending result")
                    return@launch
                }
                pendingGoogleToolCalls.remove(callId)

                // Update UI on main thread
                Handler(Looper.getMainLooper()).post {
                    viewModel.updateLatestFunctionCallResult(toolResult)
                }

                // Send result back to Google
                // Note: Google Live API continues generation automatically after receiving toolResponse
                // For analyze_vision, use SILENT scheduling to avoid double response (image triggers response via turnComplete=true)
                val scheduling = if (toolName == "analyze_vision") "SILENT" else null
                val sent = sessionManager.sendGoogleToolResult(callId, toolName, toolResult, scheduling)
                if (!sent) {
                    Log.e(TAG, "Failed to send Google tool result for $toolName")
                }
            }
        }
    }

    override fun onGoogleToolCallCancellation(ids: List<String>) {
        Log.i(TAG, "Google: Tool call cancellation for ${ids.size} call(s)")
        ids.forEach { id ->
            pendingGoogleToolCalls.remove(id)
        }
    }

    // Track if we have an active thinking bubble to append to
    private var currentThinkingBubbleId: String? = null

    override fun onGoogleThinkingDelta(text: String) {
        // Only show thinking traces if enabled in settings
        if (!settingsRepository.googleShowThinking) return
        
        // Skip empty or whitespace-only chunks
        if (text.isBlank()) return
        
        // Clean up text: trim and normalize multiple newlines to single ones
        val cleanText = text.trim()
            .replace(Regex("\\n{3,}"), "\n\n")  // Max 2 consecutive newlines
            .trimEnd()
        if (cleanText.isEmpty()) return
        
        // Note: Google sends thinking traces as single chunks, not streaming word-by-word
        // Check if we already have a thinking bubble to append to
        if (currentThinkingBubbleId != null) {
            // Append to existing thinking bubble
            viewModel.appendToThinkingMessage(cleanText)
        } else {
            // Create new thinking bubble with emoji prefix
            val thinkingMessage = ChatMessage.createThinking("💭 $cleanText")
            currentThinkingBubbleId = thinkingMessage.uuid
            viewModel.addMessage(thinkingMessage)
        }
    }

    /**
     * Convert a Map<String, Any> to JSONObject, properly handling nested objects and arrays.
     * Gson deserializes JSON arrays as ArrayList and objects as LinkedTreeMap.
     */
    private fun convertToJsonObject(map: Map<String, Any?>): JSONObject {
        val json = JSONObject()
        for ((key, value) in map) {
            json.put(key, convertToJsonValue(value))
        }
        return json
    }

    private fun convertToJsonValue(value: Any?): Any? {
        return when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                convertToJsonObject(value as Map<String, Any?>)
            }
            is List<*> -> {
                val jsonArray = JSONArray()
                for (item in value) {
                    jsonArray.put(convertToJsonValue(item))
                }
                jsonArray
            }
            is Number, is String, is Boolean -> value
            else -> value.toString()
        }
    }
}
