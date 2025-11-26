package io.github.anonymous.pepper_realtime.tools.navigation

import android.util.Log
import com.aldebaran.qi.sdk.QiContext
import io.github.anonymous.pepper_realtime.data.SavedLocation
import io.github.anonymous.pepper_realtime.robot.RobotSafetyGuard
import io.github.anonymous.pepper_realtime.tools.BaseTool
import io.github.anonymous.pepper_realtime.tools.ToolContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Tool for navigating to previously saved locations.
 * Handles lazy map loading and localization if needed.
 */
class NavigateToLocationTool : BaseTool() {

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
        val speed = args.optDouble("speed", 0.3)

        // Validate location name
        if (locationName.trim().isEmpty()) {
            return JSONObject().put("error", "Location name is required").toString()
        }

        // Validate speed
        if (speed < 0.1 || speed > 0.55) {
            return JSONObject().put("error", "Speed must be between 0.1 and 0.55 m/s").toString()
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

        return try {
            // Load the saved location
            val savedLocation = loadLocationFromStorage(context, locationName)
                ?: return JSONObject().put("error", "Location '$locationName' not found. Please save the location first.").toString()

            // Get dependencies
            val navManager = context.navigationServiceManager

            // --- PRE-CHECK FOR RACE CONDITION ---
            // Check if the robot is already localized to prevent sending a redundant success message later.
            val wasAlreadyLocalized = navManager.isLocalizationReady()

            // --- ASYNCHRONOUS NAVIGATION FLOW ---
            // This part runs in the background. The initial return value is determined synchronously below.
            Log.i(TAG, "Step 1: Starting navigation preparation - ensuring map is loaded.")
            navManager.ensureMapLoadedIfNeeded(qiContext, context.appContext) {
                context.sendAsyncUpdate("[MAP LOADED] Pepper has loaded the map into memory and will now orient itself.", true)
            }.thenConsume { mapReadyFuture ->
                try {
                    if (mapReadyFuture.hasError() || mapReadyFuture.value != true) {
                        Log.e(TAG, "Step 1 FAILED: Map could not be loaded.", mapReadyFuture.error)
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
                                            "[LOCALIZATION COMPLETED] Pepper is oriented and starting navigation to %s%s.",
                                            locationName, note
                                        ), true
                                    )
                                }

                                val navFuture = navManager.navigateToLocation(qiContext, savedLocation, speed.toFloat())

                                navFuture.thenConsume { navResultFuture ->
                                    try {
                                        val result = navResultFuture.value
                                        val success = !navResultFuture.hasError() && result?.success == true
                                        val error = if (navResultFuture.hasError()) {
                                            navResultFuture.error?.message
                                        } else {
                                            result?.error
                                        }

                                        val message: String
                                        if (success) {
                                            message = String.format(
                                                Locale.US,
                                                "[NAVIGATION COMPLETED] Pepper has arrived at %s.",
                                                locationName
                                            )
                                            Log.i(TAG, "Step 3 SUCCESS: Navigation to $locationName completed.")
                                        } else {
                                            val friendlyError = translateNavigationError(error)

                                            // Build the base error message
                                            val messageBuilder = StringBuilder()
                                            messageBuilder.append(
                                                String.format(
                                                    Locale.US,
                                                    "[NAVIGATION FAILED] Could not reach %s. Reason: %s.",
                                                    locationName, friendlyError
                                                )
                                            )

                                            // Add vision analysis suggestion for obstacle-related errors in the same message
                                            val isObstacleError = isObstacleRelatedNavigationError(error)
                                            Log.i(TAG, "Navigation error: '$error' -> obstacle-related: $isObstacleError")
                                            if (isObstacleError) {
                                                messageBuilder.append(
                                                    String.format(
                                                        Locale.US,
                                                        " Use vision analysis to identify what is blocking your path - " +
                                                                "look around your current position, starting with (%.2f, %.2f, %.2f) in front of you, to see what obstacles are nearby.",
                                                        1.0, 0.0, 0.0
                                                    )
                                                ) // Always look 1m forward from current position
                                            }

                                            message = messageBuilder.toString()
                                            Log.e(TAG, "Step 3 FAILED: Navigation to $locationName failed. Reason: $error")
                                        }
                                        context.sendAsyncUpdate(message, true)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error processing navigation result future.", e)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Step 3 FAILED: Error initiating navigation.", e)
                                context.sendAsyncUpdate("[NAVIGATION ERROR] Failed to start navigation: ${e.message}", true)
                            }
                        },
                        { // onFailed Callback
                            Log.e(TAG, "Step 2 FAILED: Localization failed. Navigation cancelled.")
                            context.sendAsyncUpdate("[LOCALIZATION FAILED] Orientation failed. Navigation cannot start.", true)
                        }
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error in map loading callback chain.", e)
                }
            }

            // --- SYNCHRONOUS INITIAL RESPONSE ---
            // Determine the immediate response based on the current state, before async operations begin.
            JSONObject().apply {
                when {
                    !navManager.isMapLoaded() -> {
                        put("status", "starting_full_navigation_setup")
                        put("message", "The environment map is not currently loaded. Tell the user that Pepper must first load the map, which can take up to 30 seconds. Explain that after the map is loaded, Pepper will need to localize itself, and only then will it begin navigating to the target '$locationName'.")
                    }
                    !navManager.isLocalizationReady() -> {
                        put("status", "starting_localization_before_navigation")
                        put("message", "The map is loaded, but Pepper is not yet localized. Tell the user that Pepper will first orient itself within the map. Explain that once localization is complete, it will start navigating to the target '$locationName'.")
                    }
                    else -> {
                        put("status", "navigation_started_immediately")
                        put("message", "The map is loaded and Pepper is localized. Tell the user that Pepper is now starting its navigation directly to the target location '$locationName'.")
                    }
                }
            }.toString()

        } catch (e: Exception) {
            Log.e(TAG, "Error navigating to location", e)
            JSONObject().put("error", "Failed to navigate to location: ${e.message}").toString()
        }
    }

    override fun requiresApiKey(): Boolean = false

    override fun getApiKeyType(): String? = null

    /**
     * Load a location from internal storage
     */
    private fun loadLocationFromStorage(context: ToolContext, locationName: String): SavedLocation? {
        return try {
            val locationsDir = File(context.appContext.filesDir, "locations")
            val locationFile = File(locationsDir, "$locationName.loc")

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

