package io.github.anonymous.pepper_realtime.tools.navigation

import android.util.Log
import com.aldebaran.qi.sdk.QiContext
import io.github.anonymous.pepper_realtime.tools.Tool
import kotlinx.coroutines.*
import io.github.anonymous.pepper_realtime.tools.ToolContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Tool for finishing and saving environment maps.
 * Completes the mapping process and saves the map for navigation.
 */
class FinishEnvironmentMapTool : Tool {

    companion object {
        private const val TAG = "FinishEnvironmentMapTool"
        private const val ACTIVE_MAP_NAME = "default_map"
    }

    override fun getName(): String = "finish_environment_map"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Complete and save the current mapping process. Uses a single global map name internally.")
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

        Log.i(TAG, "Finishing environment mapping and saving map: $ACTIVE_MAP_NAME")

        // CRITICAL: Enter localization mode immediately to stop gestures and other services
        context.navigationServiceManager.handleServiceStateChange("enterLocalizationMode")
        Log.i(TAG, "Entered localization mode to suppress gestures during map finalization")

        // Send async update about map finalization starting
        context.sendAsyncUpdate(
            String.format(
                Locale.US,
                "[MAPPING STATUS] Map '%s' is being finalized and saved now. This may take a moment. Pepper will notify when it's ready.",
                ACTIVE_MAP_NAME
            ), false
        )

        // Implement mapping finalization on background I/O coroutine
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // 1) Get current mapping action and its future
                val lam = CreateEnvironmentMapTool.currentLocalizeAndMap
                val mappingFuture = CreateEnvironmentMapTool.currentMappingFuture

                if (lam == null || mappingFuture == null) {
                    Log.w(TAG, "No active mapping process to finish.")
                    context.sendAsyncUpdate("[MAPPING ERROR] No active mapping process was found to finish.", true)
                    return@launch
                }

                // 2) Attach the map dumping and saving logic to the future of the mapping action
                mappingFuture.thenConsume { _ ->
                    // This block executes AFTER the mapping action has terminated (either normally or via cancellation)
                    try {
                        Log.i(TAG, "Mapping action has terminated. Proceeding to dump and save the map.")
                        val qiContext = context.qiContext as? QiContext
                            ?: throw IllegalStateException("QiContext is null after mapping termination - cannot finalize map")

                        // 2a) Dump ExplorationMap now that the action is safely terminated
                        val explorationMap = lam.dumpMap()
                        Log.i(TAG, "ExplorationMap dumped successfully.")

                        // 2b) Persist explorationMap to storage
                        // Force garbage collection before heavy serialization
                        System.gc()
                        var serialized: String? = explorationMap.serialize()
                        val mapsDir = File(context.appContext.filesDir, "maps")
                        if (!mapsDir.exists()) {
                            mapsDir.mkdirs()
                        }
                        val mapFile = File(mapsDir, "$ACTIVE_MAP_NAME.map")
                        if (mapFile.exists()) {
                            Log.w(TAG, "Map '$ACTIVE_MAP_NAME' already exists and will be overwritten: ${mapFile.absolutePath}")
                        }
                        FileOutputStream(mapFile).use { fos ->
                            var data: ByteArray? = serialized!!.toByteArray(StandardCharsets.UTF_8)
                            fos.write(data)
                            fos.flush()
                            Log.i(TAG, "ExplorationMap persisted to: ${mapFile.absolutePath} (${data!!.size} bytes)")

                            // Clear serialized string from memory immediately
                            @Suppress("UNUSED_VALUE")
                            serialized = null
                            @Suppress("UNUSED_VALUE")
                            data = null
                            System.gc()
                        }

                        // 2c) Cache the new map, update UI, and start new localization via manager
                        val navManager = context.navigationServiceManager
                        Log.i(TAG, "Caching map directly via NavigationServiceManager and preparing UI/localization.")

                        // Cache with callback to ensure proper sequencing
                        navManager.cacheNewMap(explorationMap) {
                            try {
                                // Update UI on main thread
                                if (context.hasUi()) {
                                    context.toolHost.runOnUiThread {
                                        try {
                                            Log.i(TAG, "Updating map preview UI after caching...")
                                            context.toolHost.updateMapPreview()
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Failed to update map preview UI", e)
                                        }
                                    }
                                }

                                Log.i(TAG, "Starting localization after successful map caching...")
                                // Start localization via manager so actions/futures are tracked consistently
                                navManager.ensureLocalizationIfNeeded(
                                    qiContext,
                                    { // onLocalized
                                        try {
                                            Log.i(TAG, "Localization completed successfully - resuming normal operation")
                                            if (context.hasUi()) {
                                                context.toolHost.updateNavigationStatus("🗺️ Map: Ready", "🧭 Localization: Localized")
                                            }
                                            context.sendAsyncUpdate(
                                                "[ORIENTATION COMPLETED] Pepper is now localized and ready for navigation.",
                                                true
                                            )
                                            context.notifyServiceStateChange("resumeNormalOperation")
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Post-localization success handling failed", e)
                                        }
                                    },
                                    { // onFailed
                                        try {
                                            Log.e(TAG, "Localization failed after map caching")
                                            if (context.hasUi()) {
                                                context.toolHost.updateNavigationStatus("🗺️ Map: Ready", "🧭 Localization: Failed")
                                            }
                                            context.sendAsyncUpdate(
                                                "[ORIENTATION ERROR] Localization failed after saving the map. Please try again.",
                                                true
                                            )
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Post-localization failure handling failed", e)
                                        }
                                    }
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Post-cache actions failed", e)
                            }
                        }

                        // 2d) Inform user but keep services in localization mode until localization completes
                        if (context.hasUi()) {
                            context.toolHost.updateNavigationStatus("🗺️ Map: Ready", "🧭 Localization: Localizing...")
                        }
                        context.sendAsyncUpdate(
                            String.format(
                                Locale.US,
                                "[MAP SAVED] Map '%s' has been saved. Pepper will now orient itself; navigation will be available once orientation completes.",
                                ACTIVE_MAP_NAME
                            ), true
                        )
                        Log.i(TAG, "Map '$ACTIVE_MAP_NAME' finalization process initiated successfully.")

                    } catch (t: Throwable) {
                        Log.e(TAG, "Error during map finalization callback", t)
                        context.notifyServiceStateChange("resumeNormalOperation")
                        if (context.hasUi()) {
                            context.sendAsyncUpdate("[MAPPING ERROR] Failed to finish mapping: ${t.message}", true)
                        }
                    }
                }

                // 3) Now that the callback is attached, request cancellation of the mapping action.
                // The logic in thenConsume() will execute once the cancellation is complete.
                Log.i(TAG, "Requesting cancellation of the current mapping action to finalize it.")
                if (!mappingFuture.isDone) {
                    mappingFuture.requestCancellation()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error during map finalization setup", e)
                context.notifyServiceStateChange("resumeNormalOperation")
                if (context.hasUi()) {
                    context.sendAsyncUpdate("[MAPPING ERROR] Failed to finish mapping: ${e.message}", true)
                }
            }
        }

        // Return immediately to keep WebSocket responsive
        return JSONObject().apply {
            put("status", "Map finalization started")
            put("message", String.format(
                Locale.US,
                "Map '%s' is being finalized and saved now. Pepper will notify when it's ready.",
                ACTIVE_MAP_NAME
            ))
        }.toString()
    }

}


