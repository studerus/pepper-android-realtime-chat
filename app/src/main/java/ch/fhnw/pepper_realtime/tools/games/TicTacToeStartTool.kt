package ch.fhnw.pepper_realtime.tools.games

import android.util.Log
import ch.fhnw.pepper_realtime.tools.Tool
import ch.fhnw.pepper_realtime.tools.ToolContext
import ch.fhnw.pepper_realtime.ui.ChatActivity
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tool for starting a new Tic Tac Toe game.
 * Opens the game dialog and initializes a new game where user is X and AI is O.
 */
class TicTacToeStartTool : Tool {

    override fun getName(): String = "start_tic_tac_toe_game"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Start a new Tic Tac Toe game with the user. The user will be X and you will be O. Call this function when the user wants to play Tic Tac Toe. Call the function directly without announcing it.")

            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        Log.i(TAG, "Starting Tic Tac Toe game")

        val activity = context.activity as? ChatActivity
        if (activity == null || !context.hasUi()) {
            return JSONObject()
                .put("error", "Failed to start Tic Tac Toe game - UI not available")
                .toString()
        }

        var success = false
        val latch = CountDownLatch(1)
        
        activity.runOnUiThread {
            success = activity.viewModel.startTicTacToeGame { message, requestResponse ->
                Log.i(TAG, "TicTacToe game update: $message")
                context.sendAsyncUpdate(message, requestResponse)
            }
            latch.countDown()
        }

        // Wait for UI thread to complete (max 2 seconds)
        latch.await(2, TimeUnit.SECONDS)

        return if (success) {
            JSONObject()
                .put("success", "Tic Tac Toe game started! You are X, I am O. Make your first move!")
                .toString()
        } else {
            JSONObject()
                .put("error", "Failed to start Tic Tac Toe game - UI not available")
                .toString()
        }
    }


    companion object {
        private const val TAG = "TicTacToeStartTool"
    }
}


