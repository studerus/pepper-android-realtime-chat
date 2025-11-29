package io.github.anonymous.pepper_realtime.tools.games

import android.util.Log
import io.github.anonymous.pepper_realtime.tools.Tool
import io.github.anonymous.pepper_realtime.tools.ToolContext
import org.json.JSONObject

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

        // Start game using the manager
        val success = TicTacToeGameManager.startNewGame(context)

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


