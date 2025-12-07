package io.github.anonymous.pepper_realtime.tools.games

import io.github.anonymous.pepper_realtime.tools.Tool
import io.github.anonymous.pepper_realtime.tools.ToolContext
import io.github.anonymous.pepper_realtime.ui.ChatActivity
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tool for starting a drawing canvas game.
 * Opens a fullscreen canvas where the user can draw.
 * Drawings are automatically sent to the API context after 2 seconds of inactivity.
 */
class DrawingGameTool : Tool {

    override fun getName(): String = "start_drawing_game"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Opens a drawing canvas on the tablet where the user can draw with their finger. The drawing is automatically sent to your conversation context when the user pauses - you will receive the image directly without needing any additional function calls. When the user asks 'what did I draw?' or similar, simply describe the drawing image you already have in context. Do NOT use analyze_vision for drawings - that tool is only for the robot's physical camera. Call this function directly without announcing it.")

            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("topic", JSONObject()
                        .put("type", "string")
                        .put("description", "Optional topic or prompt for the drawing (e.g., 'Draw an animal' or 'Draw your favorite food'). Leave empty for free drawing."))
                })
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        val topic = args.optString("topic", "").takeIf { it.isNotBlank() }

        val activity = context.activity as? ChatActivity
        if (activity == null || !context.hasUi()) {
            return JSONObject().put("error", "No UI available").toString()
        }

        var started = false
        val latch = CountDownLatch(1)

        activity.runOnUiThread {
            started = activity.viewModel.startDrawingGame(topic)
            latch.countDown()
        }

        // Wait for UI thread to complete (max 2 seconds)
        latch.await(2, TimeUnit.SECONDS)

        return if (started) {
            val response = JSONObject().put("status", "Drawing canvas opened.")
            if (topic != null) {
                response.put("topic", topic)
            }
            response.put("info", "The drawing will be sent to your context automatically when the user pauses. You will receive it as an image - just describe it directly when asked, no additional function calls needed.")
            response.toString()
        } else {
            JSONObject().put("error", "Could not start drawing game").toString()
        }
    }

    companion object {
        private const val TAG = "DrawingGameTool"
    }
}

