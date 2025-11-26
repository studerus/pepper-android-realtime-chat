package io.github.anonymous.pepper_realtime.tools.navigation

import android.util.Log
import io.github.anonymous.pepper_realtime.tools.Tool
import io.github.anonymous.pepper_realtime.tools.ToolContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Stub implementation of LookAtPositionTool for standalone mode.
 */
class LookAtPositionTool : Tool {

    companion object {
        private const val TAG = "LookAtPositionTool[STUB]"
    }

    override fun getName(): String = "look_at_position"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Make Pepper look at a specific 3D position.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("x", JSONObject().put("type", "number").put("description", "X coordinate"))
                    put("y", JSONObject().put("type", "number").put("description", "Y coordinate"))
                    put("z", JSONObject().put("type", "number").put("description", "Z coordinate"))
                })
                put("required", JSONArray().put("x").put("y").put("z"))
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        val x = args.optDouble("x", 0.0)
        val y = args.optDouble("y", 0.0)
        val z = args.optDouble("z", 0.0)

        Log.i(TAG, "🤖 [SIMULATED] Look at position: (%.2f, %.2f, %.2f)".format(x, y, z))

        return JSONObject()
            .put("success", true)
            .put("message", String.format(Locale.US, "Would look at position (%.2f, %.2f, %.2f)", x, y, z))
            .toString()
    }

    override fun requiresApiKey(): Boolean = false

    override fun getApiKeyType(): String? = null
}


