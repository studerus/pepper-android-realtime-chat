package ch.fhnw.pepper_realtime.tools.navigation

import android.util.Log
import com.aldebaran.qi.sdk.QiContext
import ch.fhnw.pepper_realtime.data.NavigationStore
import com.aldebaran.qi.sdk.`object`.geometry.Transform
import ch.fhnw.pepper_realtime.tools.Tool
import ch.fhnw.pepper_realtime.tools.ToolContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Tool for saving robot's current location for future navigation.
 * Captures current position and orientation with optional description.
 */
class SaveCurrentLocationTool : Tool {

    companion object {
        private const val TAG = "SaveCurrentLocationTool"
    }

    override fun getName(): String = "save_current_location"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Save Pepper's current position with a name for future navigation. Use this when the user wants to save a location like 'kitchen', 'printer', 'entrance', etc. Call the function directly without announcing it.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("location_name", JSONObject()
                        .put("type", "string")
                        .put("description", "Name for this location (e.g. 'kitchen', 'printer', 'entrance')"))
                    put("description", JSONObject()
                        .put("type", "string")
                        .put("description", "Optional description of this location")
                        .put("default", ""))
                })
                put("required", JSONArray().put("location_name"))
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        val locationName = args.optString("location_name", "")
        val description = args.optString("description", "")

        // Validate location name
        if (locationName.trim().isEmpty()) {
            return JSONObject().put("error", "Location name is required").toString()
        }

        // Check if robot is ready
        if (context.isQiContextNotReady()) {
            return JSONObject().put("error", "Robot not ready").toString()
        }

        Log.i(TAG, "Saving current location: $locationName")

        return try {
            val qiContext = context.qiContext as QiContext
            val store = context.navigationStore
            val snapshot = store.writable()
            // LocalizeAndMap owns the map frame while a new map is being explored.
            val mapping = store.isMapping()
            val localized = if (mapping) {
                context.navigationServiceManager.mappingCoordinator.isLocalized
            } else {
                context.navigationServiceManager.isLocalizationReady()
            }
            if (!localized) {
                return JSONObject().put("error", "Location not saved: Pepper must be localized in a map first.").toString()
            }

            val robotFrame = qiContext.actuation.robotFrame()
            val mapFrame = qiContext.mapping.mapFrame()
            val currentTransform = robotFrame.computeTransform(mapFrame)?.transform
                ?: throw IllegalStateException("Current position relative to the map is unavailable")
            val translation = currentTransform.translation
                ?: throw IllegalStateException("Map position is unavailable")
            val rotation = currentTransform.rotation
                ?: throw IllegalStateException("Map orientation is unavailable")
            require(listOf(translation.x, translation.y, translation.z, rotation.x, rotation.y, rotation.z, rotation.w).all { it.isFinite() }) {
                "Map position or orientation is not finite"
            }
            val rotationNorm = rotation.x * rotation.x + rotation.y * rotation.y + rotation.z * rotation.z + rotation.w * rotation.w
            require(rotationNorm.isFinite() && rotationNorm > 0.0) { "Map orientation is invalid" }

            // Save location data
            val saved = saveLocationToStorage(context, snapshot, locationName, description, currentTransform)

            if (saved) {
                // IMPORTANT: Refresh the location provider to update the cache
                context.locationProvider.refreshLocations(context.appContext)

                JSONObject().apply {
                    put("status", "Location saved successfully")
                    put("location_name", locationName)
                    put("high_precision", false) // Currently standard precision only
                    if (description.isNotEmpty()) {
                        put("description", description)
                    }

                    val precisionNote = "" // Currently standard precision only
                    put("message", if (mapping) "Location '$locationName' is saved in the map draft. Finish the map to make it available for navigation." else String.format(
                        Locale.US,
                        "Location '%s' has been successfully saved%s%s. Navigation to this location is now available.",
                        locationName, if (description.isEmpty()) "" else " ($description)", precisionNote
                    ))
                }.also {
                    Log.i(TAG, "Location '$locationName' saved successfully (standard precision)")
                }.toString()
            } else {
                JSONObject().put("error", "Location could not be saved to storage").toString()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error saving location", e)
            JSONObject().put("error", "Failed to save location: ${e.message}").toString()
        }
    }


    /**
     * Save a location to internal storage
     */
    private fun saveLocationToStorage(
        context: ToolContext,
        snapshot: NavigationStore.Snapshot,
        locationName: String,
        description: String,
        transform: Transform
    ): Boolean {
        return try {
            // Save as JSON for easier debugging and cross-platform compatibility
            val locationData = JSONObject().apply {
                put("name", locationName)
                put("description", description)

                // Store transform data
                put("translation", JSONArray().apply {
                    put(transform.translation.x)
                    put(transform.translation.y)
                    put(transform.translation.z)
                })
                put("rotation", JSONArray().apply {
                    put(transform.rotation.x)
                    put(transform.rotation.y)
                    put(transform.rotation.z)
                    put(transform.rotation.w)
                })

                put("timestamp", System.currentTimeMillis())
                put("high_precision", false) // Currently standard precision only
            }

            val save = { context.navigationStore.writeLocation(snapshot, locationName, locationData.toString()) }
            val operation = context.operation
            if (operation != null) operation.guard(save) else save()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save location", e)
            false
        }
    }
}


