package io.github.anonymous.pepper_realtime.tools.navigation

import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.LocalizeAndMapBuilder
import com.aldebaran.qi.sdk.`object`.actuation.LocalizeAndMap
import io.github.anonymous.pepper_realtime.tools.BaseTool
import io.github.anonymous.pepper_realtime.tools.ToolContext
import org.json.JSONObject
import java.io.File

/**
 * Tool for creating environment maps for robot navigation.
 * Uses manual mapping process where user guides robot through the environment.
 */
class CreateEnvironmentMapTool : BaseTool() {

    companion object {
        private const val TAG = "CreateEnvironmentMapTool"
        private const val ACTIVE_MAP_NAME = "default_map"

        // Static reference to current mapping operation for tool coordination
        @Volatile
        @JvmStatic
        var currentLocalizeAndMap: LocalizeAndMap? = null
            private set

        @Volatile
        @JvmStatic
        var currentMappingFuture: Future<Void>? = null
            private set

        /**
         * Stop current mapping operation
         */
        @JvmStatic
        fun stopCurrentMapping() {
            currentMappingFuture?.let { future ->
                if (!future.isDone) {
                    try {
                        Log.i(TAG, "Stopping current mapping operation...")
                        future.requestCancellation()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error stopping current mapping", e)
                    }
                }
            }
            currentMappingFuture = null
            currentLocalizeAndMap = null
        }
    }

    override fun getName(): String = "create_environment_map"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Create a detailed map of the current environment that Pepper can use for navigation. Uses a single global map name internally.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        // Check if robot is ready
        if (context.isQiContextNotReady()) {
            return JSONObject().put("error", "Robot not ready").toString()
        }

        // Check if charging flap is open (mapping requires movement)
        if (isChargingFlapOpen(context)) {
            return JSONObject().put("error", "Cannot create map while charging flap is open. Mapping requires robot movement. Please close the charging flap first.").toString()
        }

        Log.i(TAG, "Starting environment mapping prerequisite checks for: $ACTIVE_MAP_NAME")

        // Get the NavigationServiceManager from the context
        val navManager = context.navigationServiceManager

        // Asynchronously stop any current localization first
        navManager.stopCurrentLocalization().thenConsume { future ->
            if (future.hasError()) {
                Log.w(TAG, "Stopping previous localization failed, but proceeding anyway.", future.error)
            } else {
                Log.i(TAG, "Previous localization stopped successfully. Proceeding with mapping.")
            }

            // This part now runs after the old localization has been stopped
            // CRITICAL: Enter localization mode to suppress gestures and autonomous abilities during mapping
            context.notifyServiceStateChange("enterLocalizationMode")

            try {
                // Start actual LocalizeAndMap for scanning animation and mapping
                if (!startLocalizeAndMap(context)) {
                    context.notifyServiceStateChange("resumeNormalOperation")
                    // Can't return a value here, but can send an update to the user
                    context.sendAsyncUpdate("[MAPPING ERROR] Failed to start mapping. Another mapping process might be active or the robot is not ready.", true)
                    return@thenConsume
                }

                // Clear all existing locations when starting a new map
                // New map = new coordinate system = old locations become invalid
                clearAllLocations(context)

            } catch (e: Exception) {
                Log.e(TAG, "Error starting manual mapping", e)
                context.notifyServiceStateChange("resumeNormalOperation") // Restore services on error
                context.sendAsyncUpdate("[MAPPING ERROR] An unexpected error occurred: ${e.message}", true)
            }
        }

