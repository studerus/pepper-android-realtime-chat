package io.github.anonymous.pepper_realtime.controller

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.JsonObject
import io.github.anonymous.pepper_realtime.R
import io.github.anonymous.pepper_realtime.di.ApplicationScope
import io.github.anonymous.pepper_realtime.di.IoDispatcher
import io.github.anonymous.pepper_realtime.manager.AudioPlayer
import io.github.anonymous.pepper_realtime.manager.TurnManager
import io.github.anonymous.pepper_realtime.network.RealtimeEventHandler
import io.github.anonymous.pepper_realtime.network.RealtimeEvents
import io.github.anonymous.pepper_realtime.tools.ToolContext
import io.github.anonymous.pepper_realtime.tools.ToolRegistry
import io.github.anonymous.pepper_realtime.ui.ChatMessage
import io.github.anonymous.pepper_realtime.ui.ChatViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

class ChatRealtimeHandler(
    private val viewModel: ChatViewModel,
    private val audioPlayer: AudioPlayer,
    private val turnManager: TurnManager?,
    private val ioDispatcher: CoroutineDispatcher,
    private val applicationScope: CoroutineScope,
    private val toolRegistry: ToolRegistry,
    private var toolContext: ToolContext?
) : RealtimeEventHandler.Listener {

    companion object {
        private const val TAG = "ChatRealtimeHandler"
    }

    var sessionController: ChatSessionController? = null

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
        if (responseId == viewModel.cancelledResponseId) {
            return // drop transcript of cancelled response
        }

        // Debug: Log first delta to verify transcript is being processed
        val isFirstDelta = responseId != viewModel.lastChatBubbleResponseId
        if (isFirstDelta) {
            Log.d(TAG, "First transcript delta received for response: $responseId, expectingFinalAnswer=${viewModel.isExpectingFinalAnswerAfterToolCall}")
        }

        // Update status bar with streaming transcript (like LISTENING shows partial recognition)
        val speakingPrefix = viewModel.getApplication<android.app.Application>().getString(R.string.status_speaking_tap_to_interrupt) + " "
        val currentStatus = viewModel.statusText.value
        if (currentStatus == null || !currentStatus.startsWith(speakingPrefix)) {
            // First delta - set initial status with this delta
            viewModel.setStatusText(speakingPrefix + (delta ?: ""))
        } else {
            // Subsequent deltas - append to existing status
            viewModel.setStatusText(currentStatus + (delta ?: ""))
        }

        val needNew = viewModel.isExpectingFinalAnswerAfterToolCall
                || isMessageListEmpty()
                || !isLastMessageFromRobot()
                || responseId != viewModel.lastChatBubbleResponseId
        if (needNew) {
            Log.d(TAG, "Creating new chat bubble for transcript (needNew=true)")
            viewModel.addMessage(ChatMessage(delta ?: "", ChatMessage.Sender.ROBOT))
            viewModel.isExpectingFinalAnswerAfterToolCall = false
            viewModel.lastChatBubbleResponseId = responseId
        } else {
            viewModel.appendToLastMessage(delta ?: "")
        }
    }

    override fun onAudioTranscriptDone(transcript: String?, responseId: String?) {
        if (responseId == viewModel.cancelledResponseId) {
            return
        }
        // Update status bar with complete transcript
        val speakingPrefix = viewModel.getApplication<android.app.Application>().getString(R.string.status_speaking_tap_to_interrupt) + " "
        viewModel.setStatusText(speakingPrefix + (transcript ?: ""))

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
        // Ignore audio from cancelled response
        if (responseId == viewModel.cancelledResponseId) {
            return
        }

        // Note: State transition to SPEAKING is handled by AudioPlayer.Listener.onPlaybackStarted()
        // when playback actually begins, not here when audio chunks arrive
        if (responseId != null) {
            if (viewModel.currentResponseId != responseId) {
                try {
                    audioPlayer.onResponseBoundary()
                } catch (_: Exception) {
                }
                viewModel.currentResponseId = responseId
            }
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
                for (fc in functionCalls) {
                    val toolName = fc.name
                    val callId = fc.callId
                    val argsString = fc.arguments

                    // Convert to JSONObject for ToolRegistry compatibility
                    val args = JSONObject(argsString)

                    val functionCall = ChatMessage.createFunctionCall(toolName ?: "", args.toString(), ChatMessage.Sender.ROBOT)
                    viewModel.addMessage(functionCall)

                    viewModel.isExpectingFinalAnswerAfterToolCall = true
                    
                    applicationScope.launch(ioDispatcher) {
                        var toolResult: String?
                        try {
                            toolResult = toolRegistry.executeTool(toolName ?: "", args, toolContext!!)
                        } catch (toolEx: Exception) {
                            Log.e(TAG, "Tool execution crashed for $toolName", toolEx)
                            toolResult = try {
                                JSONObject()
                                    .put("error", "Tool execution failed: ${toolEx.message ?: "Unknown error"}")
                                    .toString()
                            } catch (_: Exception) {
                                "{\"error\":\"Tool execution failed.\"}"
                            }
                        }

                        if (toolResult == null) {
                            toolResult = "{\"error\":\"Tool returned no result.\"}"
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
        Log.d(TAG, "User speech stopped: $itemId")
    }

    override fun onAudioBufferCommitted(itemId: String?) {
        // Server has accepted the audio input and will generate a response
        // Transition to THINKING state (equivalent to after Azure speech recognition sends transcript)
        Log.d(TAG, "Audio buffer committed: $itemId - entering THINKING state")
        if (turnManager != null && turnManager.state == TurnManager.State.LISTENING) {
            turnManager.setState(TurnManager.State.THINKING)
        }
    }

    override fun onUserTranscriptCompleted(itemId: String?, transcript: String?) {
        if (!transcript.isNullOrEmpty()) {
            Log.d(TAG, "Attempting to update message with itemId: $itemId, transcript: $transcript")
            val updated = viewModel.updateMessageByItemId(itemId, transcript)

            if (!updated) {
                Log.w(TAG, "No placeholder found for item $itemId, adding new message")
                val msg = ChatMessage(transcript, ChatMessage.Sender.USER)
                msg.itemId = itemId
                viewModel.addMessage(msg)
            } else {
                Log.d(TAG, "Successfully updated placeholder for itemId: $itemId")
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
}
