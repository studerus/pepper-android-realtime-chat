package io.github.anonymous.pepper_realtime.tools.games

import android.util.Log
import io.github.anonymous.pepper_realtime.tools.BaseTool
import io.github.anonymous.pepper_realtime.tools.ToolContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool for starting memory matching games.
 * Creates a grid of cards that users need to match in pairs.
 */
class MemoryGameTool : BaseTool() {

    override fun getName(): String = "start_memory_game"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Starts a memory matching game with cards that the user has to match in pairs. The user will see a grid of face-down cards and needs to find matching pairs by flipping two cards at a time.")

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
            // Execute UI operation on main thread
            context.activity?.runOnUiThread {
                val activity = context.activity
                if (activity != null && !activity.isFinishing) {
                    val memoryGame = MemoryGameDialog(
                        activity,
                        context
                    ) { context.sendAsyncUpdate("Memory game closed", false) }
                    memoryGame.show(difficulty)
                } else {
                    Log.w(TAG, "Not showing memory game dialog because activity is finishing.")
                }
            }
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

