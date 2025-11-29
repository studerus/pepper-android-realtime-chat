package io.github.anonymous.pepper_realtime.manager

import android.util.Log
import io.github.anonymous.pepper_realtime.tools.games.TicTacToeGame
import io.github.anonymous.pepper_realtime.ui.TicTacToeUiState
import io.github.anonymous.pepper_realtime.ui.compose.games.TicTacToeGameState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages TicTacToe game state and logic.
 * Extracted from ChatViewModel for better separation of concerns.
 */
@Singleton
class TicTacToeGameManager @Inject constructor() {

    companion object {
        private const val TAG = "TicTacToeGameManager"
        private const val AUTO_CLOSE_DELAY_MS = 5000L
    }

    // UI State
    private val _uiState = MutableStateFlow(TicTacToeUiState())
    val uiState: StateFlow<TicTacToeUiState> = _uiState.asStateFlow()

    // Game state for Compose UI
    private val _gameState = TicTacToeGameState()
    val gameState: TicTacToeGameState get() = _gameState

    // Callback for sending updates to the AI
    private var updateCallback: ((message: String, requestResponse: Boolean) -> Unit)? = null

    // Auto-close job
    private var autoCloseJob: Job? = null
    private var coroutineScope: CoroutineScope? = null

    /**
     * Set the coroutine scope for background operations.
     * Should be called with viewModelScope from the ViewModel.
     */
    fun setCoroutineScope(scope: CoroutineScope) {
        coroutineScope = scope
    }

    /**
     * Start a new TicTacToe game.
     * @param onUpdate Callback for sending game updates to the AI
     * @return true if game started successfully
     */
    fun startGame(onUpdate: (message: String, requestResponse: Boolean) -> Unit): Boolean {
        updateCallback = onUpdate
        _gameState.reset()
        _uiState.value = TicTacToeUiState(isVisible = true)
        Log.i(TAG, "TicTacToe game started")
        return true
    }

    /**
     * Check if a game is currently active.
     */
    fun isGameActive(): Boolean = _uiState.value.isVisible

    /**
     * Handle user move (X player).
     * @param position Board position 0-8
     */
    fun onUserMove(position: Int) {
        val state = _uiState.value
        if (!state.isVisible || _gameState.isGameOver) return
        if (!_gameState.isValidMove(position)) return

        _gameState.makeMove(position, TicTacToeGame.PLAYER_X)
        updateUiState()

        val result = _gameState.gameResult
        val boardState = _gameState.getBoardString()

        if (result == TicTacToeGame.GAME_CONTINUE) {
            val readableBoard = formatBoardForAI(boardState)
            val threatAnalysis = analyzeThreats(boardState)
            val strategyHint = " Strategy: 1) Win if you can complete three O's in a row, 2) Block user if they can win with X next move, 3) Take center (4), 4) Take corners (0,2,6,8). Play competitively!"
            val update = String.format(
                Locale.US, "[GAME] User X on pos %d.\n%s\n%s\nYour turn!%s",
                position, readableBoard, threatAnalysis, strategyHint
            )
            updateCallback?.invoke(update, true)
        } else {
            val readableBoard = formatBoardForAI(boardState)
            val gameResult = TicTacToeGame.getGameResultMessage(result)
            val update = String.format(
                Locale.US, "[GAME] User X on pos %d.\n%s\nGAME OVER: %s",
                position, readableBoard, gameResult
            )
            updateCallback?.invoke(update, true)
            scheduleAutoClose()
        }
    }

    /**
     * Result of an AI move attempt.
     */
    data class AIMoveResult(
        val success: Boolean,
        val error: String? = null,
        val gameOver: Boolean = false,
        val winner: String? = null,
        val gameOverMessage: String? = null
    )

    /**
     * Make an AI move (O player).
     * @param position Board position 0-8
     * @return Result of the move attempt
     */
    fun makeAIMove(position: Int): AIMoveResult {
        val state = _uiState.value
        if (!state.isVisible) {
            return AIMoveResult(success = false, error = "No active game")
        }
        if (_gameState.isGameOver) {
            return AIMoveResult(success = false, error = "Game is already over")
        }
        if (!_gameState.isValidMove(position)) {
            return AIMoveResult(success = false, error = "Position $position is already occupied")
        }

        _gameState.makeMove(position, TicTacToeGame.PLAYER_O)
        updateUiState()

        val result = _gameState.gameResult
        if (result != TicTacToeGame.GAME_CONTINUE) {
            scheduleAutoClose()
            val winnerString = when (result) {
                TicTacToeGame.X_WINS -> "user"
                TicTacToeGame.O_WINS -> "ai"
                TicTacToeGame.DRAW -> "draw"
                else -> null
            }
            return AIMoveResult(
                success = true,
                gameOver = true,
                winner = winnerString,
                gameOverMessage = TicTacToeGame.getGameResultMessage(result)
            )
        }
        return AIMoveResult(success = true)
    }

    /**
     * Dismiss the game dialog and reset state.
     */
    fun dismissGame() {
        autoCloseJob?.cancel()
        autoCloseJob = null
        _uiState.value = TicTacToeUiState()
        _gameState.reset()
        updateCallback = null
        Log.i(TAG, "TicTacToe game dismissed")
    }

    private fun updateUiState() {
        _uiState.update {
            it.copy(
                isGameOver = _gameState.isGameOver,
                gameResult = _gameState.gameResult
            )
        }
    }

    private fun scheduleAutoClose() {
        autoCloseJob?.cancel()
        autoCloseJob = coroutineScope?.launch {
            delay(AUTO_CLOSE_DELAY_MS)
            if (_uiState.value.isVisible && _gameState.isGameOver) {
                dismissGame()
            }
        }
    }

    /**
     * Format the board state for AI context.
     */
    private fun formatBoardForAI(boardString: String): String {
        return "Board positions:\n" +
                "0|1|2     ${boardString[0]}|${boardString[1]}|${boardString[2]}\n" +
                "3|4|5  => ${boardString[3]}|${boardString[4]}|${boardString[5]}\n" +
                "6|7|8     ${boardString[6]}|${boardString[7]}|${boardString[8]}"
    }

    /**
     * Analyze threats on the board for AI strategy hints.
     */
    private fun analyzeThreats(boardString: String): String {
        val patterns = arrayOf(
            intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
            intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
            intArrayOf(0, 4, 8), intArrayOf(2, 4, 6)
        )

        // Check for AI winning move
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

        // Check for user threat to block
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
}

