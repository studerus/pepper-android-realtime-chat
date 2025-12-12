package ch.fhnw.pepper_realtime.tools.navigation

import android.util.Log
import ch.fhnw.pepper_realtime.tools.Tool
import ch.fhnw.pepper_realtime.tools.ToolContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stub implementation of NavigateToLocationTool for standalone mode.
 */
class NavigateToLocationTool : Tool {

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
        val navManager = context.navigationServiceManager
        
        // Lock check for consistency with pepper version
        if (!navManager.tryStartNavigationProcess()) {
            Log.w(TAG, "🤖 [SIMULATED] Navigation rejected - another navigation is in progress")
            return JSONObject().apply {
                put("status", "navigation_already_in_progress")
                put("message", "A navigation process is already running. DO NOT call this function again. Wait for the current navigation to complete.")
            }.toString()
        }
        
        Log.i(TAG, "🤖 [SIMULATED] Navigate to location: $locationName")
        
        // Simulate async completion
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            navManager.endNavigationProcess()
            Log.i(TAG, "🤖 [SIMULATED] Navigation to $locationName completed")
        }, 2000)
        
        return JSONObject().apply {
            put("status", "navigation_process_started")
            put("message", "Navigation started. The map is loaded and Pepper is already localized. Navigation to '$locationName' is starting immediately. The process is AUTOMATIC - do NOT call any other functions. Just inform the user.")
        }.toString()
    }

}


