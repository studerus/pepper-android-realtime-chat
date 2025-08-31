package io.github.studerus.pepper_android_realtime;

/**
 * Core Tic Tac Toe game logic
 * Simple 3x3 grid implementation with win detection
 */
public class TicTacToeGame {
    
    // Board representation: 0=empty, 1=X (user), 2=O (AI)
    private int[] board;
    private int currentPlayer;
    
    // Constants
    public static final int EMPTY = 0;
    public static final int PLAYER_X = 1;  // User
    public static final int PLAYER_O = 2;  // AI
    
    // Game states
    public static final int GAME_CONTINUE = 0;
    public static final int X_WINS = 1;
    public static final int O_WINS = 2;
    public static final int DRAW = 3;
    
    // Win patterns (positions 0-8 in 3x3 grid)
    private static final int[][] WIN_PATTERNS = {
        {0, 1, 2}, {3, 4, 5}, {6, 7, 8}, // Rows
        {0, 3, 6}, {1, 4, 7}, {2, 5, 8}, // Columns
        {0, 4, 8}, {2, 4, 6}             // Diagonals
    };
    
    public TicTacToeGame() {
        reset();
    }
    
    /**
     * Reset the game to initial state
     */
    public void reset() {
        board = new int[9];
        currentPlayer = PLAYER_X; // User starts first
    }
    
    /**
     * Check if a move is valid
     * @param position Position 0-8
     * @return true if position is empty and valid
     */
    public boolean isValidMove(int position) {
        return position >= 0 && position < 9 && board[position] == EMPTY;
    }
    
    /**
     * Make a move on the board
     * @param position Position 0-8
     * @param player PLAYER_X or PLAYER_O
     * @throws IllegalArgumentException if move is invalid
     */
    public void makeMove(int position, int player) {
        if (!isValidMove(position)) {
            throw new IllegalArgumentException("Invalid move at position " + position);
        }
        
        board[position] = player;
        currentPlayer = (player == PLAYER_X) ? PLAYER_O : PLAYER_X;
    }
    
    /**
     * Check current game state
     * @return GAME_CONTINUE, X_WINS, O_WINS, or DRAW
     */
    public int checkWinner() {
        // Check win patterns
        for (int[] pattern : WIN_PATTERNS) {
            int first = board[pattern[0]];
            if (first != EMPTY && 
                first == board[pattern[1]] && 
                first == board[pattern[2]]) {
                return first; // Return X_WINS or O_WINS
            }
        }
        
        // Check for draw (board full)
        boolean boardFull = true;
        for (int cell : board) {
            if (cell == EMPTY) {
                boardFull = false;
                break;
            }
        }
        
        return boardFull ? DRAW : GAME_CONTINUE;
    }
    
    /**
     * Get board state as string for AI context
     * Format: "X-O-X----" where X=user, O=AI, -=empty
     * @return Board string representation
     */
    public String getBoardString() {
        StringBuilder sb = new StringBuilder();
        for (int cell : board) {
            switch (cell) {
                case PLAYER_X:
                    sb.append('X');
                    break;
                case PLAYER_O:
                    sb.append('O');
                    break;
                default:
                    sb.append('-');
                    break;
            }
        }
        return sb.toString();
    }
    
    /**
     * Get current player
     * @return PLAYER_X or PLAYER_O
     */
    public int getCurrentPlayer() {
        return currentPlayer;
    }
    
    /**
     * Get board state at specific position
     * @param position Position 0-8
     * @return EMPTY, PLAYER_X, or PLAYER_O
     */
    public int getBoardAt(int position) {
        if (position >= 0 && position < 9) {
            return board[position];
        }
        return EMPTY;
    }
    
    /**
     * Get system message for game result
     * @param winner Result from checkWinner()
     * @return System message for AI context
     */
    public static String getGameResultMessage(int winner) {
        switch (winner) {
            case X_WINS:
                return "User has won the game. Please congratulate the user on their victory.";
            case O_WINS:
                return "AI has won the game. Please tell the user it was a good game and offer a rematch.";
            case DRAW:
                return "The game ended in a draw. Please tell the user it was a well-played game.";
            default:
                return "";
        }
    }
    
    /**
     * Check if game is over
     * @return true if game has ended (win or draw)
     */
    public boolean isGameOver() {
        return checkWinner() != GAME_CONTINUE;
    }
}
