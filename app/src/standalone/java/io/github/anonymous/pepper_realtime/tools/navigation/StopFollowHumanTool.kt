package io.github.anonymous.pepper_realtime.tools.navigation

import android.util.Log
import io.github.anonymous.pepper_realtime.tools.Tool
import io.github.anonymous.pepper_realtime.tools.ToolContext
import org.json.JSONObject

/**
 * Stub implementation of StopFollowHumanTool for standalone mode.
 */
class StopFollowHumanTool : Tool {

    companion object {
        private const val TAG = "StopFollowHumanTool[STUB]"
    }

    override fun getName(): String = "stop_follow_human"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Stop Pepper from following a human.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        Log.i(TAG, "🤖 [SIMULATED] Stop follow human requested")

        return if (FollowHumanTool.stopFollowing()) {
            JSONObject().apply {
                put("status", "stopped")
                put("message", "Would stop following human")
            }.toString()
        } else {
            JSONObject()
                .put("error", "Not currently following anyone.")
                .toString()
        }
    }
}

