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
import ch.fhnw.pepper_realtime.manager.DrawingGameManager
import ch.fhnw.pepper_realtime.manager.MemoryGameManager
import ch.fhnw.pepper_realtime.manager.QuizGameManager
import ch.fhnw.pepper_realtime.manager.TicTacToeGameManager
import ch.fhnw.pepper_realtime.manager.audio.ToneGenerator
import ch.fhnw.pepper_realtime.service.LocalFaceRecognitionService
import ch.fhnw.pepper_realtime.ui.compose.FaceManagementState
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
    val localFaceRecognitionService: LocalFaceRecognitionService
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

    init {
        // Provide coroutine scope to game managers
        ticTacToeGameManager.setCoroutineScope(viewModelScope)
        memoryGameManager.setCoroutineScope(viewModelScope)
        drawingGameManager.setCoroutineScope(viewModelScope)
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

    // Connection State
    private val _isConnected = MutableStateFlow(false)

    // Navigation/Map Overlay State
    private val _navigationState = MutableStateFlow(NavigationUiState())

    // Dashboard Overlay State
    private val _dashboardState = MutableStateFlow(DashboardState())

    // Melody Player State
    private val _melodyPlayerState = MutableStateFlow(MelodyPlayerState())
    private val toneGenerator = ToneGenerator()
    private var melodyJob: Job? = null
    private var onMelodyFinishedCallback: ((wasCancelled: Boolean) -> Unit)? = null
    private val melodyCallbackLock = Any()

    // Face Management State
    private val _faceManagementState = MutableStateFlow(FaceManagementState())

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
    val navigationState: StateFlow<NavigationUiState> = _navigationState.asStateFlow()
    val dashboardState: StateFlow<DashboardState> = _dashboardState.asStateFlow()
    val melodyPlayerState: StateFlow<MelodyPlayerState> = _melodyPlayerState.asStateFlow()
    val faceManagementState: StateFlow<FaceManagementState> = _faceManagementState.asStateFlow()

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

    // Navigation/Map Overlay Methods
    fun setLocalizationStatus(status: String) {
        _navigationState.update { it.copy(localizationStatus = status) }
    }

    fun showNavigationOverlay() {
        _navigationState.update { it.copy(isVisible = true) }
    }

    fun hideNavigationOverlay() {
        _navigationState.update { it.copy(isVisible = false) }
    }

    fun toggleNavigationOverlay() {
        _navigationState.update { it.copy(isVisible = !it.isVisible) }
    }

    fun updateMapData(
        hasMapOnDisk: Boolean,
        mapBitmap: Bitmap?,
        mapGfx: MapGraphInfo?,
        locations: List<SavedLocation>
    ) {
        _navigationState.update {
            it.copy(
                hasMapOnDisk = hasMapOnDisk,
                mapBitmap = mapBitmap,
                mapGfx = mapGfx,
                savedLocations = locations
            )
        }
    }

    // Dashboard Overlay Methods
    fun showDashboard() {
        _dashboardState.update { it.copy(isVisible = true, isMonitoring = true) }
        refreshFaceList() // Load faces when dashboard opens
    }

    fun hideDashboard() {
        _dashboardState.update { it.copy(isVisible = false, isMonitoring = false) }
    }

    fun toggleDashboard() {
        val willBeVisible = !_dashboardState.value.isVisible
        _dashboardState.update { it.copy(isVisible = willBeVisible, isMonitoring = willBeVisible) }
        if (willBeVisible) {
            refreshFaceList() // Load faces when dashboard opens
        }
    }

    fun updateDashboardHumans(humans: List<ch.fhnw.pepper_realtime.data.PerceptionData.HumanInfo>, timestamp: String) {
        _dashboardState.update { it.copy(humans = humans, lastUpdate = timestamp) }
    }

    fun resetDashboard() {
        _dashboardState.value = DashboardState()
    }

    // Face Management Methods (integrated into Dashboard)
    fun refreshFaceList() {
        viewModelScope.launch {
            _faceManagementState.update { it.copy(isLoading = true, error = null) }
            
            // listFaces() will automatically start the server if needed
            val faces = localFaceRecognitionService.listFaces()
            
            // Check if we got faces (empty list could mean server not available or no faces)
            val isAvailable = localFaceRecognitionService.isServerAvailable()
            
            _faceManagementState.update { 
                it.copy(
                    isLoading = false, 
                    isServerAvailable = isAvailable,
                    faces = faces, 
                    error = null
                ) 
            }
        }
    }

    fun registerFace(name: String) {
        viewModelScope.launch {
            _faceManagementState.update { it.copy(isLoading = true) }
            val success = localFaceRecognitionService.registerFace(name)
            if (success) {
                refreshFaceList()
            } else {
                _faceManagementState.update { 
                    it.copy(isLoading = false, error = "Failed to register face") 
                }
            }
        }
    }

    fun deleteFace(name: String) {
        viewModelScope.launch {
            _faceManagementState.update { it.copy(isLoading = true) }
            val success = localFaceRecognitionService.deleteFace(name)
            if (success) {
                refreshFaceList()
            } else {
                _faceManagementState.update { 
                    it.copy(isLoading = false, error = "Failed to delete face") 
                }
            }
        }
    }

    /**
     * Recognize faces from Pepper's camera.
     * Returns the recognition result for use in chat flow.
     */
    suspend fun recognizeFaces(): LocalFaceRecognitionService.RecognitionResult {
        return localFaceRecognitionService.recognize()
    }

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

    // Melody Player Methods
    /**
     * Start playing a melody with visual overlay.
     * @param melody The melody string to play
     * @param onFinished Callback when playback finishes with wasCancelled parameter
     * @return true if playback started successfully
     */
    fun startMelodyPlayer(melody: String, onFinished: ((wasCancelled: Boolean) -> Unit)? = null): Boolean {
        if (_melodyPlayerState.value.isVisible) {
            Log.w(TAG, "Melody player already active")
            return false
        }

        synchronized(melodyCallbackLock) {
            onMelodyFinishedCallback = onFinished
        }

        _melodyPlayerState.update {
            it.copy(
                isVisible = true,
                melody = melody,
                isPlaying = true,
                progress = 0f,
                currentNote = ""
            )
        }

        melodyJob = viewModelScope.launch {
            toneGenerator.playMelody(melody, object : ToneGenerator.PlaybackCallback {
                override fun onNoteChanged(note: String, noteIndex: Int, totalNotes: Int) {
                    _melodyPlayerState.update { it.copy(currentNote = note) }
                }

                override fun onProgressChanged(progress: Float) {
                    _melodyPlayerState.update { it.copy(progress = progress) }
                }

                override fun onPlaybackFinished(wasCancelled: Boolean) {
                    _melodyPlayerState.update {
                        it.copy(
                            isPlaying = false,
                            isVisible = false,
                            progress = if (wasCancelled) it.progress else 1f
                        )
                    }
                    melodyJob = null
                    // Call the finish callback with cancellation status (atomic to prevent double-invoke)
                    synchronized(melodyCallbackLock) {
                        onMelodyFinishedCallback?.invoke(wasCancelled)
                        onMelodyFinishedCallback = null
                    }
                }
            })
        }

        return true
    }

    /**
     * Stop the melody player and cancel playback.
     */
    fun dismissMelodyPlayer() {
        toneGenerator.cancel()
        melodyJob?.cancel()
        melodyJob = null
        _melodyPlayerState.update {
            it.copy(isVisible = false, isPlaying = false)
        }
        // Call the finish callback with cancelled=true (atomic to prevent double-invoke)
        synchronized(melodyCallbackLock) {
            onMelodyFinishedCallback?.invoke(true)
            onMelodyFinishedCallback = null
        }
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
