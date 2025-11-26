package io.github.anonymous.pepper_realtime.tools.navigation

import android.util.Log
import io.github.anonymous.pepper_realtime.tools.BaseTool
import io.github.anonymous.pepper_realtime.tools.ToolContext
import org.json.JSONObject

/**
 * Stub implementation of FinishEnvironmentMapTool for standalone mode.
 */
class FinishEnvironmentMapTool : BaseTool() {

    companion object {
        private const val TAG = "FinishEnvironmentMapTool[STUB]"
    }

    override fun getName(): String = "finish_environment_map"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Finish creating environment map and save it.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        Log.i(TAG, "🤖 [SIMULATED] Finish environment map")
        return JSONObject()
            .put("success", true)
            .put("message", "Would finish and save environment map")
            .toString()
    }

    override fun requiresApiKey(): Boolean = false

    override fun getApiKeyType(): String? = null
}

