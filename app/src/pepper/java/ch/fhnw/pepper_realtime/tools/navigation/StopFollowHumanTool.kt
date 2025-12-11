package ch.fhnw.pepper_realtime.tools.navigation

import android.util.Log
import ch.fhnw.pepper_realtime.tools.Tool
import ch.fhnw.pepper_realtime.tools.ToolContext
import org.json.JSONObject

/**
 * Tool for stopping the active follow_human action.
 * Works in conjunction with FollowHumanTool.
 */
class StopFollowHumanTool : Tool {

    companion object {
        private const val TAG = "StopFollowHumanTool"
    }

    override fun getName(): String = "stop_follow_human"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Stop Pepper from following a human. Use this when the user wants Pepper to stop following them.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        Log.i(TAG, "Stop follow human requested")

        return if (FollowHumanTool.stopFollowing()) {
            Log.i(TAG, "Follow action stopped successfully")
            JSONObject().apply {
                put("status", "stopped")
                put("message", "Pepper has stopped following.")
            }.toString()
        } else {
            Log.i(TAG, "Not currently following anyone")
            JSONObject()
                .put("error", "Not currently following anyone.")
                .toString()
        }
    }
}

