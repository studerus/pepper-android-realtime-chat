package io.github.anonymous.pepper_realtime.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.anonymous.pepper_realtime.controller.ChatSessionController
import io.github.anonymous.pepper_realtime.data.ResponseState
import io.github.anonymous.pepper_realtime.network.WebSocketConnectionCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    // State using StateFlow - thread-safe, no manual locking needed
    private val _isWarmingUp = MutableStateFlow(false)
    private val _isResponseGenerating = MutableStateFlow(false)
    private val _isAudioPlaying = MutableStateFlow(false)
    private val _statusText = MutableStateFlow("")
    private val _messageList = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _isMuted = MutableStateFlow(false)
    private val _isInterruptFabVisible = MutableStateFlow(false)

    // Connection State
    private val _isConnected = MutableStateFlow(false)

    // Navigation State
    private val _mapStatus = MutableStateFlow("")
    private val _localizationStatus = MutableStateFlow("")

    // Internal Response State - atomic updates replace @Volatile variables
    private val _responseState = MutableStateFlow(ResponseState())

    // Public read-only StateFlows
    val isWarmingUp: StateFlow<Boolean> = _isWarmingUp.asStateFlow()
    val isResponseGenerating: StateFlow<Boolean> = _isResponseGenerating.asStateFlow()
    val isAudioPlaying: StateFlow<Boolean> = _isAudioPlaying.asStateFlow()
    val statusText: StateFlow<String> = _statusText.asStateFlow()
    val messageList: StateFlow<List<ChatMessage>> = _messageList.asStateFlow()
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()
    val isInterruptFabVisible: StateFlow<Boolean> = _isInterruptFabVisible.asStateFlow()
    val mapStatus: StateFlow<String> = _mapStatus.asStateFlow()
    val localizationStatus: StateFlow<String> = _localizationStatus.asStateFlow()

    // Response state accessors (for compatibility with existing code)
    var currentResponseId: String?
        get() = _responseState.value.currentResponseId
        set(value) = _responseState.update { it.copy(currentResponseId = value) }

    var cancelledResponseId: String?
        get() = _responseState.value.cancelledResponseId
        set(value) = _responseState.update { it.copy(cancelledResponseId = value) }

    var lastChatBubbleResponseId: String?
        get() = _responseState.value.lastChatBubbleResponseId
        set(value) = _responseState.update { it.copy(lastChatBubbleResponseId = value) }

    var isExpectingFinalAnswerAfterToolCall: Boolean
        get() = _responseState.value.isExpectingFinalAnswerAfterToolCall
        set(value) = _responseState.update { it.copy(isExpectingFinalAnswerAfterToolCall = value) }

    var lastAssistantItemId: String?
        get() = _responseState.value.lastAssistantItemId
        set(value) = _responseState.update { it.copy(lastAssistantItemId = value) }

    // Setters - StateFlow.value is thread-safe
    fun setWarmingUp(warmingUp: Boolean) {
        _isWarmingUp.value = warmingUp
    }

    fun setResponseGenerating(generating: Boolean) {
        _isResponseGenerating.value = generating
    }

    fun setAudioPlaying(playing: Boolean) {
        _isAudioPlaying.value = playing
    }

    fun setStatusText(text: String) {
        _statusText.value = text
    }

    fun setConnected(connected: Boolean) {
        _isConnected.value = connected
    }

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
    }

    fun setInterruptFabVisible(visible: Boolean) {
        _isInterruptFabVisible.value = visible
    }

    fun setMapStatus(status: String) {
        _mapStatus.value = status
    }

    fun setLocalizationStatus(status: String) {
        _localizationStatus.value = status
    }

    // Message Management - using StateFlow.update for atomic operations
    fun addMessage(message: ChatMessage) {
        _messageList.update { current -> current + message }
    }

    fun addImageMessage(imagePath: String) {
        // Image is captured by robot camera, so it's a ROBOT output (displayed on the left with thumbnail)
        addMessage(ChatMessage("", imagePath, ChatMessage.Sender.ROBOT))
    }

    fun appendToLastMessage(text: String) {
        _messageList.update { current ->
            if (current.isEmpty()) return@update current

            val lastIndex = current.size - 1
            val lastMsg = current[lastIndex]

            if (lastMsg.sender == ChatMessage.Sender.ROBOT &&
                lastMsg.type == ChatMessage.Type.REGULAR_MESSAGE
            ) {
                current.toMutableList().apply {
                    this[lastIndex] = lastMsg.copyWithNewText(lastMsg.message + text)
                }
            } else {
                current
            }
        }
    }

    fun updateLastRobotMessage(newText: String) {
        _messageList.update { current ->
            if (current.isEmpty()) return@update current

            val lastIndex = current.size - 1
            val lastMsg = current[lastIndex]

            if (lastMsg.sender == ChatMessage.Sender.ROBOT &&
                lastMsg.type == ChatMessage.Type.REGULAR_MESSAGE
            ) {
                current.toMutableList().apply {
                    this[lastIndex] = lastMsg.copyWithNewText(newText)
                }
            } else {
                current
            }
        }
    }

    fun updateMessageByItemId(itemId: String?, newText: String): Boolean {
        if (itemId == null) {
            Log.w(TAG, "Cannot update message with null itemId")
            return false
        }

        var found = false
        _messageList.update { current ->
            val index = current.indexOfFirst { it.itemId == itemId }
            if (index != -1) {
                found = true
                Log.d(TAG, "Updated message at index $index with itemId $itemId to text: $newText")
                current.toMutableList().apply {
                    this[index] = current[index].copyWithNewText(newText)
                }
            } else {
                Log.w(TAG, "Could not find message with itemId: $itemId in list of ${current.size} messages")
                current
            }
        }
        return found
    }

    fun clearMessages() {
        _messageList.value = emptyList()
        // Clear cached item ID to prevent truncate errors on non-existent items
        lastAssistantItemId = null
    }

    /**
     * Force a UI refresh by creating a new list instance.
     * Useful when UI updates were missed due to overlays (e.g., YouTube player).
     */
    fun refreshMessages() {
        _messageList.update { current -> current.toList() }
    }

    // Transcript Management
    private val pendingUserTranscripts = HashMap<String, ChatMessage>()

    fun handleUserSpeechStopped(itemId: String) {
        val placeholder = ChatMessage("🎤 ...", ChatMessage.Sender.USER)
        placeholder.itemId = itemId
        addMessage(placeholder)
        pendingUserTranscripts[itemId] = placeholder
    }

    fun handleUserTranscriptCompleted(itemId: String, transcript: String) {
        val placeholder = pendingUserTranscripts.remove(itemId)
        if (placeholder != null) {
            _messageList.update { current ->
                val index = current.indexOf(placeholder)
                if (index != -1) {
                    current.toMutableList().apply {
                        this[index] = placeholder.copyWithNewText(transcript)
                    }
                } else {
                    // Fallback: just add if not found (shouldn't happen if logic is correct)
                    current + ChatMessage(transcript, ChatMessage.Sender.USER)
                }
            }
        } else {
            // Fallback if placeholder not found
            addMessage(ChatMessage(transcript, ChatMessage.Sender.USER))
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun handleUserTranscriptFailed(itemId: String, error: JSONObject?) {
        val placeholder = pendingUserTranscripts.remove(itemId)
        if (placeholder != null) {
            _messageList.update { current ->
                val index = current.indexOf(placeholder)
                if (index != -1) {
                    current.toMutableList().apply {
                        this[index] = placeholder.copyWithNewText("🎤 [Transcription failed]")
                    }
                } else {
                    current
                }
            }
        }
    }

    fun updateLatestFunctionCallResult(result: String) {
        _messageList.update { current ->
            val index = current.indexOfLast { message ->
                message.type == ChatMessage.Type.FUNCTION_CALL && message.functionResult == null
            }

            if (index != -1) {
                current.toMutableList().apply {
                    this[index] = current[index].copyWithFunctionResult(result)
                }
            } else {
                current
            }
        }
    }

    // Controller Delegation
    private var sessionController: ChatSessionController? = null

    fun setSessionController(sessionController: ChatSessionController?) {
        this.sessionController = sessionController
    }

    fun sendMessageToRealtimeAPI(text: String, requestResponse: Boolean, allowInterrupt: Boolean) {
        sessionController?.sendMessageToRealtimeAPI(text, requestResponse, allowInterrupt)
    }

    fun sendToolResult(callId: String, result: String) {
        sessionController?.sendToolResult(callId, result)
    }

    fun startNewSession() {
        sessionController?.startNewSession()
    }

    fun connectWebSocket(callback: WebSocketConnectionCallback) {
        sessionController?.connectWebSocket(callback)
    }

    fun disconnectWebSocket() {
        sessionController?.disconnectWebSocket()
    }

    fun disconnectWebSocketGracefully() {
        sessionController?.disconnectWebSocketGracefully()
    }
}
