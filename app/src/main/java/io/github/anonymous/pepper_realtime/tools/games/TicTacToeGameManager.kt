package io.github.anonymous.pepper_realtime.tools.games

import android.annotation.SuppressLint
import android.util.Log
import io.github.anonymous.pepper_realtime.tools.ToolContext

/**
 * Static manager for TicTacToe game dialog.
 * Handles dialog lifecycle and communication between tools.
 */
object TicTacToeGameManager {

    private const val TAG = "TicTacToeGameManager"

    @SuppressLint("StaticFieldLeak") // Managed lifecycle - dialog is properly cleaned up
    @Volatile
    private var currentDialog: TicTacToeDialog? = null

    /**
     * Start a new TicTacToe game
     */
    @JvmStatic
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

        // Close existing game if present
        currentDialog?.let { dialog ->
            if (dialog.isShowing) {
                dialog.dismiss()
                Log.i(TAG, "Closed existing TicTacToe game for new game")
            }
        }

        // Create callback for game communication
        val gameCallback = TicTacToeDialog.GameUpdateCallback { message, requestResponse ->
            Log.i(TAG, "TicTacToe game update: $message")
            context.sendAsyncUpdate(message, requestResponse)
        }

        // Create and show new dialog on UI thread (Dialog requires Activity context, not Application context)
        activity.runOnUiThread {
            currentDialog = TicTacToeDialog(activity, gameCallback)
            currentDialog?.show()
        }

        Log.i(TAG, "New TicTacToe game started")
        return true
    }

    /**
     * Get current active dialog
     */
    @JvmStatic
    fun getCurrentDialog(): TicTacToeDialog {
        return currentDialog ?: throw IllegalStateException("No active TicTacToe game")
    }

    /**
     * Check if there's an active game
     */
    @JvmStatic
    fun hasNoActiveGame(): Boolean {
        val dialog = currentDialog
        return dialog == null || !dialog.isShowing
    }

    /**
     * Make an AI move in the current game
     */
    @JvmStatic
    fun makeAIMove(position: Int): Boolean {
        if (hasNoActiveGame()) {
            Log.w(TAG, "Cannot make AI move - no active game")
            return false
        }

        currentDialog?.onAIMove(position)
        return true
    }
}

