package ch.fhnw.pepper_realtime.tools.navigation

import ch.fhnw.pepper_realtime.tools.Tool
import ch.fhnw.pepper_realtime.tools.ToolContext
import org.json.JSONObject

class FinishEnvironmentMapTool : Tool {
    override fun getName() = "finish_environment_map"
    override fun getDefinition() = JSONObject().apply {
        put("type", "function")
        put("name", getName())
        put("description", "Finish the current map draft and save the map and its locations together.")
        put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()))
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        if (context.isQiContextNotReady()) return JSONObject().put("error", "Robot not ready").toString()
        return try {
            // Validation precedes every service-state change (including the no-active-map case).
            if (!context.navigationServiceManager.mappingCoordinator.finish(context)) {
                JSONObject().put("error", "No active mapping process is ready to finish, or finalization is already running.")
            } else {
                JSONObject().put("status", "Map finalization started")
                    .put("message", "The map draft is being finalized. Pepper will confirm once the map and its locations have been saved.")
            }.toString()
        } catch (error: Exception) {
            JSONObject().put("error", "Could not finish mapping: ${error.message}").toString()
        }
    }
}
