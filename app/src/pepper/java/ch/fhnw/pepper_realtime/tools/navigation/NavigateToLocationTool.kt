package ch.fhnw.pepper_realtime.tools.navigation

import android.util.Log
import ch.fhnw.pepper_realtime.controller.MovementController.Companion.MOVEMENT_CANCELLED
import ch.fhnw.pepper_realtime.manager.NavigationServiceManager.MovementResult
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import ch.fhnw.pepper_realtime.robot.MotionValidation
import ch.fhnw.pepper_realtime.data.SavedLocation
import ch.fhnw.pepper_realtime.robot.RobotSafetyGuard
import ch.fhnw.pepper_realtime.tools.Tool
import ch.fhnw.pepper_realtime.tools.ToolContext
import ch.fhnw.pepper_realtime.tools.startRobotAction
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Tool for navigating to previously saved locations.
 * Handles lazy map loading and localization if needed.
 */
class NavigateToLocationTool : Tool {

    companion object {
        private const val TAG = "NavigateToLocationTool"
    }

    override fun getName(): String = "navigate_to_location"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Navigate Pepper to a previously saved location. Use this when the user wants to go to a specific named place.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("location_name", JSONObject()
                        .put("type", "string")
                        .put("description", "Name of the saved location to navigate to"))
                    put("speed", JSONObject()
                        .put("type", "number")
                        .put("description", "Optional movement speed in m/s (0.1-0.55)")
                        .put("minimum", 0.1)
                        .put("maximum", 0.55)
                        .put("default", 0.3))
                })
                put("required", JSONArray().put("location_name"))
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        val locationName = args.optString("location_name", "")
        val speed: Double
        try {
            speed = MotionValidation.number(args, "speed", 0.3)
            MotionValidation.speed(speed)
        } catch (e: IllegalArgumentException) {
            return JSONObject().put("error", e.message).toString()
        }

        // Validate location name
        if (locationName.trim().isEmpty()) {
            return JSONObject().put("error", "Location name is required").toString()
        }


        // Check if robot is ready
        if (context.isQiContextNotReady()) {
            return JSONObject().put("error", "Robot not ready").toString()
        }

        val qiContext = context.qiContext as QiContext
        val safety = RobotSafetyGuard.evaluateMovementSafety(qiContext)
        if (!safety.isOk()) {
            val message = safety.message ?: "Navigation blocked by safety check"
            return JSONObject().put("error", message).toString()
        }

        Log.i(TAG, "Navigating to location: $locationName")

        // Get dependencies
        val navManager = context.navigationServiceManager
        if (context.navigationStore.isMapping()) {
            return JSONObject().put("error", "Finish or cancel the current map before navigating to saved locations.").toString()
        }

        // --- LOCK CHECK: Prevent parallel navigation processes ---
        if (!navManager.tryStartNavigationProcess(context.operation)) {
            Log.w(TAG, "Navigation rejected - another navigation process is already running")
            return JSONObject().apply {
                put("status", "navigation_already_in_progress")
                put("message", "A navigation process is already running. DO NOT call this function again. Wait for the current navigation to complete - you will receive automatic status updates.")
            }.toString()
        }

        return try {
            // Load the saved location
            val savedLocation = loadLocationFromStorage(context, locationName)
            if (savedLocation == null) {
                navManager.endNavigationProcess(context.operation)
                return JSONObject().put("error", "Location '$locationName' not found. Please save the location first.").toString()
            }

            // --- PRE-CHECK FOR RACE CONDITION ---
            // Check if the robot is already localized to prevent sending a redundant success message later.
            val wasAlreadyLocalized = navManager.isLocalizationReady()

            // --- ASYNCHRONOUS NAVIGATION FLOW ---
            // This part runs in the background. The initial return value is determined synchronously below.
            Log.i(TAG, "Step 1: Starting navigation preparation - ensuring map is loaded.")
            context.startRobotAction {
                navManager.ensureMapLoadedIfNeeded(qiContext, context.appContext) {
                    context.sendAsyncUpdate("[MAP LOADED] Pepper has loaded the map into memory and will now orient itself. DO NOT call navigate_to_location again - the process continues automatically.", true)
                }
            }.thenConsume { mapReadyFuture ->
                if (!context.isActive) return@thenConsume
                try {
                    if (mapReadyFuture.hasError() || mapReadyFuture.value != true) {
                        Log.e(TAG, "Step 1 FAILED: Map could not be loaded.", mapReadyFuture.error)
                        navManager.endNavigationProcess(context.operation)
                        context.sendAsyncUpdate("[NAVIGATION ERROR] No usable map available. Please create a new map.", true)
                        return@thenConsume
                    }

                    Log.i(TAG, "Step 1 SUCCESS: Map is loaded. Now, Step 2: Ensuring localization.")
                    navManager.ensureLocalizationIfNeeded(
                        qiContext,
                        { // onLocalized Callback
                            try {
                                Log.i(TAG, "Step 2 SUCCESS: Localization is confirmed. Now, Step 3: Starting actual navigation.")
                                // CRITICAL FIX: Only send this update if localization was NOT already complete when the tool was called.
                                if (!wasAlreadyLocalized) {
                                    val note = if (savedLocation.highPrecision) " (high-precision location)" else ""
                                    context.sendAsyncUpdate(
                                        String.format(
                                            Locale.US,
                                            "[LOCALIZATION COMPLETED] Pepper is oriented and starting navigation to %s%s. DO NOT call navigate_to_location again - navigation is now in progress.",
                                            locationName, note
                                        ), true
                                    )
                                }

                                context.ensureActive()
                                val navFuture = navManager.navigateToLocation(qiContext, savedLocation, speed.toFloat(), context.operation)

                                navFuture.thenConsume { navResultFuture ->
                                    val message = try {
                                        if (context.isActive) completionMessage(navResultFuture, locationName) else null
                                    } finally {
                                        navManager.endNavigationProcess(context.operation)
                                    }
                                    if (message != null) context.sendAsyncUpdate(message, true)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Step 3 FAILED: Error initiating navigation.", e)
                                navManager.endNavigationProcess(context.operation)
                                context.sendAsyncUpdate("[NAVIGATION ERROR] Failed to start navigation: ${e.message}", true)
                            }
                        },
                        { // onFailed Callback
                            Log.e(TAG, "Step 2 FAILED: Localization failed. Navigation cancelled.")
                            navManager.endNavigationProcess(context.operation)
                            context.sendAsyncUpdate("[LOCALIZATION FAILED] Orientation failed. Navigation cannot start.", true)
                        },
                        context.operation
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error in map loading callback chain.", e)
                    navManager.endNavigationProcess(context.operation)
                }
            }

            // --- SYNCHRONOUS INITIAL RESPONSE ---
            // Determine the immediate response based on the current state, before async operations begin.
            // IMPORTANT: These messages must clearly state that the process is AUTOMATIC and NO OTHER TOOLS should be called.
            JSONObject().apply {
                when {
                    !navManager.isMapLoaded() -> {
                        put("status", "navigation_process_started")
                        put("message", "The saved map is now being loaded from storage into memory, which takes up to 30 seconds. After loading, Pepper will automatically localize itself, then navigate to '$locationName'. The entire process is AUTOMATIC - do NOT call any other functions (especially not create_environment_map). Just inform the user about the steps and wait for status updates.")
                    }
                    !navManager.isLocalizationReady() -> {
                        put("status", "navigation_process_started")
                        put("message", "The map is already in memory. Pepper will now automatically localize itself (orient within the map), then navigate to '$locationName'. The process is AUTOMATIC - do NOT call any other functions. Just inform the user and wait for status updates.")
                    }
                    else -> {
                        put("status", "navigation_process_started")
                        put("message", "The map is loaded and Pepper is already localized. Navigation to '$locationName' is starting immediately. The process is AUTOMATIC - do NOT call any other functions. Just inform the user.")
                    }
                }
            }.toString()

        } catch (e: Exception) {
            Log.e(TAG, "Error navigating to location", e)
            navManager.endNavigationProcess(context.operation)
            JSONObject().put("error", "Failed to navigate to location: ${e.message}").toString()
        }
    }


    /**
     * Load a location from internal storage
     */
    private fun loadLocationFromStorage(context: ToolContext, locationName: String): SavedLocation? {
        return try {
            val store = context.navigationStore
            val locationFile = store.locationFile(store.active(), locationName)

            if (!locationFile.exists()) {
                Log.w(TAG, "Location file not found: ${locationFile.absolutePath}")
                return null
            }

            // Read JSON data
            val content = StringBuilder()
            BufferedReader(InputStreamReader(FileInputStream(locationFile), StandardCharsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    content.append(line)
                }
            }

            val locationData = JSONObject(content.toString())

            // Create SavedLocation object
            SavedLocation().apply {
                name = locationData.optString("name", locationName)
                description = locationData.optString("description", "")
                timestamp = locationData.optLong("timestamp", 0)
                highPrecision = locationData.optBoolean("high_precision", false)

                val translationArray = locationData.getJSONArray("translation")
                translation = doubleArrayOf(
                    translationArray.getDouble(0),
                    translationArray.getDouble(1),
                    translationArray.getDouble(2)
                )

                val rotationArray = locationData.getJSONArray("rotation")
                rotation = doubleArrayOf(
                    rotationArray.getDouble(0),
                    rotationArray.getDouble(1),
                    rotationArray.getDouble(2),
                    rotationArray.getDouble(3)
                )
            }.also {
                Log.d(TAG, "Location loaded from: ${locationFile.absolutePath}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load location", e)
            null
        }
    }

    /**
     * Check if the navigation error is related to obstacles that could benefit from vision analysis
     */
    private fun isObstacleRelatedNavigationError(error: String?): Boolean {
        if (error.isNullOrEmpty()) {
            return true // Default fallback suggests obstacles
        }

        val lowerError = error.lowercase()

        // Navigation errors that are likely obstacle-related and would benefit from vision analysis
        return lowerError.contains("obstacle") || lowerError.contains("blocked") ||
                lowerError.contains("collision") || lowerError.contains("bump") ||
                lowerError.contains("unreachable") || lowerError.contains("no path") ||
                lowerError.contains("path planning") || lowerError.contains("navigation failed") ||
                lowerError.contains("timeout") || lowerError.contains("took too long") ||
                lowerError.contains("safety") || lowerError.contains("emergency") ||
                lowerError.contains("goto failed") || lowerError.contains("failed to complete")
        // Note: Cancelled/localization errors are NOT obstacle-related, so no vision suggestion
    }

    internal fun completionMessage(future: Future<MovementResult>, location: String): String? {
        if (future.isCancelled) return null
        val error = if (future.hasError()) {
            future.error?.message
        } else {
            val result = future.value
            if (result?.success == true) return "[NAVIGATION COMPLETED] Pepper has arrived at $location."
            if (result?.error == MOVEMENT_CANCELLED) return null
            result?.error
        }
        val hint = if (isObstacleRelatedNavigationError(error)) " $OBSTACLE_VISION_HINT" else ""
        return "[NAVIGATION FAILED] Could not reach $location. Reason: ${translateNavigationError(error)}.$hint"
    }

    /**
     * Translates technical QiSDK navigation errors into user-friendly messages
     */
    private fun translateNavigationError(technicalError: String?): String {
        if (technicalError.isNullOrEmpty()) {
            return "Unknown navigation error occurred"
        }

        val lowerError = technicalError.lowercase()

        return when {
            lowerError.contains("obstacle") || lowerError.contains("blocked") ->
                "My path to the destination is blocked by obstacles"
            lowerError.contains("unreachable") || lowerError.contains("no path") ->
                "The destination cannot be reached from my current position"
            lowerError.contains("timeout") || lowerError.contains("took too long") ->
                "Navigation took too long to reach the destination and was stopped"
            lowerError.contains("localization") || lowerError.contains("lost") ->
                "Position tracking was lost and navigation cannot continue safely"
            lowerError.contains("cancelled") ->
                "Navigation was cancelled"
            else -> "Navigation failed: $technicalError"
        }
    }
}


