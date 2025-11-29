package io.github.anonymous.pepper_realtime.tools.navigation

import android.util.Log
import io.github.anonymous.pepper_realtime.tools.Tool
import io.github.anonymous.pepper_realtime.tools.ToolContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Stub implementation of TurnPepperTool for standalone mode.
 */
class TurnPepperTool : Tool {

    companion object {
        private const val TAG = "TurnPepperTool[STUB]"
    }

    override fun getName(): String = "turn_pepper"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Turn Pepper robot left or right by a specific number of degrees. Use this when the user asks Pepper to turn or rotate.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("direction", JSONObject()
                        .put("type", "string")
                        .put("description", "Direction to turn")
                        .put("enum", JSONArray().put("left").put("right")))
                    put("degrees", JSONObject()
                        .put("type", "number")
                        .put("description", "Degrees to turn (15-180)")
                        .put("minimum", 15)
                        .put("maximum", 180))
                    put("speed", JSONObject()
                        .put("type", "number")
                        .put("description", "Optional turning speed in rad/s (0.1-1.0)")
                        .put("minimum", 0.1)
                        .put("maximum", 1.0)
                        .put("default", 0.5))
                })
                put("required", JSONArray().put("direction").put("degrees"))
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        val direction = args.optString("direction", "")
        val degrees = args.optDouble("degrees", 0.0)
        val speed = args.optDouble("speed", 0.5)

        Log.i(TAG, "🤖 [SIMULATED] Turn $direction by %.1f degrees at speed %.2f rad/s".format(degrees, speed))

        return JSONObject()
            .put("success", true)
            .put("message", String.format(Locale.US, "Would turn %s by %.0f degrees", direction, degrees))
            .toString()
    }

}


