package ch.fhnw.pepper_realtime.tools.games

/**
 * Core Tic Tac Toe game logic
 * Simple 3x3 grid implementation with win detection
 */
class TicTacToeGame {

    // Board representation: 0=empty, 1=X (user), 2=O (AI)
    private var board = IntArray(9)
    var currentPlayer: Int = PLAYER_X
        private set

    init {
        reset()
    }

    /**
     * Reset the game to initial state
     */
    fun reset() {
        board = IntArray(9)
        currentPlayer = PLAYER_X // User starts first
    }

    /**
     * Check if a move is valid
     * @param position Position 0-8
     * @return true if position is empty and valid
     */
    fun isValidMove(position: Int): Boolean {
        return position in 0..8 && board[position] == EMPTY
    }

    /**
     * Make a move on the board
     * @param position Position 0-8
     * @param player PLAYER_X or PLAYER_O
     * @throws IllegalArgumentException if move is invalid
     */
    fun makeMove(position: Int, player: Int) {
        require(isValidMove(position)) { "Invalid move at position $position" }

        board[position] = player
        currentPlayer = if (player == PLAYER_X) PLAYER_O else PLAYER_X
    }

    /**
     * Check current game state
     * @return GAME_CONTINUE, X_WINS, O_WINS, or DRAW
     */
    fun checkWinner(): Int {
        // Check win patterns
        for (pattern in WIN_PATTERNS) {
            val first = board[pattern[0]]
            if (first != EMPTY &&
                first == board[pattern[1]] &&
                first == board[pattern[2]]
            ) {
                return first // Return X_WINS or O_WINS
            }
        }

        // Check for draw (board full)
        val boardFull = board.none { it == EMPTY }

        return if (boardFull) DRAW else GAME_CONTINUE
    }

    /**
     * Get board state as string for AI context
     * Format: "X-O-X----" where X=user, O=AI, -=empty
     * @return Board string representation
     */
    fun getBoardString(): String {
        return board.joinToString("") { cell ->
            when (cell) {
                PLAYER_X -> "X"
                PLAYER_O -> "O"
                else -> "-"
            }
        }
    }

    /**
     * Get board state at specific position
     * @param position Position 0-8
     * @return EMPTY, PLAYER_X, or PLAYER_O
     */
    fun getBoardAt(position: Int): Int {
        return if (position in 0..8) board[position] else EMPTY
    }

    /**
     * Check if game is over
     * @return true if game has ended (win or draw)
     */
    val isGameOver: Boolean
        get() = checkWinner() != GAME_CONTINUE

    companion object {
        // Constants
        const val EMPTY = 0
        const val PLAYER_X = 1  // User
        const val PLAYER_O = 2  // AI

        // Game states
        const val GAME_CONTINUE = 0
        const val X_WINS = 1
        const val O_WINS = 2
        const val DRAW = 3

        // Win patterns (positions 0-8 in 3x3 grid)
        private val WIN_PATTERNS = arrayOf(
            intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8), // Rows
            intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8), // Columns
            intArrayOf(0, 4, 8), intArrayOf(2, 4, 6)                       // Diagonals
        )

        /**
         * Get system message for game result
         * @param winner Result from checkWinner()
         * @return System message for AI context
         */
        fun getGameResultMessage(winner: Int): String {
            return when (winner) {
                X_WINS -> "User has won the game. Please congratulate the user on their victory."
                O_WINS -> "AI has won the game. Please tell the user it was a good game and offer a rematch."
                DRAW -> "The game ended in a draw. Please tell the user it was a well-played game."
                else -> ""
            }
        }
    }
}


