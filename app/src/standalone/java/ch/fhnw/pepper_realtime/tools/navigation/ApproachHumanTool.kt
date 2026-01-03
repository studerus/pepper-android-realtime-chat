package ch.fhnw.pepper_realtime.tools.navigation

import android.util.Log
import ch.fhnw.pepper_realtime.tools.Tool
import ch.fhnw.pepper_realtime.tools.ToolContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Stub implementation of ApproachHumanTool for standalone mode.
 */
class ApproachHumanTool : Tool {



    override fun getName(): String = "approach_human"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Approach the nearest detected human.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("distance", JSONObject()
                        .put("type", "number")
                        .put("description", "Target distance in meters (0.5-2.0)")
                        .put("minimum", 0.5)
                        .put("maximum", 2.0)
                        .put("default", 1.0))
                })
                put("required", JSONArray())
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        val distance = args.optDouble("distance", 1.0)
        Log.i("ApproachHumanTool[STUB]", "🤖 [SIMULATED] Approach human to %.2fm distance".format(distance))
        return JSONObject()
            .put("success", true)
            .put("message", String.format(Locale.US, "Would approach nearest human to %.1fm distance", distance))
            .toString()
    }

}


