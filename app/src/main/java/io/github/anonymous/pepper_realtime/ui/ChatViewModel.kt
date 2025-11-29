package io.github.anonymous.pepper_realtime.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import android.graphics.Bitmap
import io.github.anonymous.pepper_realtime.controller.ChatSessionController
import io.github.anonymous.pepper_realtime.data.MapGraphInfo
import io.github.anonymous.pepper_realtime.data.ResponseState
import io.github.anonymous.pepper_realtime.data.SavedLocation
import io.github.anonymous.pepper_realtime.network.WebSocketConnectionCallback
import io.github.anonymous.pepper_realtime.tools.games.MemoryCard
import io.github.anonymous.pepper_realtime.tools.games.TicTacToeGame
import io.github.anonymous.pepper_realtime.ui.compose.games.TicTacToeGameState
import java.util.Locale
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

    // Navigation/Map Overlay State (replaces MapUiManager singleton)
    private val _navigationState = MutableStateFlow(NavigationUiState())

    // Dashboard Overlay State (replaces DashboardManager singleton)
    private val _dashboardState = MutableStateFlow(DashboardState())

    // Quiz Dialog State (replaces QuizDialogManager singleton)
    private val _quizState = MutableStateFlow(QuizState())
    private var quizAnswerCallback: ((question: String, selectedOption: String) -> Unit)? = null

    // TicTacToe Game State (replaces TicTacToeGameManager singleton)
    private val _ticTacToeState = MutableStateFlow(TicTacToeUiState())
    private val _ticTacToeGameState = TicTacToeGameState()
    private var ticTacToeUpdateCallback: ((message: String, requestResponse: Boolean) -> Unit)? = null

    // Memory Game State (replaces MemoryGameManager singleton)
    private val _memoryGameState = MutableStateFlow(MemoryGameInternalState())
    private var memoryUpdateCallback: ((message: String, requestResponse: Boolean) -> Unit)? = null
    private val memoryTimerHandler = Handler(Looper.getMainLooper())
    private var memoryTimerRunnable: Runnable? = null

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
    val navigationState: StateFlow<NavigationUiState> = _navigationState.asStateFlow()
    val dashboardState: StateFlow<DashboardState> = _dashboardState.asStateFlow()
    val quizState: StateFlow<QuizState> = _quizState.asStateFlow()
    val ticTacToeState: StateFlow<TicTacToeUiState> = _ticTacToeState.asStateFlow()
    val ticTacToeGameState: TicTacToeGameState get() = _ticTacToeGameState
    val memoryGameState: StateFlow<MemoryGameInternalState> = _memoryGameState.asStateFlow()

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

    // Navigation/Map Overlay Methods (replacing MapUiManager)
    fun setMapStatus(status: String) {
        _navigationState.update { it.copy(mapStatus = status) }
    }

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
        mapState: MapState,
        mapBitmap: Bitmap?,
        mapGfx: MapGraphInfo?,
        locations: List<SavedLocation>
    ) {
        _navigationState.update {
            it.copy(
                mapState = mapState,
                mapBitmap = mapBitmap,
                mapGfx = mapGfx,
                savedLocations = locations
            )
        }
    }

    // Dashboard Overlay Methods (replacing DashboardManager)
    fun showDashboard() {
        _dashboardState.update { it.copy(isVisible = true, isMonitoring = true) }
    }

    fun hideDashboard() {
        _dashboardState.update { it.copy(isVisible = false, isMonitoring = false) }
    }

    fun toggleDashboard() {
        _dashboardState.update { it.copy(isVisible = !it.isVisible, isMonitoring = !it.isMonitoring) }
    }

    fun updateDashboardHumans(humans: List<io.github.anonymous.pepper_realtime.data.PerceptionData.HumanInfo>, timestamp: String) {
        _dashboardState.update { it.copy(humans = humans, lastUpdate = timestamp) }
    }

    fun resetDashboard() {
        _dashboardState.value = DashboardState()
    }

    // Quiz Dialog Methods (replacing QuizDialogManager)
    fun showQuiz(
        question: String,
        options: List<String>,
        correctAnswer: String,
        onAnswered: (question: String, selectedOption: String) -> Unit
    ) {
        quizAnswerCallback = onAnswered
        _quizState.value = QuizState(
            isVisible = true,
            question = question,
            options = options,
            correctAnswer = correctAnswer
        )
    }

    fun onQuizAnswerSelected(selectedOption: String) {
        val state = _quizState.value
        quizAnswerCallback?.invoke(state.question, selectedOption)
        dismissQuiz()
    }

    fun dismissQuiz() {
        _quizState.value = QuizState()
        quizAnswerCallback = null
    }

    // TicTacToe Game Methods (replacing TicTacToeGameManager)
    fun startTicTacToeGame(onUpdate: (message: String, requestResponse: Boolean) -> Unit): Boolean {
        ticTacToeUpdateCallback = onUpdate
        _ticTacToeGameState.reset()
        _ticTacToeState.value = TicTacToeUiState(isVisible = true)
        Log.i(TAG, "TicTacToe game started")
        return true
    }

    fun onTicTacToeUserMove(position: Int) {
        val state = _ticTacToeState.value
        if (!state.isVisible || _ticTacToeGameState.isGameOver) return
        if (!_ticTacToeGameState.isValidMove(position)) return

        _ticTacToeGameState.makeMove(position, TicTacToeGame.PLAYER_X)
        updateTicTacToeUiState()

        val result = _ticTacToeGameState.gameResult
        val boardState = _ticTacToeGameState.getBoardString()

        if (result == TicTacToeGame.GAME_CONTINUE) {
            val readableBoard = formatBoardForAI(boardState)
            val threatAnalysis = analyzeThreats(boardState)
            val strategyHint = " Strategy: 1) Win if you can complete three O's in a row, 2) Block user if they can win with X next move, 3) Take center (4), 4) Take corners (0,2,6,8). Play competitively!"
            val update = String.format(
                Locale.US, "[GAME] User X on pos %d.\n%s\n%s\nYour turn!%s",
                position, readableBoard, threatAnalysis, strategyHint
            )
            ticTacToeUpdateCallback?.invoke(update, true)
        } else {
            val readableBoard = formatBoardForAI(boardState)
            val gameResult = TicTacToeGame.getGameResultMessage(result)
            val update = String.format(
                Locale.US, "[GAME] User X on pos %d.\n%s\nGAME OVER: %s",
                position, readableBoard, gameResult
            )
            ticTacToeUpdateCallback?.invoke(update, true)
            scheduleTicTacToeAutoClose()
        }
    }

    fun isTicTacToeGameActive(): Boolean = _ticTacToeState.value.isVisible

    data class TicTacToeAIMoveResult(
        val success: Boolean,
        val error: String? = null,
        val gameOver: Boolean = false,
        val winner: String? = null,
        val gameOverMessage: String? = null
    )

    fun makeTicTacToeAIMove(position: Int): TicTacToeAIMoveResult {
        val state = _ticTacToeState.value
        if (!state.isVisible) {
            return TicTacToeAIMoveResult(success = false, error = "No active game")
        }
        if (_ticTacToeGameState.isGameOver) {
            return TicTacToeAIMoveResult(success = false, error = "Game is already over")
        }
        if (!_ticTacToeGameState.isValidMove(position)) {
            return TicTacToeAIMoveResult(success = false, error = "Position $position is already occupied")
        }

        _ticTacToeGameState.makeMove(position, TicTacToeGame.PLAYER_O)
        updateTicTacToeUiState()

        val result = _ticTacToeGameState.gameResult
        if (result != TicTacToeGame.GAME_CONTINUE) {
            scheduleTicTacToeAutoClose()
            val winnerString = when (result) {
                TicTacToeGame.X_WINS -> "user"
                TicTacToeGame.O_WINS -> "ai"
                TicTacToeGame.DRAW -> "draw"
                else -> null
            }
            return TicTacToeAIMoveResult(
                success = true,
                gameOver = true,
                winner = winnerString,
                gameOverMessage = TicTacToeGame.getGameResultMessage(result)
            )
        }
        return TicTacToeAIMoveResult(success = true)
    }

    fun dismissTicTacToeGame() {
        _ticTacToeState.value = TicTacToeUiState()
        _ticTacToeGameState.reset()
        ticTacToeUpdateCallback = null
        Log.i(TAG, "TicTacToe game dismissed")
    }

    private fun updateTicTacToeUiState() {
        _ticTacToeState.update {
            it.copy(
                isGameOver = _ticTacToeGameState.isGameOver,
                gameResult = _ticTacToeGameState.gameResult
            )
        }
    }

    private fun scheduleTicTacToeAutoClose() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (_ticTacToeState.value.isVisible && _ticTacToeGameState.isGameOver) {
                dismissTicTacToeGame()
            }
        }, 5000)
    }

    private fun formatBoardForAI(boardString: String): String {
        return "Board positions:\n" +
                "0|1|2     ${boardString[0]}|${boardString[1]}|${boardString[2]}\n" +
                "3|4|5  => ${boardString[3]}|${boardString[4]}|${boardString[5]}\n" +
                "6|7|8     ${boardString[6]}|${boardString[7]}|${boardString[8]}"
    }

    private fun analyzeThreats(boardString: String): String {
        val patterns = arrayOf(
            intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
            intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
            intArrayOf(0, 4, 8), intArrayOf(2, 4, 6)
        )

        for (pattern in patterns) {
            var oCount = 0; var xCount = 0; var emptyPos = -1
            for (pos in pattern) {
                when (boardString[pos]) {
                    'O' -> oCount++
                    'X' -> xCount++
                    else -> emptyPos = pos
                }
            }
            if (oCount == 2 && xCount == 0 && emptyPos != -1) {
                return "CRITICAL: You can WIN by playing position $emptyPos!\n"
            }
        }

        for (pattern in patterns) {
            var oCount = 0; var xCount = 0; var emptyPos = -1
            for (pos in pattern) {
                when (boardString[pos]) {
                    'O' -> oCount++
                    'X' -> xCount++
                    else -> emptyPos = pos
                }
            }
            if (xCount == 2 && oCount == 0 && emptyPos != -1) {
                return "URGENT: User can win next turn! BLOCK position $emptyPos!\n"
            }
        }

        return "Analysis: No immediate threats. Play strategically.\n"
    }

    // Memory Game Methods (replacing MemoryGameManager)
    private val memoryAllSymbols = arrayOf(
        "🌟", "🎈", "🍎", "🏆", "🎵", "🌺", "⚽", "🎯", "🚗", "🏠", "📚", "🎨",
        "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁", "🐮",
        "🍕", "🍔", "🍟", "🌭", "🍿", "🎂", "🍪", "🍩", "🍌", "🍇", "🍓",
        "🌲", "🌳", "🌴", "🌵", "🌸", "🌼", "🌻", "🌹", "🌿", "🍀", "🌾", "🌙"
    )

    fun startMemoryGame(difficulty: String, onUpdate: (message: String, requestResponse: Boolean) -> Unit): Boolean {
        memoryUpdateCallback = onUpdate
        
        val totalPairs = when (difficulty.lowercase()) {
            "easy" -> 4
            "hard" -> 12
            else -> 8
        }

        val cards = setupMemoryCards(totalPairs)
        
        _memoryGameState.value = MemoryGameInternalState(
            isVisible = true,
            cards = cards,
            totalPairs = totalPairs,
            isGameActive = true,
            startTime = System.currentTimeMillis()
        )

        startMemoryTimer()

        val initialMessage = String.format(
            Locale.US,
            "User started a memory game (difficulty: %s, %d card pairs). The game is now running.",
            difficulty, totalPairs
        )
        onUpdate(initialMessage, false)

        Log.i(TAG, "Memory game started with difficulty: $difficulty")
        return true
    }

    private fun setupMemoryCards(pairCount: Int): List<MemoryCard> {
        val actualCount = minOf(pairCount, memoryAllSymbols.size)
        val availableSymbols = memoryAllSymbols.toMutableList().shuffled()
        val cardList = mutableListOf<MemoryCard>()
        var cardId = 0
        
        for (i in 0 until actualCount) {
            val symbol = availableSymbols[i]
            cardList.add(MemoryCard(cardId++, symbol))
            cardList.add(MemoryCard(cardId++, symbol))
        }
        
        return cardList.shuffled()
    }

    fun onMemoryCardClick(cardId: Int) {
        val state = _memoryGameState.value
        if (!state.isGameActive || state.processingMove) return

        val cardIndex = state.cards.indexOfFirst { it.id == cardId }
        if (cardIndex == -1) return
        
        val clickedCard = state.cards[cardIndex]
        if (!clickedCard.canFlip()) return

        val flippedCard = clickedCard.flip()
        updateMemoryCard(cardIndex, flippedCard)

        val cardInfo = "Position ${cardIndex + 1} (Symbol: ${flippedCard.symbol})"

        if (state.firstFlippedCardIndex == -1) {
            // First card
            _memoryGameState.update { it.copy(firstFlippedCardIndex = cardIndex) }
            memoryUpdateCallback?.invoke("User revealed the first card: $cardInfo", false)
        } else if (state.secondFlippedCardIndex == -1) {
            // Second card
            val newMoves = state.moves + 1
            _memoryGameState.update { 
                it.copy(secondFlippedCardIndex = cardIndex, moves = newMoves, processingMove = true) 
            }

            val firstCard = state.cards[state.firstFlippedCardIndex]
            if (firstCard.symbol == flippedCard.symbol) {
                handleMemoryMatch(newMoves)
            } else {
                handleMemoryMismatch(newMoves)
            }
        }
    }

    private fun handleMemoryMatch(currentMoves: Int) {
        val state = _memoryGameState.value
        val firstIdx = state.firstFlippedCardIndex
        val secondIdx = state.secondFlippedCardIndex
        
        val updatedCards = state.cards.toMutableList()
        updatedCards[firstIdx] = updatedCards[firstIdx].setMatched()
        updatedCards[secondIdx] = updatedCards[secondIdx].setMatched()
        
        val newMatchedPairs = state.matchedPairs + 1
        
        _memoryGameState.update {
            it.copy(
                cards = updatedCards,
                matchedPairs = newMatchedPairs,
                firstFlippedCardIndex = -1,
                secondFlippedCardIndex = -1,
                processingMove = false
            )
        }

        val firstInfo = "Symbol: ${state.cards[firstIdx].symbol}"
        val secondInfo = "Symbol: ${state.cards[secondIdx].symbol}"

        if (newMatchedPairs == state.totalPairs) {
            memoryGameComplete(currentMoves)
        } else {
            val message = String.format(
                Locale.US,
                "User found a matching pair! First card: %s, Second card: %s. Current score: %d of %d pairs found, %d moves. Give a very short feedback to the user.",
                firstInfo, secondInfo, newMatchedPairs, state.totalPairs, currentMoves
            )
            memoryUpdateCallback?.invoke(message, true)
        }
    }

    private fun handleMemoryMismatch(currentMoves: Int) {
        val state = _memoryGameState.value
        val firstIdx = state.firstFlippedCardIndex
        val secondIdx = state.secondFlippedCardIndex
        
        val firstInfo = "Symbol: ${state.cards[firstIdx].symbol}"
        val secondInfo = "Symbol: ${state.cards[secondIdx].symbol}"
        
        val message = String.format(
            Locale.US,
            "User revealed two different cards: %s and %s. Cards will be flipped back. Current score: %d moves.",
            firstInfo, secondInfo, currentMoves
        )
        memoryUpdateCallback?.invoke(message, false)

        Handler(Looper.getMainLooper()).postDelayed({
            _memoryGameState.update { currentState ->
                val updatedCards = currentState.cards.toMutableList()
                if (firstIdx in updatedCards.indices) {
                    updatedCards[firstIdx] = updatedCards[firstIdx].flipBack()
                }
                if (secondIdx in updatedCards.indices) {
                    updatedCards[secondIdx] = updatedCards[secondIdx].flipBack()
                }
                currentState.copy(
                    cards = updatedCards,
                    firstFlippedCardIndex = -1,
                    secondFlippedCardIndex = -1,
                    processingMove = false
                )
            }
        }, 1500)
    }

    private fun memoryGameComplete(finalMoves: Int) {
        stopMemoryTimer()
        val state = _memoryGameState.value
        _memoryGameState.update { it.copy(isGameActive = false) }
        
        val message = String.format(
            Locale.US,
            "GAME COMPLETED! Final statistics: All %d pairs found in %d moves and %s time. Congratulate the user on completing the memory game!",
            state.totalPairs, finalMoves, state.timeString
        )
        memoryUpdateCallback?.invoke(message, true)

        Handler(Looper.getMainLooper()).postDelayed({
            dismissMemoryGame()
        }, 5000)
    }

    fun dismissMemoryGame() {
        stopMemoryTimer()
        _memoryGameState.value = MemoryGameInternalState()
        memoryUpdateCallback = null
        Log.i(TAG, "Memory game dismissed")
    }

    private fun startMemoryTimer() {
        stopMemoryTimer()
        memoryTimerRunnable = object : Runnable {
            override fun run() {
                val state = _memoryGameState.value
                if (state.isGameActive) {
                    val elapsedSeconds = (System.currentTimeMillis() - state.startTime) / 1000
                    val minutes = elapsedSeconds / 60
                    val seconds = elapsedSeconds % 60
                    val timeString = String.format(Locale.US, "%02d:%02d", minutes, seconds)
                    
                    _memoryGameState.update { it.copy(timeString = timeString) }
                    memoryTimerHandler.postDelayed(this, 1000)
                }
            }
        }
        memoryTimerHandler.post(memoryTimerRunnable!!)
    }

    private fun stopMemoryTimer() {
        memoryTimerRunnable?.let { memoryTimerHandler.removeCallbacks(it) }
        memoryTimerRunnable = null
    }
    
    private fun updateMemoryCard(index: Int, newCard: MemoryCard) {
        _memoryGameState.update { state ->
            val updatedCards = state.cards.toMutableList()
            if (index in updatedCards.indices) {
                updatedCards[index] = newCard
            }
            state.copy(cards = updatedCards)
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
