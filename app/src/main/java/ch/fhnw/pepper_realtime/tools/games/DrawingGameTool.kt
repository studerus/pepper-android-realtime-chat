package ch.fhnw.pepper_realtime.tools.games

import ch.fhnw.pepper_realtime.tools.Tool
import ch.fhnw.pepper_realtime.tools.ToolContext
import ch.fhnw.pepper_realtime.ui.ChatActivity
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tool for starting a drawing canvas game.
 * Opens a fullscreen canvas where the user can draw.
 * Changed drawings are sent silently after a pause and during continuous drawing.
 */
class DrawingGameTool : Tool {

    override fun getName(): String = "start_drawing_game"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Opens a drawing canvas on the tablet. You receive drawing snapshots in context during drawing and after a pause. When asked, describe the latest drawing directly. Do NOT use analyze_vision for drawings; it uses the robot's physical camera. Call this function directly without announcing it.")

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

        context.toolHost.runOnUiThread {
            context.operation?.let { owner -> activity.viewModel.ownChatActivity("drawing", owner) { activity.viewModel.dismissDrawingGame() } }
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
            response.put("info", "Drawing snapshots arrive automatically. Describe the latest image directly when asked; no additional function call is needed.")
            response.toString()
        } else {
            JSONObject().put("error", "Could not start drawing game").toString()
        }
    }


}

