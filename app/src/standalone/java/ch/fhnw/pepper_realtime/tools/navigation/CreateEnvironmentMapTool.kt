package ch.fhnw.pepper_realtime.tools.navigation

import android.util.Log
import ch.fhnw.pepper_realtime.tools.Tool
import ch.fhnw.pepper_realtime.tools.ToolContext
import org.json.JSONObject

/**
 * Stub implementation of CreateEnvironmentMapTool for standalone mode.
 */
class CreateEnvironmentMapTool : Tool {

    companion object {
        private const val TAG = "CreateEnvironmentMapTool[STUB]"
    }

    override fun getName(): String = "create_environment_map"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Start creating a new environment map.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        Log.i(TAG, "🤖 [SIMULATED] Create environment map")
        return JSONObject()
            .put("success", true)
            .put("message", "Would start creating environment map")
            .toString()
    }

}


