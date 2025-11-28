package io.github.anonymous.pepper_realtime.tools.games

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import io.github.anonymous.pepper_realtime.tools.ToolContext
import io.github.anonymous.pepper_realtime.ui.compose.games.TicTacToeGameState
import java.util.Locale

/**
 * Static manager for TicTacToe game dialog.
 * Handles dialog lifecycle and communication between tools using Jetpack Compose.
 */
object TicTacToeGameManager {

    private const val TAG = "TicTacToeGameManager"

    /**
     * Interface for communicating game updates to the AI system
     */
    fun interface GameUpdateCallback {
        fun sendGameUpdate(message: String, requestResponse: Boolean)
    }

    /**
     * State holder for the TicTacToe dialog
     */
    data class TicTacToeState(
        val isVisible: Boolean = false,
        val gameState: TicTacToeGameState = TicTacToeGameState(),
        val callback: GameUpdateCallback? = null
    )

    /**
     * Result of an AI move, including game-over information
     */
    data class AIMoveResult(
        val success: Boolean,
        val error: String? = null,
        val gameOver: Boolean = false,
        val winner: String? = null,  // "user", "ai", or "draw"
        val gameOverMessage: String? = null
    )

    // Observable state for Compose
    var ticTacToeState by mutableStateOf(TicTacToeState())
        private set

    /**
     * Start a new TicTacToe game
     */
    fun startNewGame(context: ToolContext): Boolean {
        if (!context.hasUi()) {
            Log.w(TAG, "Cannot start game - no UI context available")
            return false
        }

        val activity = context.activity
        if (activity == null) {
            Log.w(TAG, "Cannot start game - no activity context available")
            return false
        }

        // Create callback for game communication
        val gameCallback = GameUpdateCallback { message, requestResponse ->
            Log.i(TAG, "TicTacToe game update: $message")
            context.sendAsyncUpdate(message, requestResponse)
        }

        // Create new game state
        ticTacToeState = TicTacToeState(
            isVisible = true,
            gameState = TicTacToeGameState(),
            callback = gameCallback
        )

        Log.i(TAG, "New TicTacToe game started")
        return true
    }

    /**
     * Handle user move and send update to AI
     */
    fun onUserMove(position: Int) {
        val state = ticTacToeState
        if (!state.isVisible) return
        
        val gameState = state.gameState
        if (gameState.isGameOver) {
            Log.w(TAG, "Game is over, ignoring user move")
            return
        }
        
        if (!gameState.isValidMove(position)) {
            Log.w(TAG, "Invalid move attempted at position: $position")
            return
        }
        
        // Make user move
        gameState.makeMove(position, TicTacToeGame.PLAYER_X)
        
        Log.i(TAG, "User move at position $position, board: ${gameState.getBoardString()}")
        
        // Check game state after user move
        val winner = gameState.gameResult
        val boardState = gameState.getBoardString()
        
        if (winner == TicTacToeGame.GAME_CONTINUE) {
            // Game continues - send update and request AI move with strategy reminder
            val readableBoard = formatBoardForAI(boardState)
            val threatAnalysis = analyzeThreats(boardState)
            val strategyHint = " Strategy: 1) Win if you can complete three O's in a row, 2) Block user if they can win with X next move, 3) Take center (4), 4) Take corners (0,2,6,8). Play competitively!"
            val update = String.format(
                Locale.US, "[GAME] User X on pos %d.\n%s\n%s\nYour turn!%s",
                position, readableBoard, threatAnalysis, strategyHint
            )
            state.callback?.sendGameUpdate(update, true)
        } else {
            // Game ended with user move
            val readableBoard = formatBoardForAI(boardState)
            val gameResult = TicTacToeGame.getGameResultMessage(winner)
            val update = String.format(
                Locale.US, "[GAME] User X on pos %d.\n%s\nGAME OVER: %s",
                position, readableBoard, gameResult
            )
            state.callback?.sendGameUpdate(update, true)
            
            // Schedule auto-close
            scheduleAutoClose()
        }
    }

    /**
     * Check if there's an active game
     */
    fun hasNoActiveGame(): Boolean {
        return !ticTacToeState.isVisible
    }

    /**
     * Make an AI move in the current game
     * @return AIMoveResult with success status and game-over information
     */
    fun makeAIMove(position: Int): AIMoveResult {
        val state = ticTacToeState
        if (!state.isVisible) {
            Log.w(TAG, "Cannot make AI move - no active game")
            return AIMoveResult(success = false, error = "No active game")
        }

        val gameState = state.gameState
        if (gameState.isGameOver) {
            Log.w(TAG, "Game is over, ignoring AI move")
            return AIMoveResult(success = false, error = "Game is already over")
        }

        if (!gameState.isValidMove(position)) {
            Log.e(TAG, "AI attempted invalid move at position: $position")
            return AIMoveResult(success = false, error = "Position $position is already occupied")
        }

        // Make AI move
        gameState.makeMove(position, TicTacToeGame.PLAYER_O)
        
        Log.i(TAG, "AI move at position $position, board: ${gameState.getBoardString()}")

        // Check game result
        val result = gameState.gameResult
        if (result != TicTacToeGame.GAME_CONTINUE) {
            // Game ended with AI move - schedule auto-close but don't send separate message
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
     * Dismiss the TicTacToe dialog
     */
    fun dismissGame() {
        ticTacToeState = TicTacToeState()
        Log.i(TAG, "TicTacToe game dismissed")
    }

    /**
     * Schedule auto-close after game ends
     */
    private fun scheduleAutoClose() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (ticTacToeState.isVisible && ticTacToeState.gameState.isGameOver) {
                dismissGame()
            }
        }, 5000)
    }

    /**
     * Format board state in a readable way for AI with position numbers
     */
    private fun formatBoardForAI(boardString: String): String {
        return "Board positions:\n" +
                "0|1|2     ${boardString[0]}|${boardString[1]}|${boardString[2]}\n" +
                "3|4|5  => ${boardString[3]}|${boardString[4]}|${boardString[5]}\n" +
                "6|7|8     ${boardString[6]}|${boardString[7]}|${boardString[8]}"
    }

    /**
     * Analyze the current board for immediate threats and opportunities
     */
    private fun analyzeThreats(boardString: String): String {
        val analysis = StringBuilder()

        val patterns = arrayOf(
            intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8), // rows
            intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8), // columns
            intArrayOf(0, 4, 8), intArrayOf(2, 4, 6)                       // diagonals
        )

        // Check for AI win opportunities (two O's and one empty)
        for (pattern in patterns) {
            var oCount = 0
            var xCount = 0
            var emptyPos = -1
            for (pos in pattern) {
                when (boardString[pos]) {
                    'O' -> oCount++
                    'X' -> xCount++
                    else -> emptyPos = pos
                }
            }
            if (oCount == 2 && xCount == 0 && emptyPos != -1) {
                analysis.append("CRITICAL: You can WIN by playing position ").append(emptyPos).append("!\n")
                return analysis.toString()
            }
        }

        // Check for user threats (two X's and one empty)
        for (pattern in patterns) {
            var oCount = 0
            var xCount = 0
            var emptyPos = -1
            for (pos in pattern) {
                when (boardString[pos]) {
                    'O' -> oCount++
                    'X' -> xCount++
                    else -> emptyPos = pos
                }
            }
            if (xCount == 2 && oCount == 0 && emptyPos != -1) {
                analysis.append("URGENT: User can win next turn! BLOCK position ").append(emptyPos).append("!\n")
                return analysis.toString()
            }
        }

        analysis.append("Analysis: No immediate threats. Play strategically.\n")
        return analysis.toString()
    }
}
