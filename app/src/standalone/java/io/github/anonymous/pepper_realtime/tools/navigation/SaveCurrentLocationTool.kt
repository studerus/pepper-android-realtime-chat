package io.github.anonymous.pepper_realtime.tools.navigation

import android.util.Log
import io.github.anonymous.pepper_realtime.tools.Tool
import io.github.anonymous.pepper_realtime.tools.ToolContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stub implementation of SaveCurrentLocationTool for standalone mode.
 */
class SaveCurrentLocationTool : Tool {

    companion object {
        private const val TAG = "SaveCurrentLocationTool[STUB]"
    }

    override fun getName(): String = "save_current_location"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Save current location with a name for later navigation.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("location_name", JSONObject()
                        .put("type", "string")
                        .put("description", "Name for this location"))
                })
                put("required", JSONArray().put("location_name"))
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        val locationName = args.optString("location_name", "")
        Log.i(TAG, "🤖 [SIMULATED] Save location: $locationName")
        return JSONObject()
            .put("success", true)
            .put("message", "Would save current location as: $locationName")
            .toString()
    }

}