        // Return immediately with a confirmation that the process has started.
        // The detailed status will follow in async updates.
        return JSONObject().apply {
            put("status", "Mapping process initiated")
            put("message", "Pepper is preparing to create a new map. It will first stop any ongoing localization, then begin the mapping process. You will be notified when it's ready.")
        }.toString()
    }

    override fun requiresApiKey(): Boolean = false

    override fun getApiKeyType(): String? = null

    /**
     * Start LocalizeAndMap with scanning animation
     */
    private fun startLocalizeAndMap(context: ToolContext): Boolean {
        return try {
            val qiContext = context.qiContext as? QiContext
            if (qiContext == null) {
                Log.e(TAG, "Cannot start LocalizeAndMap - QiContext is null")
                return false
            }

            // Stop any existing mapping
            stopCurrentMapping()

            Log.i(TAG, "Building LocalizeAndMap action...")
            currentLocalizeAndMap = LocalizeAndMapBuilder.with(qiContext).build()

            // Add status listener for localization feedback
            currentLocalizeAndMap?.addOnStatusChangedListener { status ->
                Log.i(TAG, "LocalizeAndMap status changed: $status")
                when (status) {
                    com.aldebaran.qi.sdk.`object`.actuation.LocalizationStatus.NOT_STARTED -> {
                        Log.i(TAG, "LocalizeAndMap not started yet")
                    }
                    com.aldebaran.qi.sdk.`object`.actuation.LocalizationStatus.SCANNING -> {
                        Log.i(TAG, "Robot scanning environment - performing initial localization")
                        context.sendAsyncUpdate("[MAPPING STATUS] Pepper is scanning the environment to determine its position. Please wait...", false)
                    }
                    com.aldebaran.qi.sdk.`object`.actuation.LocalizationStatus.LOCALIZED -> {
                        Log.i(TAG, "Robot successfully localized - mapping active")

                        // Update navigation status to reflect successful localization during mapping
                        context.notifyServiceStateChange("mappingLocalized")

                        // Notify user that robot is ready to be guided (third person to avoid AI confusion)
                        context.sendAsyncUpdate(
                            "✅ Localization complete! Pepper is now ready to be guided through the environment. " +
                                    "Please give movement commands like 'move forward 2 meters', 'turn left 90 degrees', etc. " +
                                    "You can also say 'save current location as [name]' to mark important spots. " +
                                    "When done exploring, say 'finish the map' to complete the mapping process.",
                            true // Request response to acknowledge
                        )
                    }
                    else -> {}
                }
            }

            Log.i(TAG, "Starting LocalizeAndMap with scanning animation...")
            currentMappingFuture = currentLocalizeAndMap?.async()?.run()

            // Handle completion/errors asynchronously
            currentMappingFuture?.thenConsume { future ->
                if (future.hasError()) {
                    Log.e(TAG, "LocalizeAndMap failed", future.error)
                } else {
                    Log.i(TAG, "LocalizeAndMap completed successfully")
                }
            }

            Log.i(TAG, "LocalizeAndMap started - robot should perform scanning animation")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start LocalizeAndMap", e)
            false
        }
    }

    /**
     * Check if the charging flap is open, which prevents movement for safety reasons
     */
    private fun isChargingFlapOpen(context: ToolContext): Boolean {
        return try {
            val qiContext = context.qiContext as QiContext
            val power = qiContext.power
            val chargingFlap = power.chargingFlap

            if (chargingFlap != null) {
                val flapState = chargingFlap.state
                val isOpen = flapState.open
                Log.d(TAG, "Charging flap status: ${if (isOpen) "OPEN (movement blocked)" else "CLOSED (movement allowed)"}")
                isOpen
            } else {
                Log.d(TAG, "No charging flap sensor available - assuming movement is allowed")
                false // Assume closed if sensor not available
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not check charging flap status: ${e.message}", e)
            false // Allow movement if check fails to avoid false blocking
        }
    }

    /**
     * Clear all existing locations since they become invalid with a new map
     */
    private fun clearAllLocations(context: ToolContext) {
        val deletedLocations = mutableListOf<String>()
        try {
            val locationsDir = File(context.appContext.filesDir, "locations")
            if (locationsDir.exists() && locationsDir.isDirectory) {
                locationsDir.listFiles()?.forEach { file ->
                    if (file.name.endsWith(".loc")) {
                        val locationName = file.name.replace(".loc", "")
                        if (file.delete()) {
                            deletedLocations.add(locationName)
                            Log.i(TAG, "Deleted location: $locationName")
                        } else {
                            Log.w(TAG, "Failed to delete location: $locationName")
                        }
                    }
                }
            }

            if (deletedLocations.isNotEmpty()) {
                Log.i(TAG, "Cleared ${deletedLocations.size} locations for new map: $deletedLocations")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error clearing locations", e)
        }
    }
}

