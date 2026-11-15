package ch.fhnw.pepper_realtime.tools.navigation

import ch.fhnw.pepper_realtime.robot.RobotSafetyGuard
import ch.fhnw.pepper_realtime.tools.Tool
import ch.fhnw.pepper_realtime.tools.ToolContext
import com.aldebaran.qi.sdk.QiContext
import org.json.JSONObject

class CreateEnvironmentMapTool : Tool {
    override fun getName() = "create_environment_map"
    override fun getDefinition() = JSONObject().apply {
        put("type", "function")
        put("name", getName())
        put("description", "Create a new environment map draft. Existing map and locations are preserved until finish_environment_map saves the new map.")
        put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()))
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        if (context.isQiContextNotReady()) return JSONObject().put("error", "Robot not ready").toString()
        val safety = RobotSafetyGuard.evaluateMovementSafety(context.qiContext as QiContext)
        if (!safety.isOk()) return JSONObject().put("error", safety.message).toString()
        return try {
            if (!context.navigationServiceManager.mappingCoordinator.start(context)) {
                JSONObject().put("error", "Mapping or navigation is already active. Finish or cancel it first.")
            } else {
                JSONObject().put("status", "Mapping process initiated")
                    .put("message", "Pepper is preparing a new map draft. The previous map and locations remain intact until the new map is finished and saved.")
            }.toString()
        } catch (error: Exception) {
            JSONObject().put("error", "Could not start mapping: ${error.message}").toString()
        }
    }
}
