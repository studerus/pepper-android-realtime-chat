package ch.fhnw.pepper_realtime.tools.games

import ch.fhnw.pepper_realtime.tools.Tool
import ch.fhnw.pepper_realtime.tools.ToolContext
import ch.fhnw.pepper_realtime.ui.ChatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

        val activity = context.activity as? ChatActivity
        if (activity == null || !context.hasUi()) {
            return JSONObject().put("error", "No UI available").toString()
        }

        var started = false
        val latch = CountDownLatch(1)
        
        activity.runOnUiThread {
            started = activity.viewModel.startMemoryGame(difficulty) { message, requestResponse ->
                context.sendAsyncUpdate(message, requestResponse)
            }
            latch.countDown()
        }

        // Wait for UI thread to complete (max 2 seconds)
        latch.await(2, TimeUnit.SECONDS)

        return if (started) {
            JSONObject()
                .put("status", "Memory game started.")
                .put("difficulty", difficulty)
                .toString()
        } else {
            JSONObject().put("error", "Could not start game").toString()
        }
    }



}

