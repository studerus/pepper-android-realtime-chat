package ch.fhnw.pepper_realtime.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import android.graphics.Bitmap
import ch.fhnw.pepper_realtime.controller.ChatSessionController
import ch.fhnw.pepper_realtime.data.MapGraphInfo
import ch.fhnw.pepper_realtime.data.ResponseState
import ch.fhnw.pepper_realtime.data.SavedLocation
import ch.fhnw.pepper_realtime.manager.DashboardManager
import ch.fhnw.pepper_realtime.manager.DrawingGameManager
import ch.fhnw.pepper_realtime.manager.EventRulesManager
import ch.fhnw.pepper_realtime.manager.FaceManager
import ch.fhnw.pepper_realtime.manager.MelodyManager
import ch.fhnw.pepper_realtime.manager.MemoryGameManager
import ch.fhnw.pepper_realtime.manager.NavigationManager
import ch.fhnw.pepper_realtime.manager.QuizGameManager
import ch.fhnw.pepper_realtime.manager.TicTacToeGameManager
import ch.fhnw.pepper_realtime.service.LocalFaceRecognitionService
import ch.fhnw.pepper_realtime.service.PerceptionWebSocketClient
import ch.fhnw.pepper_realtime.service.EventRuleEngine
import ch.fhnw.pepper_realtime.data.RulePersistence
import ch.fhnw.pepper_realtime.data.EventRule
import ch.fhnw.pepper_realtime.data.MatchedRule
import ch.fhnw.pepper_realtime.data.RuleActionType
import ch.fhnw.pepper_realtime.ui.compose.dashboard.FaceManagementState
import ch.fhnw.pepper_realtime.ui.compose.dashboard.PerceptionSettingsState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ch.fhnw.pepper_realtime.network.WebSocketConnectionCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    application: Application,
    private val ticTacToeGameManager: TicTacToeGameManager,
    private val memoryGameManager: MemoryGameManager,
    private val quizGameManager: QuizGameManager,
    private val drawingGameManager: DrawingGameManager,
    val localFaceRecognitionService: LocalFaceRecognitionService,
    val perceptionWebSocketClient: PerceptionWebSocketClient,
    private val melodyManager: MelodyManager,
    private val navigationManager: NavigationManager,
    private val dashboardManager: DashboardManager,
    private val eventRulesManager: EventRulesManager,
    private val faceManager: FaceManager
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    // State for partial speech results (streaming STT)
    private val _partialSpeechResult = MutableStateFlow<String?>(null)
    val partialSpeechResult = _partialSpeechResult.asStateFlow()
    
    fun setPartialSpeechResult(text: String?) {
        _partialSpeechResult.value = text
    }

    // State using StateFlow - thread-safe, no manual locking needed
    private val _isWarmingUp = MutableStateFlow(false)
    private val _isResponseGenerating = MutableStateFlow(false)
    private val _isAudioPlaying = MutableStateFlow(false)
    private val _statusText = MutableStateFlow("")
    private val _messageList = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _isMuted = MutableStateFlow(false)
    private val _userWantsMicOn = MutableStateFlow(true) // User's desired mic state (persists across robot states)
    private val _isInterruptFabVisible = MutableStateFlow(false)
    
    // Video streaming state
    private val _isVideoStreamActive = MutableStateFlow(false)
    private val _videoPreviewFrame = MutableStateFlow<android.graphics.Bitmap?>(null)
    
    // Google Live API: Flag to ignore incoming audio after manual interrupt
    // This prevents audio from continuing to play after user taps the interrupt button
    // The flag is cleared when a new model turn starts (triggered by user's next speech)
    private val _ignoreGoogleAudioUntilNextTurn = MutableStateFlow(false)
    val ignoreGoogleAudioUntilNextTurn: StateFlow<Boolean> = _ignoreGoogleAudioUntilNextTurn.asStateFlow()
    
    fun setIgnoreGoogleAudio(ignore: Boolean) {
        _ignoreGoogleAudioUntilNextTurn.value = ignore
        if (ignore) {
            Log.d(TAG, "🔇 Google audio ignored until next turn (manual interrupt)")
        } else {
            Log.d(TAG, "🔊 Google audio acceptance resumed (new turn started)")
        }
    }

    // Connection State
    private val _isConnected = MutableStateFlow(false)

    // Navigation/Map Overlay State - delegated to NavigationManager

    // Dashboard Overlay State - delegated to DashboardManager

    // Melody Player State - delegated to MelodyManager

    // Face Management State - delegated to FaceManager

    // Event Rules State - delegated to EventRulesManager

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
    val userWantsMicOn: StateFlow<Boolean> = _userWantsMicOn.asStateFlow()
    val isInterruptFabVisible: StateFlow<Boolean> = _isInterruptFabVisible.asStateFlow()
    val isVideoStreamActive: StateFlow<Boolean> = _isVideoStreamActive.asStateFlow()
    val videoPreviewFrame: StateFlow<android.graphics.Bitmap?> = _videoPreviewFrame.asStateFlow()
    val navigationState: StateFlow<NavigationUiState> = navigationManager.state
    val dashboardState: StateFlow<DashboardState> = dashboardManager.state
    val melodyPlayerState: StateFlow<MelodyPlayerState> = melodyManager.state
    val faceManagementState: StateFlow<FaceManagementState> = faceManager.faceManagementState
    val perceptionSettingsState: StateFlow<PerceptionSettingsState> = faceManager.perceptionSettingsState
    val eventRulesState: StateFlow<EventRulesState> = eventRulesManager.state

    init {
        // Provide coroutine scope to game managers
        ticTacToeGameManager.setCoroutineScope(viewModelScope)
        memoryGameManager.setCoroutineScope(viewModelScope)
        drawingGameManager.setCoroutineScope(viewModelScope)
        melodyManager.setCoroutineScope(viewModelScope)
        faceManager.setCoroutineScope(viewModelScope)
        
        // Setup dashboard callback to refresh faces when opened
        dashboardManager.setOnDashboardOpenedCallback { refreshFaceList() }
        
        // Initialize event rules manager with callback for chat actions
        eventRulesManager.setRuleActionHandler(object : EventRulesManager.RuleActionHandler {
            override fun onAddEventMessage(matchedRule: MatchedRule) {
                val eventMessage = ChatMessage.createEventTrigger(
                    ruleName = matchedRule.rule.name,
                    eventType = matchedRule.event.type.name,
                    actionType = matchedRule.rule.actionType.name,
                    template = matchedRule.rule.template,
                    resolvedText = matchedRule.resolvedTemplate,
                    personName = null
                )
                addMessage(eventMessage)
            }
            
            override fun onSendToRealtimeAPI(text: String, requestResponse: Boolean, allowInterrupt: Boolean) {
                sendMessageToRealtimeAPI(text, requestResponse, allowInterrupt)
            }
        })
        eventRulesManager.initialize()
    }

    /**
     * Set the robot state provider for rule condition checks.
     * Should be called from ChatActivity after TurnManager is available.
     */
    fun setRobotStateProvider(provider: EventRuleEngine.RobotStateProvider) {
        eventRulesManager.setRobotStateProvider(provider)
    }

    // Expose eventRuleEngine for external access (e.g., for PerceptionService event evaluation)
    val eventRuleEngine: EventRuleEngine get() = eventRulesManager.engine

    // Game state flows - delegated to managers
    val quizState: StateFlow<QuizState> = quizGameManager.state
    val ticTacToeState: StateFlow<TicTacToeUiState> = ticTacToeGameManager.uiState
    val ticTacToeGameState get() = ticTacToeGameManager.gameState
    val memoryGameState = memoryGameManager.state
    val drawingGameState = drawingGameManager.state

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

    fun setUserWantsMicOn(wantsMicOn: Boolean) {
        _userWantsMicOn.value = wantsMicOn
    }

    fun setInterruptFabVisible(visible: Boolean) {
        _isInterruptFabVisible.value = visible
    }

    // Video Streaming Methods
    fun setVideoStreamActive(active: Boolean) {
        _isVideoStreamActive.value = active
    }

    fun setVideoPreviewFrame(frame: android.graphics.Bitmap?) {
        _videoPreviewFrame.value = frame
    }

    // Navigation/Map Overlay Methods - delegated to NavigationManager
    fun setLocalizationStatus(status: String) = navigationManager.setLocalizationStatus(status)
    fun showNavigationOverlay() = navigationManager.showNavigationOverlay()
    fun hideNavigationOverlay() = navigationManager.hideNavigationOverlay()
    fun toggleNavigationOverlay() = navigationManager.toggleNavigationOverlay()
    fun updateMapData(
        hasMapOnDisk: Boolean,
        mapBitmap: Bitmap?,
        mapGfx: MapGraphInfo?,
        locations: List<SavedLocation>
    ) = navigationManager.updateMapData(hasMapOnDisk, mapBitmap, mapGfx, locations)

    // Dashboard Overlay Methods - delegated to DashboardManager
    fun showDashboard() = dashboardManager.showDashboard()
    fun hideDashboard() = dashboardManager.hideDashboard()
    fun toggleDashboard() = dashboardManager.toggleDashboard()
    fun updateDashboardHumans(humans: List<ch.fhnw.pepper_realtime.data.PerceptionData.HumanInfo>, timestamp: String) = 
        dashboardManager.updateDashboardHumans(humans, timestamp)
    fun resetDashboard() = dashboardManager.resetDashboard()

    // Face Management Methods - delegated to FaceManager
    fun refreshFaceList() = faceManager.refreshFaceList()
    fun registerFace(name: String) = faceManager.registerFace(name)
    fun deleteFace(name: String) = faceManager.deleteFace(name)
    fun refreshPerceptionSettings() = faceManager.refreshPerceptionSettings()
    fun updatePerceptionSettings(settings: LocalFaceRecognitionService.PerceptionSettings) = 
        faceManager.updatePerceptionSettings(settings)
    suspend fun recognizeFaces(): LocalFaceRecognitionService.RecognitionResult = 
        faceManager.recognizeFaces()

    // Quiz Dialog Methods - delegated to QuizGameManager
    fun showQuiz(
        question: String,
        options: List<String>,
        correctAnswer: String,
        onAnswered: (question: String, selectedOption: String) -> Unit
    ) {
        quizGameManager.showQuiz(question, options, correctAnswer, onAnswered)
    }

    fun onQuizAnswerSelected(selectedOption: String) {
        quizGameManager.onAnswerSelected(selectedOption)
    }

    fun dismissQuiz() {
        quizGameManager.dismissQuiz()
    }

    // TicTacToe Game Methods - delegated to TicTacToeGameManager
    fun startTicTacToeGame(onUpdate: (message: String, requestResponse: Boolean) -> Unit): Boolean {
        return ticTacToeGameManager.startGame(onUpdate)
    }

    fun onTicTacToeUserMove(position: Int) {
        ticTacToeGameManager.onUserMove(position)
    }

    fun isTicTacToeGameActive(): Boolean = ticTacToeGameManager.isGameActive()

    fun makeTicTacToeAIMove(position: Int): TicTacToeGameManager.AIMoveResult {
        return ticTacToeGameManager.makeAIMove(position)
    }

    fun dismissTicTacToeGame() {
        ticTacToeGameManager.dismissGame()
    }

    // Memory Game Methods - delegated to MemoryGameManager
    fun startMemoryGame(difficulty: String, onUpdate: (message: String, requestResponse: Boolean) -> Unit): Boolean {
        return memoryGameManager.startGame(difficulty, onUpdate)
    }

    fun onMemoryCardClick(cardId: Int) {
        memoryGameManager.onCardClick(cardId)
    }

    fun dismissMemoryGame() {
        memoryGameManager.dismissGame()
    }

    // Drawing Game Methods - delegated to DrawingGameManager
    fun startDrawingGame(topic: String?): Boolean {
        return drawingGameManager.startGame(topic)
    }

    fun onDrawingChanged(bitmap: android.graphics.Bitmap) {
        drawingGameManager.onDrawingChanged(bitmap)
    }

    fun clearDrawingCanvas() {
        drawingGameManager.clearCanvas()
    }

    fun dismissDrawingGame() {
        drawingGameManager.dismissGame()
    }

    // Melody Player Methods - delegated to MelodyManager
    /**
     * Start playing a melody with visual overlay.
     */
    fun startMelodyPlayer(melody: String, onFinished: ((wasCancelled: Boolean) -> Unit)? = null): Boolean {
        return melodyManager.startMelodyPlayer(melody, onFinished)
    }

    /**
     * Stop the melody player and cancel playback.
     */
    fun dismissMelodyPlayer() {
        melodyManager.dismissMelodyPlayer()
    }

    /**
     * Set up the drawing game manager with the image send callback.
     * Should be called after sessionController is set.
     */
    fun setupDrawingGameCallback() {
        sessionController?.let { controller ->
            drawingGameManager.setImageSendCallback { base64, mime ->
                controller.sendUserImageToContext(base64, mime)
            }
        }
    }

    // Message Management - using StateFlow.update for atomic operations
    fun addMessage(message: ChatMessage) {
        _messageList.update { current -> current + message }
    }

    fun addImageMessage(imagePath: String) {
        // Image is captured by robot camera, so it's a ROBOT output (displayed on the left with thumbnail)
        addMessage(ChatMessage("", imagePath, ChatMessage.Sender.ROBOT))
    }

    /**
     * Append text to the last ROBOT regular message bubble.
     * This prevents mixing user and assistant text in the same bubble.
     */
    fun appendToLastRobotMessage(text: String) {
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

    /**
     * Append text to the last USER regular message bubble.
     */
    fun appendToLastUserMessage(text: String) {
        _messageList.update { current ->
            if (current.isEmpty()) return@update current

            val lastIndex = current.size - 1
            val lastMsg = current[lastIndex]

            if (lastMsg.sender == ChatMessage.Sender.USER &&
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

    /**
     * Backwards-compatible helper: historically this appended only to the ROBOT bubble.
     */
    fun appendToLastMessage(text: String) {
        appendToLastRobotMessage(text)
    }

    /**
     * Append text to the last THINKING message bubble (Google Live API thinking traces).
     */
    fun appendToThinkingMessage(text: String) {
        _messageList.update { current ->
            if (current.isEmpty()) return@update current

            val lastIndex = current.size - 1
            val lastMsg = current[lastIndex]

            if (lastMsg.type == ChatMessage.Type.THINKING_MESSAGE) {
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

    // ==================== Event Rules - delegated to EventRulesManager ====================
    fun showEventRules() = eventRulesManager.showEventRules()
    fun hideEventRules() = eventRulesManager.hideEventRules()
    fun toggleEventRules() = eventRulesManager.toggleEventRules()
    fun addEventRule(rule: EventRule) = eventRulesManager.addEventRule(rule)
    fun updateEventRule(rule: EventRule) = eventRulesManager.updateEventRule(rule)
    fun deleteEventRule(ruleId: String) = eventRulesManager.deleteEventRule(ruleId)
    fun toggleEventRuleEnabled(ruleId: String) = eventRulesManager.toggleEventRuleEnabled(ruleId)
    fun resetEventRulesToDefaults() = eventRulesManager.resetEventRulesToDefaults()
    fun exportEventRules(): String = eventRulesManager.exportEventRules()
    fun importEventRules(json: String, merge: Boolean = false): Int = eventRulesManager.importEventRules(json, merge)
    fun setEditingRule(rule: EventRule?) = eventRulesManager.setEditingRule(rule)
}
