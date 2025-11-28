package io.github.anonymous.pepper_realtime.tools.games

import android.util.Log
import io.github.anonymous.pepper_realtime.tools.Tool
import io.github.anonymous.pepper_realtime.tools.ToolContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool for starting memory matching games.
 * Creates a grid of cards that users need to match in pairs.
 */
class MemoryGameTool : Tool {

    override fun getName(): String = "start_memory_game"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Starts a memory matching game with cards that the user has to match in pairs. The user will see a grid of face-down cards and needs to find matching pairs by flipping two cards at a time. Call this function directly without announcing it.")

            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("difficulty", JSONObject()
                        .put("type", "string")
                        .put("description", "Game difficulty level")
                        .put("enum", JSONArray().put("easy").put("medium").put("hard"))
                        .put("default", "medium"))
                })
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        val difficulty = args.optString("difficulty", "medium")

        if (context.hasUi()) {
            val started = MemoryGameManager.startGame(difficulty, context)
            if (!started) {
                return JSONObject().put("error", "Could not start game").toString()
            }
        } else {
            return JSONObject().put("error", "No UI available").toString()
        }

        return JSONObject()
            .put("status", "Memory game started.")
            .put("difficulty", difficulty)
            .toString()
    }

    override fun requiresApiKey(): Boolean = false

    override fun getApiKeyType(): String? = null

    companion object {
        private const val TAG = "MemoryGameTool"
    }
}
