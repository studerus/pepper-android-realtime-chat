package io.github.anonymous.pepper_realtime.tools.games;

import android.util.Log;
import io.github.anonymous.pepper_realtime.tools.ToolContext;

/**
 * Static manager for TicTacToe game dialog.
 * Handles dialog lifecycle and communication between tools.
 */
public class TicTacToeGameManager {
    
    private static final String TAG = "TicTacToeGameManager";
    @SuppressWarnings("StaticFieldLeak") // Managed lifecycle - dialog is properly cleaned up
    private static TicTacToeDialog currentDialog = null;
    
    /**
     * Start a new TicTacToe game
     */
    public static boolean startNewGame(ToolContext context) {
        if (context == null || !context.hasUi()) {
            Log.w(TAG, "Cannot start game - no UI context available");
            return false;
        }
        
        android.app.Activity activity = context.getActivity();
        if (activity == null) {
            Log.w(TAG, "Cannot start game - no activity context available");
            return false;
        }
        
        // Close existing game if present
        if (currentDialog != null && currentDialog.isShowing()) {
            currentDialog.dismiss();
            Log.i(TAG, "Closed existing TicTacToe game for new game");
        }
        
        // Create callback for game communication
        TicTacToeDialog.GameUpdateCallback gameCallback = (message, requestResponse) -> {
            Log.i(TAG, "TicTacToe game update: " + message);
            context.sendAsyncUpdate(message, requestResponse);
        };
        
        // Create and show new dialog on UI thread (Dialog requires Activity context, not Application context)
        activity.runOnUiThread(() -> {
            currentDialog = new TicTacToeDialog(activity, gameCallback);
            currentDialog.show();
        });
        
        Log.i(TAG, "New TicTacToe game started");
        return true;
    }
    
    /**
     * Get current active dialog
     */
    public static TicTacToeDialog getCurrentDialog() {
        return currentDialog;
    }
    
    /**
     * Check if there's an active game
     */
    public static boolean hasNoActiveGame() {
        return currentDialog == null || !currentDialog.isShowing();
    }
    
    /**
     * Make an AI move in the current game
     */
    public static boolean makeAIMove(int position) {
        if (hasNoActiveGame()) {
            Log.w(TAG, "Cannot make AI move - no active game");
            return false;
        }
        
        currentDialog.onAIMove(position);
        return true;
    }
    

}
