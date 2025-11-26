package io.github.anonymous.pepper_realtime.tools.games

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Window
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.github.anonymous.pepper_realtime.R
import java.util.Locale

/**
 * Tic Tac Toe game dialog with 3x3 button grid
 * Handles user interactions and communicates with the new tool system
 */
class TicTacToeDialog(
    context: Context,
    private val gameUpdateCallback: GameUpdateCallback?
) : Dialog(context) {

    companion object {
        private const val TAG = "TicTacToeDialog"
    }

    /**
     * Interface for communicating game updates to the AI system
     */
    fun interface GameUpdateCallback {
        fun sendGameUpdate(message: String, requestResponse: Boolean)
    }

    private val game = TicTacToeGame()
    private lateinit var boardButtons: Array<Button>
    private lateinit var statusText: TextView
    private val autoCloseHandler = Handler(Looper.getMainLooper())

    init {
        initializeDialog()
    }

    private fun initializeDialog() {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_tic_tac_toe)
        setCancelable(true)

        // Initialize UI components
        statusText = findViewById(R.id.tv_game_status)
        val closeButton: Button = findViewById(R.id.btn_close_game)

        // Initialize board buttons
        boardButtons = Array(9) { i ->
            val buttonId = "btn_pos_$i"
            val resId = context.resources.getIdentifier(buttonId, "id", context.packageName)
            val button: Button = findViewById(resId)

            button.setOnClickListener { onUserMove(i) }

            // Set consistent background that doesn't change with state
            setConsistentButtonBackground(button)
            button
        }

        // Set up button listeners
        closeButton.setOnClickListener { dismiss() }

        // Initialize game state
        updateUI()

        Log.i(TAG, "TicTacToe dialog initialized")
    }

    /**
     * Set consistent white background for a button regardless of its state
     * @param button Button to apply consistent background to
     */
    private fun setConsistentButtonBackground(button: Button) {
        val whiteColor = ContextCompat.getColor(context, android.R.color.white)

        // Create a ColorStateList that uses white for all states
        val colorStateList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_enabled),    // enabled
                intArrayOf(-android.R.attr.state_enabled),   // disabled
                intArrayOf(android.R.attr.state_pressed),    // pressed
                intArrayOf()                                 // default
            ),
            intArrayOf(whiteColor, whiteColor, whiteColor, whiteColor)
        )

        button.backgroundTintList = colorStateList
    }

    /**
     * Handle user move on the board
     * @param position Position 0-8 clicked by user
     */
    private fun onUserMove(position: Int) {
        if (game.isGameOver) {
            Log.w(TAG, "Game is over, ignoring user move")
            return
        }

        if (!game.isValidMove(position)) {
            Log.w(TAG, "Invalid move attempted at position: $position")
            return
        }

        // Make user move
        game.makeMove(position, TicTacToeGame.PLAYER_X)
        updateUI()

        Log.i(TAG, "User move at position $position, board: ${game.getBoardString()}")

        // Check game state after user move
        val winner = game.checkWinner()
        val boardState = game.getBoardString()

        if (winner == TicTacToeGame.GAME_CONTINUE) {
            // Game continues - send update and request AI move with strategy reminder
            val readableBoard = formatBoardForAI(boardState)
            val threatAnalysis = analyzeThreats(boardState)
            val strategyHint = " Strategy: 1) Win if you can complete three O's in a row, 2) Block user if they can win with X next move, 3) Take center (4), 4) Take corners (0,2,6,8). Play competitively!"
            val update = String.format(
                Locale.US, "[GAME] User X on pos %d.\n%s\n%s\nYour turn!%s",
                position, readableBoard, threatAnalysis, strategyHint
            )
            gameUpdateCallback?.sendGameUpdate(update, true)

            // Update status to show AI is thinking
            statusText.text = context.getString(R.string.ttt_ai_thinking)
            disableBoardButtons()

        } else {
            // Game ended with user move - send combined message
            val readableBoard = formatBoardForAI(boardState)
            val gameResult = TicTacToeGame.getGameResultMessage(winner)
            val update = String.format(
                Locale.US, "[GAME] User X on pos %d.\n%s\nGAME OVER: %s",
                position, readableBoard, gameResult
            )
            gameUpdateCallback?.sendGameUpdate(update, true)

            // Update UI for game end
            updateGameEndUI(winner)
            scheduleAutoClose()
        }
    }

    /**
     * Handle AI move (called from ToolExecutor)
     * @param position AI's chosen position
     */
    fun onAIMove(position: Int) {
        // Ensure this runs on UI thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { onAIMove(position) }
            return
        }

        if (game.isGameOver) {
            Log.w(TAG, "Game is over, ignoring AI move")
            return
        }

        if (!game.isValidMove(position)) {
            Log.e(TAG, "AI attempted invalid move at position: $position")
            return
        }

        // Make AI move
        game.makeMove(position, TicTacToeGame.PLAYER_O)
        updateUI()

        Log.i(TAG, "AI move at position $position, board: ${game.getBoardString()}")

        // Check if AI won
        val winner = game.checkWinner()
        if (winner != TicTacToeGame.GAME_CONTINUE) {
            // Game ended with AI move - send context update to inform AI
            val readableBoard = formatBoardForAI(game.getBoardString())
            val gameResult = TicTacToeGame.getGameResultMessage(winner)
            val update = String.format(
                Locale.US, "[GAME] AI O on pos %d.\n%s\nGAME OVER: %s",
                position, readableBoard, gameResult
            )
            gameUpdateCallback?.sendGameUpdate(update, true)

            updateGameEndUI(winner)
            scheduleAutoClose()
        } else {
            // Game continues - enable user input
            statusText.text = context.getString(R.string.ttt_your_turn)
            enableBoardButtons()
        }
    }

    /**
     * Update the UI to reflect current game state
     */
    private fun updateUI() {
        // Update board buttons
        for (i in 0 until 9) {
            when (game.getBoardAt(i)) {
                TicTacToeGame.PLAYER_X -> {
                    boardButtons[i].text = "X"
                    boardButtons[i].setTextColor(ContextCompat.getColor(context, android.R.color.holo_blue_dark))
                }
                TicTacToeGame.PLAYER_O -> {
                    boardButtons[i].text = "O"
                    boardButtons[i].setTextColor(ContextCompat.getColor(context, android.R.color.holo_red_dark))
                }
                else -> {
                    boardButtons[i].text = ""
                    boardButtons[i].setTextColor(ContextCompat.getColor(context, android.R.color.black))
                }
            }
        }

        // Update status text
        if (!game.isGameOver) {
            if (game.currentPlayer == TicTacToeGame.PLAYER_X) {
                statusText.text = context.getString(R.string.ttt_your_turn)
                enableBoardButtons()
            } else {
                statusText.text = context.getString(R.string.ttt_ai_thinking)
                disableBoardButtons()
            }
        }
    }

    /**
     * Update UI when game ends
     * @param winner Game result from checkWinner()
     */
    private fun updateGameEndUI(winner: Int) {
        disableBoardButtons()

        when (winner) {
            TicTacToeGame.X_WINS -> statusText.text = context.getString(R.string.ttt_you_won)
            TicTacToeGame.O_WINS -> statusText.text = context.getString(R.string.ttt_ai_won)
            TicTacToeGame.DRAW -> statusText.text = context.getString(R.string.ttt_draw)
        }
    }

    /**
     * Enable all empty board buttons for user input
     * Background color remains consistent due to ColorStateList
     */
    private fun enableBoardButtons() {
        for (i in 0 until 9) {
            boardButtons[i].isEnabled = game.isValidMove(i)
        }
    }

    /**
     * Disable all board buttons (during AI turn or game end)
     * Background color remains consistent due to ColorStateList
     */
    private fun disableBoardButtons() {
        for (button in boardButtons) {
            button.isEnabled = false
        }
    }

    /**
     * Schedule auto-close after game ends
     */
    private fun scheduleAutoClose() {
        autoCloseHandler.postDelayed({
            if (isShowing) {
                Log.i(TAG, "Auto-closing game dialog after game end")
                dismiss()
            }
        }, 5000) // 5 seconds delay
    }

    /**
     * Cancel scheduled auto-close
     */
    private fun cancelAutoClose() {
        autoCloseHandler.removeCallbacksAndMessages(null)
    }

    override fun dismiss() {
        cancelAutoClose()
        super.dismiss()
        Log.i(TAG, "TicTacToe dialog dismissed")
    }

    /**
     * Format board state in a readable way for AI with position numbers
     * @param boardString Raw board string like "---XO-X--"
     * @return Formatted board with position numbers for AI understanding
     */
    private fun formatBoardForAI(boardString: String): String {
        return "Board positions:\n" +
                "0|1|2     ${boardString[0]}|${boardString[1]}|${boardString[2]}\n" +
                "3|4|5  => ${boardString[3]}|${boardString[4]}|${boardString[5]}\n" +
                "6|7|8     ${boardString[6]}|${boardString[7]}|${boardString[8]}"
    }

    /**
     * Analyze the current board for immediate threats and opportunities
     * @param boardString Current board state
     * @return Analysis string with specific recommendations
     */
    private fun analyzeThreats(boardString: String): String {
        val analysis = StringBuilder()

        // Win patterns: rows, columns, diagonals
        val patterns = arrayOf(
            intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8), // rows
            intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8), // columns
            intArrayOf(0, 4, 8), intArrayOf(2, 4, 6)           // diagonals
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

        // No immediate threats
        analysis.append("Analysis: No immediate threats. Play strategically.\n")
        return analysis.toString()
    }

    /**
     * Get current game instance (for new tool system access)
     * @return Current TicTacToeGame instance
     */
    fun getGame(): TicTacToeGame = game
}

