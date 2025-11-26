package io.github.anonymous.pepper_realtime.tools.navigation

import android.util.Log
import io.github.anonymous.pepper_realtime.tools.BaseTool
import io.github.anonymous.pepper_realtime.tools.ToolContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stub implementation of NavigateToLocationTool for standalone mode.
 */
class NavigateToLocationTool : BaseTool() {

    companion object {
        private const val TAG = "NavigateToLocationTool[STUB]"
    }

    override fun getName(): String = "navigate_to_location"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Navigate to a previously saved location.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("location_name", JSONObject()
                        .put("type", "string")
                        .put("description", "Name of the saved location to navigate to"))
                })
                put("required", JSONArray().put("location_name"))
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        val locationName = args.optString("location_name", "")
        Log.i(TAG, "🤖 [SIMULATED] Navigate to location: $locationName")
        return JSONObject()
            .put("success", true)
            .put("message", "Would navigate to location: $locationName")
            .toString()
    }

    override fun requiresApiKey(): Boolean = false

    override fun getApiKeyType(): String? = null
}

