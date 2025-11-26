package io.github.anonymous.pepper_realtime.tools.navigation;

import android.util.Log;
import com.aldebaran.qi.Future;
import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.object.actuation.ExplorationMap;
import com.aldebaran.qi.sdk.object.actuation.LocalizeAndMap;
import java.nio.charset.StandardCharsets;
import io.github.anonymous.pepper_realtime.tools.BaseTool;
import io.github.anonymous.pepper_realtime.tools.ToolContext;
import io.github.anonymous.pepper_realtime.manager.ThreadManager;
import io.github.anonymous.pepper_realtime.manager.NavigationServiceManager;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Tool for finishing and saving environment maps.
 * Completes the mapping process and saves the map for navigation.
 */
public class FinishEnvironmentMapTool extends BaseTool {
    
    private static final String TAG = "FinishEnvironmentMapTool";
    private static final String ACTIVE_MAP_NAME = "default_map";

    @Override
    public String getName() {
        return "finish_environment_map";
    }

    @Override
    public JSONObject getDefinition() {
        try {
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", getName());
            tool.put("description", "Complete and save the current mapping process. Uses a single global map name internally.");
            
            JSONObject params = new JSONObject();
            params.put("type", "object");
            params.put("properties", new JSONObject());
            tool.put("parameters", params);
            
            return tool;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool definition", e);
        }
    }

