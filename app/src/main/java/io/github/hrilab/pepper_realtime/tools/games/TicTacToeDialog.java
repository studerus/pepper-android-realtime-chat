package io.github.hrilab.pepper_realtime.tools.games;

import io.github.hrilab.pepper_realtime.R;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;
import android.util.Log;

/**
 * Tic Tac Toe game dialog with 3x3 button grid
 * Handles user interactions and communicates with the new tool system
 */
public class TicTacToeDialog extends Dialog {
    
    private static final String TAG = "TicTacToeDialog";
    
    /**
     * Interface for communicating game updates to the AI system
     */
    public interface GameUpdateCallback {
        void sendGameUpdate(String message, boolean requestResponse);
    }
    
    private final TicTacToeGame game;
    private final GameUpdateCallback gameUpdateCallback;
    private Button[] boardButtons;
    private TextView statusText;
    private final Handler autoCloseHandler;
    
    public TicTacToeDialog(Context context, GameUpdateCallback callback) {
        super(context);
        this.game = new TicTacToeGame();
        this.gameUpdateCallback = callback;
        this.autoCloseHandler = new Handler(Looper.getMainLooper());
        
        initializeDialog();
    }
    
    private void initializeDialog() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_tic_tac_toe);
        setCancelable(true);
        
        // Initialize UI components
        statusText = findViewById(R.id.tv_game_status);
        Button closeButton = findViewById(R.id.btn_close_game);
        
        // Initialize board buttons
        boardButtons = new Button[9];
        for (int i = 0; i < 9; i++) {
            String buttonId = "btn_pos_" + i;
            int resId = getContext().getResources().getIdentifier(buttonId, "id", getContext().getPackageName());
            boardButtons[i] = findViewById(resId);
            
            final int position = i;
            boardButtons[i].setOnClickListener(v -> onUserMove(position));
            
            // Set consistent background that doesn't change with state
            setConsistentButtonBackground(boardButtons[i]);
        }
        
        // Set up button listeners
        closeButton.setOnClickListener(v -> dismiss());
        
        // Initialize game state
        updateUI();
        
        Log.i(TAG, "TicTacToe dialog initialized");
    }
    
    /**
     * Set consistent white background for a button regardless of its state
     * @param button Button to apply consistent background to
     */
    private void setConsistentButtonBackground(Button button) {
        int whiteColor = androidx.core.content.ContextCompat.getColor(getContext(), android.R.color.white);
        
        // Create a ColorStateList that uses white for all states
        ColorStateList colorStateList = new ColorStateList(
            new int[][]{
                new int[]{android.R.attr.state_enabled},    // enabled
                new int[]{-android.R.attr.state_enabled},   // disabled
                new int[]{android.R.attr.state_pressed},    // pressed
                new int[]{}                                 // default
            },
            new int[]{whiteColor, whiteColor, whiteColor, whiteColor}
        );
        
        button.setBackgroundTintList(colorStateList);
    }
    
    /**
     * Handle user move on the board
     * @param position Position 0-8 clicked by user
     */
    private void onUserMove(int position) {
        if (game.isGameOver()) {
            Log.w(TAG, "Game is over, ignoring user move");
            return;
        }
        
        if (!game.isValidMove(position)) {
            Log.w(TAG, "Invalid move attempted at position: " + position);
            return;
        }
        
        // Make user move
        game.makeMove(position, TicTacToeGame.PLAYER_X);
        updateUI();
        
        Log.i(TAG, "User move at position " + position + ", board: " + game.getBoardString());
        
        // Check game state after user move
        int winner = game.checkWinner();
        String boardState = game.getBoardString();
        
        if (winner == TicTacToeGame.GAME_CONTINUE) {
            // Game continues - send update and request AI move with strategy reminder
            String readableBoard = formatBoardForAI(boardState);
            String threatAnalysis = analyzeThreats(boardState);
            String strategyHint = " Strategy: 1) Win if you can complete three O's in a row, 2) Block user if they can win with X next move, 3) Take center (4), 4) Take corners (0,2,6,8). Play competitively!";
            String update = String.format(java.util.Locale.US, "[GAME] User X on pos %d.\n%s\n%s\nYour turn!%s", 
                                        position, readableBoard, threatAnalysis, strategyHint);
            if (gameUpdateCallback != null) {
                gameUpdateCallback.sendGameUpdate(update, true);
            }
            
            // Update status to show AI is thinking
            statusText.setText(getContext().getString(R.string.ttt_ai_thinking));
            disableBoardButtons();
            
        } else {
            // Game ended with user move - send combined message
            String readableBoard = formatBoardForAI(boardState);
            String gameResult = TicTacToeGame.getGameResultMessage(winner);
            String update = String.format(java.util.Locale.US, "[GAME] User X on pos %d.\n%s\nGAME OVER: %s", 
                                        position, readableBoard, gameResult);
            if (gameUpdateCallback != null) {
                gameUpdateCallback.sendGameUpdate(update, true);
            }
            
            // Update UI for game end
            updateGameEndUI(winner);
            scheduleAutoClose();
        }
    }
    
    /**
     * Handle AI move (called from ToolExecutor)
     * @param position AI's chosen position
     */
    public void onAIMove(int position) {
        // Ensure this runs on UI thread
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> onAIMove(position));
            return;
        }
        
        if (game.isGameOver()) {
            Log.w(TAG, "Game is over, ignoring AI move");
            return;
        }
        
        if (!game.isValidMove(position)) {
            Log.e(TAG, "AI attempted invalid move at position: " + position);
            return;
        }
        
        // Make AI move
        game.makeMove(position, TicTacToeGame.PLAYER_O);
        updateUI();
        
        Log.i(TAG, "AI move at position " + position + ", board: " + game.getBoardString());
        
        // Check if AI won
        int winner = game.checkWinner();
        if (winner != TicTacToeGame.GAME_CONTINUE) {
            // Game ended with AI move - send context update to inform AI
            String readableBoard = formatBoardForAI(game.getBoardString());
            String gameResult = TicTacToeGame.getGameResultMessage(winner);
            String update = String.format(java.util.Locale.US, "[GAME] AI O on pos %d.\n%s\nGAME OVER: %s", 
                                        position, readableBoard, gameResult);
            if (gameUpdateCallback != null) {
                gameUpdateCallback.sendGameUpdate(update, true);
            }
            
            updateGameEndUI(winner);
            scheduleAutoClose();
        } else {
            // Game continues - enable user input
            statusText.setText(getContext().getString(R.string.ttt_your_turn));
            enableBoardButtons();
        }
    }
    

    
    /**
     * Update the UI to reflect current game state
     */
    private void updateUI() {
        // Update board buttons
        for (int i = 0; i < 9; i++) {
            int cellValue = game.getBoardAt(i);
            switch (cellValue) {
                case TicTacToeGame.PLAYER_X:
                    boardButtons[i].setText("X");
                    boardButtons[i].setTextColor(androidx.core.content.ContextCompat.getColor(getContext(), android.R.color.holo_blue_dark));
                    break;
                case TicTacToeGame.PLAYER_O:
                    boardButtons[i].setText("O");
                    boardButtons[i].setTextColor(androidx.core.content.ContextCompat.getColor(getContext(), android.R.color.holo_red_dark));
                    break;
                default:
                    boardButtons[i].setText("");
                    boardButtons[i].setTextColor(androidx.core.content.ContextCompat.getColor(getContext(), android.R.color.black));
                    break;
            }
        }
        
        // Update status text
        if (!game.isGameOver()) {
            if (game.getCurrentPlayer() == TicTacToeGame.PLAYER_X) {
                statusText.setText(getContext().getString(R.string.ttt_your_turn));
                enableBoardButtons();
            } else {
                statusText.setText(getContext().getString(R.string.ttt_ai_thinking));
                disableBoardButtons();
            }
        }
    }
    
    /**
     * Update UI when game ends
     * @param winner Game result from checkWinner()
     */
    private void updateGameEndUI(int winner) {
        disableBoardButtons();
        
        switch (winner) {
            case TicTacToeGame.X_WINS:
                statusText.setText(getContext().getString(R.string.ttt_you_won));
                break;
            case TicTacToeGame.O_WINS:
                statusText.setText(getContext().getString(R.string.ttt_ai_won));
                break;
            case TicTacToeGame.DRAW:
                statusText.setText(getContext().getString(R.string.ttt_draw));
                break;
        }
    }
    
    /**
     * Enable all empty board buttons for user input
     * Background color remains consistent due to ColorStateList
     */
    private void enableBoardButtons() {
        for (int i = 0; i < 9; i++) {
            boardButtons[i].setEnabled(game.isValidMove(i));
        }
    }
    
    /**
     * Disable all board buttons (during AI turn or game end)
     * Background color remains consistent due to ColorStateList
     */
    private void disableBoardButtons() {
        for (Button button : boardButtons) {
            button.setEnabled(false);
        }
    }
    
    /**
     * Schedule auto-close after game ends
     */
    private void scheduleAutoClose() {
        autoCloseHandler.postDelayed(() -> {
            if (isShowing()) {
                Log.i(TAG, "Auto-closing game dialog after game end");
                dismiss();
            }
        }, 5000); // 5 seconds delay
    }
    
    /**
     * Cancel scheduled auto-close
     */
    private void cancelAutoClose() {
        autoCloseHandler.removeCallbacksAndMessages(null);
    }
    
    @Override
    public void dismiss() {
        cancelAutoClose();
        super.dismiss();
        Log.i(TAG, "TicTacToe dialog dismissed");
    }
    
    /**
     * Format board state in a readable way for AI with position numbers
     * @param boardString Raw board string like "---XO-X--"
     * @return Formatted board with position numbers for AI understanding
     */
    private String formatBoardForAI(String boardString) {
        return "Board positions:\n" +
               "0|1|2     " + boardString.charAt(0) + "|" + boardString.charAt(1) + "|" + boardString.charAt(2) + "\n" +
               "3|4|5  => " + boardString.charAt(3) + "|" + boardString.charAt(4) + "|" + boardString.charAt(5) + "\n" +
               "6|7|8     " + boardString.charAt(6) + "|" + boardString.charAt(7) + "|" + boardString.charAt(8);
    }
    
    /**
     * Analyze the current board for immediate threats and opportunities
     * @param boardString Current board state
     * @return Analysis string with specific recommendations
     */
    private String analyzeThreats(String boardString) {
        StringBuilder analysis = new StringBuilder();
        
        // Win patterns: rows, columns, diagonals
        int[][] patterns = {
            {0,1,2}, {3,4,5}, {6,7,8}, // rows
            {0,3,6}, {1,4,7}, {2,5,8}, // columns  
            {0,4,8}, {2,4,6}           // diagonals
        };
        
        // Check for AI win opportunities (two O's and one empty)
        for (int[] pattern : patterns) {
            int oCount = 0, xCount = 0, emptyPos = -1;
            for (int pos : pattern) {
                char cell = boardString.charAt(pos);
                if (cell == 'O') oCount++;
                else if (cell == 'X') xCount++;
                else emptyPos = pos;
            }
            if (oCount == 2 && xCount == 0 && emptyPos != -1) {
                analysis.append("CRITICAL: You can WIN by playing position ").append(emptyPos).append("!\n");
                return analysis.toString();
            }
        }
        
        // Check for user threats (two X's and one empty) 
        for (int[] pattern : patterns) {
            int oCount = 0, xCount = 0, emptyPos = -1;
            for (int pos : pattern) {
                char cell = boardString.charAt(pos);
                if (cell == 'O') oCount++;
                else if (cell == 'X') xCount++;
                else emptyPos = pos;
            }
            if (xCount == 2 && oCount == 0 && emptyPos != -1) {
                analysis.append("URGENT: User can win next turn! BLOCK position ").append(emptyPos).append("!\n");
                return analysis.toString();
            }
        }
        
        // No immediate threats
        analysis.append("Analysis: No immediate threats. Play strategically.\n");
        return analysis.toString();
    }
    
    /**
     * Get current game instance (for new tool system access)
     * @return Current TicTacToeGame instance
     */
    public TicTacToeGame getGame() {
        return game;
    }
}
