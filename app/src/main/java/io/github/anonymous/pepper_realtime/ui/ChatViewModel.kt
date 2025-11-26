package io.github.anonymous.pepper_realtime.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.anonymous.pepper_realtime.controller.ChatSessionController
import io.github.anonymous.pepper_realtime.network.WebSocketConnectionCallback
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    // State LiveData
    private val _isWarmingUp = MutableLiveData(false)
    private val _isResponseGenerating = MutableLiveData(false)
    private val _isAudioPlaying = MutableLiveData(false)
    private val _statusText = MutableLiveData("")
    private val _messageList = MutableLiveData<List<ChatMessage>>(ArrayList())
    private val _isMuted = MutableLiveData(false)
    private val _isInterruptFabVisible = MutableLiveData(false)

    // Connection State
    private val _isConnected = MutableLiveData(false)

    // Navigation State
    private val _mapStatus = MutableLiveData("")
    private val _localizationStatus = MutableLiveData("")

    // Synchronized backing field for message list to avoid postValue() race conditions
    // postValue() is async and getValue() doesn't reflect the new value immediately
    private val messageListLock = Any()
    private var currentMessageList: MutableList<ChatMessage> = ArrayList()

    // Internal State
    @Volatile var currentResponseId: String? = null
    @Volatile var cancelledResponseId: String? = null
    @Volatile var lastChatBubbleResponseId: String? = null
    @Volatile var isExpectingFinalAnswerAfterToolCall: Boolean = false
    @Volatile var lastAssistantItemId: String? = null

    // Getters for LiveData (read-only) - @JvmName for Java interop
    @get:JvmName("getIsWarmingUp")
    val isWarmingUp: LiveData<Boolean> get() = _isWarmingUp

    @get:JvmName("getIsResponseGenerating")
    val isResponseGenerating: LiveData<Boolean> get() = _isResponseGenerating

    @get:JvmName("getIsAudioPlaying")
    val isAudioPlaying: LiveData<Boolean> get() = _isAudioPlaying

    @get:JvmName("getStatusText")
    val statusText: LiveData<String> get() = _statusText

    @get:JvmName("getMessageList")
    val messageList: LiveData<List<ChatMessage>> get() = _messageList

    @get:JvmName("getIsConnected")
    val isConnected: LiveData<Boolean> get() = _isConnected

    @get:JvmName("getIsMuted")
    val isMuted: LiveData<Boolean> get() = _isMuted

    @get:JvmName("getIsInterruptFabVisible")
    val isInterruptFabVisible: LiveData<Boolean> get() = _isInterruptFabVisible

    @get:JvmName("getMapStatus")
    val mapStatus: LiveData<String> get() = _mapStatus

    @get:JvmName("getLocalizationStatus")
    val localizationStatus: LiveData<String> get() = _localizationStatus

    // Setters
    fun setWarmingUp(warmingUp: Boolean) {
        _isWarmingUp.postValue(warmingUp)
    }

    fun setResponseGenerating(generating: Boolean) {
        _isResponseGenerating.postValue(generating)
    }

    fun setAudioPlaying(playing: Boolean) {
        _isAudioPlaying.postValue(playing)
    }

    fun setStatusText(text: String) {
        _statusText.postValue(text)
    }

    fun setConnected(connected: Boolean) {
        _isConnected.postValue(connected)
    }

    fun setMuted(muted: Boolean) {
        _isMuted.postValue(muted)
    }

    fun setInterruptFabVisible(visible: Boolean) {
        _isInterruptFabVisible.postValue(visible)
    }

    fun setMapStatus(status: String) {
        _mapStatus.postValue(status)
    }

    fun setLocalizationStatus(status: String) {
        _localizationStatus.postValue(status)
    }

    // Message Management
    fun addMessage(message: ChatMessage) {
        synchronized(messageListLock) {
            val newList = ArrayList(currentMessageList)
            newList.add(message)
            currentMessageList = newList
            _messageList.postValue(newList)
        }
    }

    fun addImageMessage(imagePath: String) {
        // Image is captured by robot camera, so it's a ROBOT output (displayed on the left with thumbnail)
        addMessage(ChatMessage("", imagePath, ChatMessage.Sender.ROBOT))
    }

    fun appendToLastMessage(text: String) {
        synchronized(messageListLock) {
            if (currentMessageList.isNotEmpty()) {
                val lastIndex = currentMessageList.size - 1
                val lastMsg = currentMessageList[lastIndex]

                if (lastMsg.sender == ChatMessage.Sender.ROBOT &&
                    lastMsg.type == ChatMessage.Type.REGULAR_MESSAGE
                ) {
                    val newList = ArrayList(currentMessageList)
                    val updatedMsg = lastMsg.copyWithNewText(lastMsg.message + text)
                    newList[lastIndex] = updatedMsg
                    currentMessageList = newList
                    _messageList.postValue(newList)
                }
            }
        }
    }

    fun updateLastRobotMessage(newText: String) {
        synchronized(messageListLock) {
            if (currentMessageList.isNotEmpty()) {
                val lastIndex = currentMessageList.size - 1
                val lastMsg = currentMessageList[lastIndex]

                if (lastMsg.sender == ChatMessage.Sender.ROBOT &&
                    lastMsg.type == ChatMessage.Type.REGULAR_MESSAGE
                ) {
                    val newList = ArrayList(currentMessageList)
                    val updatedMsg = lastMsg.copyWithNewText(newText)
                    newList[lastIndex] = updatedMsg
                    currentMessageList = newList
                    _messageList.postValue(newList)
                }
            }
        }
    }

    fun updateMessageByItemId(itemId: String?, newText: String): Boolean {
        synchronized(messageListLock) {
            var indexToUpdate = -1
            var msgToUpdate: ChatMessage? = null

            for (i in currentMessageList.indices) {
                val msg = currentMessageList[i]
                if (itemId != null && itemId == msg.itemId) {
                    indexToUpdate = i
                    msgToUpdate = msg
                    break
                }
            }

            if (indexToUpdate != -1 && msgToUpdate != null) {
                val newList = ArrayList(currentMessageList)
                val updatedMsg = msgToUpdate.copyWithNewText(newText)
                newList[indexToUpdate] = updatedMsg
                currentMessageList = newList
                _messageList.postValue(newList)
                Log.d(TAG, "Updated message at index $indexToUpdate with itemId $itemId to text: $newText")
                return true
            } else {
                Log.w(TAG, "Could not find message with itemId: $itemId in list of ${currentMessageList.size} messages")
            }
        }
        return false
    }

    fun clearMessages() {
        synchronized(messageListLock) {
            currentMessageList = ArrayList()
            _messageList.postValue(currentMessageList)
        }
        // Clear cached item ID to prevent truncate errors on non-existent items
        lastAssistantItemId = null
    }

    /**
     * Force a UI refresh by re-posting the current message list.
     * Useful when UI updates were missed due to overlays (e.g., YouTube player).
     */
    fun refreshMessages() {
        synchronized(messageListLock) {
            // Re-post the current list to trigger UI update
            _messageList.postValue(ArrayList(currentMessageList))
        }
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
            synchronized(messageListLock) {
                val newList = ArrayList(currentMessageList)
                val index = newList.indexOf(placeholder)
                if (index != -1) {
                    val updatedMsg = placeholder.copyWithNewText(transcript)
                    newList[index] = updatedMsg
                    currentMessageList = newList
                    _messageList.postValue(newList)
                } else {
                    // Fallback: just add if not found (shouldn't happen if logic is correct)
                    addMessage(ChatMessage(transcript, ChatMessage.Sender.USER))
                }
            }
        } else {
            // Fallback if placeholder not found
            addMessage(ChatMessage(transcript, ChatMessage.Sender.USER))
        }
    }

    fun handleUserTranscriptFailed(itemId: String, error: JSONObject?) {
        val placeholder = pendingUserTranscripts.remove(itemId)
        if (placeholder != null) {
            synchronized(messageListLock) {
                val newList = ArrayList(currentMessageList)
                val index = newList.indexOf(placeholder)
                if (index != -1) {
                    val updatedMsg = placeholder.copyWithNewText("🎤 [Transcription failed]")
                    newList[index] = updatedMsg
                    currentMessageList = newList
                    _messageList.postValue(newList)
                }
            }
        }
    }

    fun updateLatestFunctionCallResult(result: String) {
        synchronized(messageListLock) {
            var indexToUpdate = -1
            var msgToUpdate: ChatMessage? = null

            for (i in currentMessageList.indices.reversed()) {
                val message = currentMessageList[i]
                if (message.type == ChatMessage.Type.FUNCTION_CALL &&
                    message.functionResult == null
                ) {
                    indexToUpdate = i
                    msgToUpdate = message
                    break
                }
            }

            if (indexToUpdate != -1 && msgToUpdate != null) {
                val newList = ArrayList(currentMessageList)
                val updatedMsg = msgToUpdate.copyWithFunctionResult(result)
                newList[indexToUpdate] = updatedMsg
                currentMessageList = newList
                _messageList.postValue(newList)
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

