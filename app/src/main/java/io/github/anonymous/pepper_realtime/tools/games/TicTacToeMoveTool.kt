package io.github.anonymous.pepper_realtime.tools.games

import android.util.Log
import io.github.anonymous.pepper_realtime.tools.Tool
import io.github.anonymous.pepper_realtime.tools.ToolContext
import org.json.JSONArray
import org.json.JSONObject


/**
 * Tool for making moves in an active Tic Tac Toe game.
 * AI uses this tool to place its O moves on the game board.
 */
class TicTacToeMoveTool : Tool {

    override fun getName(): String = "make_tic_tac_toe_move"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Make a strategic move in the current Tic Tac Toe game. Choose a position from 0-8 where 0=top-left, 1=top-center, 2=top-right, 3=middle-left, 4=center, 5=middle-right, 6=bottom-left, 7=bottom-center, 8=bottom-right. Play strategically: 1) Win immediately if you can complete three in a row, 2) Block the user if they can win on their next move, 3) Take the center (position 4) if available, 4) Take corners (0,2,6,8) over edges. Don't make it too easy for the user to win - play competitively but not perfectly. The user can see the game board visually, so don't describe the board state after your move.")

            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("position", JSONObject()
                        .put("type", "integer")
                        .put("description", "Position on the 3x3 board (0-8)")
                        .put("minimum", 0)
                        .put("maximum", 8))
                })
                put("required", JSONArray().put("position"))
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        val position = args.getInt("position")
        Log.i(TAG, "AI making Tic Tac Toe move at position: $position")

        // Check if there's an active game
        if (TicTacToeGameManager.hasNoActiveGame()) {
            return JSONObject()
                .put("error", "No active Tic Tac Toe game found")
                .toString()
        }

        // Validate position range
        if (position !in 0..8) {
            return JSONObject()
                .put("error", "Invalid move. Position must be between 0 and 8.")
                .toString()
        }

        // Make AI move using the manager
        val result = TicTacToeGameManager.makeAIMove(position)

        return if (result.success) {
            val response = JSONObject().put("success", "Move confirmed.")
            
            // Include game-over information in the tool result
            if (result.gameOver) {
                response.put("game_over", true)
                response.put("winner", result.winner)
                response.put("message", result.gameOverMessage)
            }
            
            response.toString()
        } else {
            JSONObject()
                .put("error", result.error ?: "Invalid move.")
                .toString()
        }
    }


    companion object {
        private const val TAG = "TicTacToeMoveTool"
    }
}


