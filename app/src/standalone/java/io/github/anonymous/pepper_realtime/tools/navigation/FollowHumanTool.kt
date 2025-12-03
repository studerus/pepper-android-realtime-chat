package io.github.anonymous.pepper_realtime.tools.navigation

import android.util.Log
import io.github.anonymous.pepper_realtime.tools.Tool
import io.github.anonymous.pepper_realtime.tools.ToolContext
import org.json.JSONObject
import java.util.Locale

/**
 * Stub implementation of FollowHumanTool for standalone mode.
 */
class FollowHumanTool : Tool {

    companion object {
        private const val TAG = "FollowHumanTool[STUB]"
        
        // Lock object for thread-safe state management
        private val stateLock = Any()
        
        @Volatile
        private var isFollowing: Boolean = false
        
        fun isCurrentlyFollowing(): Boolean {
            synchronized(stateLock) {
                return isFollowing
            }
        }
        
        fun stopFollowing(): Boolean {
            synchronized(stateLock) {
                if (!isFollowing) {
                    return false
                }
                isFollowing = false
                Log.i(TAG, "🤖 [SIMULATED] Stopped following")
                return true
            }
        }
    }

    override fun getName(): String = "follow_human"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Make Pepper continuously follow the nearest human at a specified distance.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("distance", JSONObject()
                        .put("type", "number")
                        .put("description", "Distance to maintain from the human in meters (0.5-3.0)")
                        .put("minimum", 0.5)
                        .put("maximum", 3.0)
                        .put("default", 1.0))
                })
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        var distance = args.optDouble("distance", 1.0)
        
        if (distance < 0.5 || distance > 3.0) {
            distance = 1.0
        }

        synchronized(stateLock) {
            if (isFollowing) {
                return JSONObject()
                    .put("error", "Already following a human. Call stop_follow_human first.")
                    .toString()
            }

            Log.i(TAG, "🤖 [SIMULATED] Following human at %.1fm distance".format(distance))
            isFollowing = true
        }

        return JSONObject().apply {
            put("status", "following_started")
            put("distance", distance)
            put("message", String.format(
                Locale.US,
                "Would follow nearest human at %.1f meters distance",
                distance
            ))
        }.toString()
    }
}