    @Override
    public String execute(JSONObject args, ToolContext context) throws Exception {
        // Check if robot is ready
        if (context.isQiContextNotReady()) {
            return new JSONObject().put("error", "Robot not ready").toString();
        }
        
        Log.i(TAG, "Finishing environment mapping and saving map: " + ACTIVE_MAP_NAME);
        
        // CRITICAL: Enter localization mode immediately to stop gestures and other services
        if (context.getNavigationServiceManager() != null) {
            context.getNavigationServiceManager().handleServiceStateChange("enterLocalizationMode");
            Log.i(TAG, "Entered localization mode to suppress gestures during map finalization");
        }
        
        // Send async update about map finalization starting
        context.sendAsyncUpdate(String.format(Locale.US,
            "[MAPPING STATUS] Map '%s' is being finalized and saved now. This may take a moment. Pepper will notify when it's ready.", 
            ACTIVE_MAP_NAME), false);
        
        // Implement mapping finalization on background I/O thread
        ThreadManager.getInstance().executeIO(() -> {
            try {
                // 1) Get current mapping action and its future
                LocalizeAndMap lam = CreateEnvironmentMapTool.getCurrentLocalizeAndMap();
                Future<Void> mappingFuture = CreateEnvironmentMapTool.getCurrentMappingFuture();

                if (lam == null || mappingFuture == null) {
                    Log.w(TAG, "No active mapping process to finish.");
                    context.sendAsyncUpdate("[MAPPING ERROR] No active mapping process was found to finish.", true);
                    return;
                }

                // 2) Attach the map dumping and saving logic to the future of the mapping action
                mappingFuture.thenConsume(f -> {
                    // This block executes AFTER the mapping action has terminated (either normally or via cancellation)
                    try {
                        Log.i(TAG, "Mapping action has terminated. Proceeding to dump and save the map.");
                        QiContext qiContext = (com.aldebaran.qi.sdk.QiContext) context.getQiContext();
                        if (qiContext == null) {
                            throw new IllegalStateException("QiContext is null after mapping termination - cannot finalize map");
                        }

                        // 2a) Dump ExplorationMap now that the action is safely terminated
                        ExplorationMap explorationMap = lam.dumpMap();
                        Log.i(TAG, "ExplorationMap dumped successfully.");

                        // 2b) Persist explorationMap to storage
                        // Force garbage collection before heavy serialization
                        System.gc();
                        String serialized = explorationMap.serialize();
                        java.io.File mapsDir = new java.io.File(context.getAppContext().getFilesDir(), "maps");
                        if (!mapsDir.exists()) { //noinspection ResultOfMethodCallIgnored
                            mapsDir.mkdirs();
                        }
                        java.io.File mapFile = new java.io.File(mapsDir, ACTIVE_MAP_NAME + ".map");
                        if (mapFile.exists()) {
                            Log.w(TAG, "Map '" + ACTIVE_MAP_NAME + "' already exists and will be overwritten: " + mapFile.getAbsolutePath());
                        }
                        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(mapFile)) {
                            byte[] data = serialized.getBytes(StandardCharsets.UTF_8);
                            fos.write(data);
                            fos.flush();
                            Log.i(TAG, "ExplorationMap persisted to: " + mapFile.getAbsolutePath() + " (" + data.length + " bytes)");

                            // Clear serialized string from memory immediately
                            //noinspection UnusedAssignment
                            serialized = null;
                            //noinspection UnusedAssignment
                            data = null;
                            System.gc();
                        }

                        // 2c) Cache the new map, update UI, and start new localization via manager
                        if (context.getNavigationServiceManager() != null) {
                            Log.i(TAG, "Caching map directly via NavigationServiceManager and preparing UI/localization.");
                            final NavigationServiceManager navManager = context.getNavigationServiceManager();

                            // Cache with callback to ensure proper sequencing
                            navManager.cacheNewMap(explorationMap, () -> {
                                try {
                                    // Update UI on main thread
                                    if (context.hasUi()) {
                                        context.getToolHost().runOnUiThread(() -> {
                                        try {
                                            Log.i(TAG, "Updating map preview UI after caching...");
                                                context.getToolHost().updateMapPreview();
                                        } catch (Exception e) {
                                            Log.e(TAG, "Failed to update map preview UI", e);
                                        }
                                    });
                                    }

                                    Log.i(TAG, "Starting localization after successful map caching...");
                                    // Start localization via manager so actions/futures are tracked consistently
                                    navManager.ensureLocalizationIfNeeded(qiContext,
                                            () -> {
                                                // onLocalized: resume normal operation and inform user
                                                try {
                                                    Log.i(TAG, "Localization completed successfully - resuming normal operation");
                                                    if (context.hasUi()) {
                                                        context.getToolHost().updateNavigationStatus("🗺️ Map: Ready", "🧭 Localization: Localized");
                                                    }
                                                    context.sendAsyncUpdate(
                                                            "[ORIENTATION COMPLETED] Pepper is now localized and ready for navigation.",
                                                            true
                                                    );
                                                    context.notifyServiceStateChange("resumeNormalOperation");
                                                } catch (Exception e) {
                                                    Log.e(TAG, "Post-localization success handling failed", e);
                                                }
                                            },
                                            () -> {
                                                // onFailed: keep localization mode, inform user
                                                try {
                                                    Log.e(TAG, "Localization failed after map caching");
                                                    if (context.hasUi()) {
                                                        context.getToolHost().updateNavigationStatus("🗺️ Map: Ready", "🧭 Localization: Failed");
                                                    }
                                                    context.sendAsyncUpdate(
                                                            "[ORIENTATION ERROR] Localization failed after saving the map. Please try again.",
                                                            true
                                                    );
                                                } catch (Exception e) {
                                                    Log.e(TAG, "Post-localization failure handling failed", e);
                                                }
                                            }
                                    );
                                } catch (Exception e) {
                                    Log.e(TAG, "Post-cache actions failed", e);
                                }
                            });
                        }

                        // 2d) Inform user but keep services in localization mode until localization completes
                        if (context.hasUi()) {
                            context.getToolHost().updateNavigationStatus("🗺️ Map: Ready", "🧭 Localization: Localizing...");
                        }
                            context.sendAsyncUpdate(String.format(Locale.US,
                                "[MAP SAVED] Map '%s' has been saved. Pepper will now orient itself; navigation will be available once orientation completes.",
                                ACTIVE_MAP_NAME), true);
                        Log.i(TAG, "Map '" + ACTIVE_MAP_NAME + "' finalization process initiated successfully.");

                    } catch (Throwable t) {
                        Log.e(TAG, "Error during map finalization callback", t);
                        context.notifyServiceStateChange("resumeNormalOperation");
                        if (context.hasUi()) {
                            context.sendAsyncUpdate("[MAPPING ERROR] Failed to finish mapping: " + t.getMessage(), true);
                        }
                    }
                });

                // 3) Now that the callback is attached, request cancellation of the mapping action.
                // The logic in thenConsume() will execute once the cancellation is complete.
                Log.i(TAG, "Requesting cancellation of the current mapping action to finalize it.");
                if (!mappingFuture.isDone()) {
                    mappingFuture.requestCancellation();
                }

            } catch (Exception e) {
                Log.e(TAG, "Error during map finalization setup", e);
                context.notifyServiceStateChange("resumeNormalOperation");
                if (context.hasUi()) {
                    context.sendAsyncUpdate("[MAPPING ERROR] Failed to finish mapping: " + e.getMessage(), true);
                }
            }
        });
        
        // Return immediately to keep WebSocket responsive
        JSONObject result = new JSONObject();
        result.put("status", "Map finalization started");
        result.put("message", String.format(Locale.US,
            "Map '%s' is being finalized and saved now. Pepper will notify when it's ready.", ACTIVE_MAP_NAME));
        return result.toString();
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public String getApiKeyType() {
        return null;
    }
}
