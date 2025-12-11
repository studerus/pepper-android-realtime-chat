package ch.fhnw.pepper_realtime.tools.navigation

import android.util.Log
import ch.fhnw.pepper_realtime.tools.Tool
import ch.fhnw.pepper_realtime.tools.ToolContext
import org.json.JSONObject
import java.util.Locale

/**
 * Stub implementation of MovePepperTool for standalone mode.
 */
class MovePepperTool : Tool {

    companion object {
        private const val TAG = "MovePepperTool[STUB]"
    }

    override fun getName(): String = "move_pepper"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Move Pepper robot in a specific direction for a given distance. Use this when the user asks Pepper to move around the room. Call the function directly without announcing it. You can combine forward/backward and sideways movements.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("distance_forward", JSONObject()
                        .put("type", "number")
                        .put("description", "Distance to move forward (positive) or backward (negative) in meters (-4.0 to 4.0). Optional, defaults to 0.")
                        .put("minimum", -4.0)
                        .put("maximum", 4.0))
                    put("distance_sideways", JSONObject()
                        .put("type", "number")
                        .put("description", "Distance to move left (positive) or right (negative) in meters (-4.0 to 4.0). Optional, defaults to 0.")
                        .put("minimum", -4.0)
                        .put("maximum", 4.0))
                    put("speed", JSONObject()
                        .put("type", "number")
                        .put("description", "Optional maximum speed in m/s (0.1-0.55)")
                        .put("minimum", 0.1)
                        .put("maximum", 0.55)
                        .put("default", 0.4))
                })
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        val distanceForward = args.optDouble("distance_forward", 0.0)
        val distanceSideways = args.optDouble("distance_sideways", 0.0)
        val speed = args.optDouble("speed", 0.4)

        // Validate that at least one movement is requested
        if (distanceForward == 0.0 && distanceSideways == 0.0) {
            return JSONObject().put("error", "Please provide a non-zero distance for 'distance_forward' or 'distance_sideways'.").toString()
        }

        Log.i(TAG, "🤖 [SIMULATED] Move - Forward: %.2fm, Sideways: %.2fm, Speed: %.2fm/s".format(
            distanceForward, distanceSideways, speed))

        return JSONObject()
            .put("success", true)
            .put("message", String.format(Locale.US, "Would move forward %.2fm, sideways %.2fm at speed %.2fm/s",
                distanceForward, distanceSideways, speed))
            .toString()
    }

}


