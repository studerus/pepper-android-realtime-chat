package io.github.anonymous.pepper_realtime.tools.navigation;

import android.util.Log;
import com.aldebaran.qi.Future;
import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.builder.LocalizeAndMapBuilder;
import com.aldebaran.qi.sdk.object.actuation.LocalizeAndMap;
import com.aldebaran.qi.sdk.object.power.FlapSensor;
import com.aldebaran.qi.sdk.object.power.FlapState;
import com.aldebaran.qi.sdk.object.power.Power;
import io.github.anonymous.pepper_realtime.manager.NavigationServiceManager;
import io.github.anonymous.pepper_realtime.tools.Tool;
import io.github.anonymous.pepper_realtime.tools.ToolContext;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Tool for creating environment maps for robot navigation.
 * Uses manual mapping process where user guides robot through the environment.
 */
public class CreateEnvironmentMapTool implements Tool {
    
    private static final String TAG = "CreateEnvironmentMapTool";
    private static final String ACTIVE_MAP_NAME = "default_map";
    
    // Static reference to current mapping operation for tool coordination
    private static LocalizeAndMap currentLocalizeAndMap = null;
    private static Future<Void> currentMappingFuture = null;

    @Override
    public String getName() {
        return "create_environment_map";
    }

    @Override
    public JSONObject getDefinition() {
        try {
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", getName());
            tool.put("description", "Create a detailed map of the current environment that Pepper can use for navigation. Uses a single global map name internally.");
            
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
        
        // Check if charging flap is open (mapping requires movement)
        if (isChargingFlapOpen(context)) {
            return new JSONObject().put("error", "Cannot create map while charging flap is open. Mapping requires robot movement. Please close the charging flap first.").toString();
        }

        Log.i(TAG, "Starting environment mapping prerequisite checks for: " + ACTIVE_MAP_NAME);

        // Get the NavigationServiceManager from the context
        NavigationServiceManager navManager = context.getNavigationServiceManager();
        if (navManager == null) {
            return new JSONObject().put("error", "NavigationService is not available.").toString();
        }

        // Asynchronously stop any current localization first
        navManager.stopCurrentLocalization().thenConsume(future -> {
            if (future.hasError()) {
                Log.w(TAG, "Stopping previous localization failed, but proceeding anyway.", future.getError());
            } else {
                Log.i(TAG, "Previous localization stopped successfully. Proceeding with mapping.");
            }

            // This part now runs after the old localization has been stopped
            // CRITICAL: Enter localization mode to suppress gestures and autonomous abilities during mapping
            context.notifyServiceStateChange("enterLocalizationMode");

            try {
                // Start actual LocalizeAndMap for scanning animation and mapping
                if (!startLocalizeAndMap(context)) {
                    context.notifyServiceStateChange("resumeNormalOperation");
                    // Can't return a value here, but can send an update to the user
                    context.sendAsyncUpdate("[MAPPING ERROR] Failed to start mapping. Another mapping process might be active or the robot is not ready.", true);
                    return;
                }

                // Clear all existing locations when starting a new map
                // New map = new coordinate system = old locations become invalid
                clearAllLocations(context);

            } catch (Exception e) {
                Log.e(TAG, "Error starting manual mapping", e);
                context.notifyServiceStateChange("resumeNormalOperation"); // Restore services on error
                context.sendAsyncUpdate("[MAPPING ERROR] An unexpected error occurred: " + e.getMessage(), true);
            }
        });

        // Return immediately with a confirmation that the process has started.
        // The detailed status will follow in async updates.
        JSONObject result = new JSONObject();
        result.put("status", "Mapping process initiated");
        result.put("message", "Pepper is preparing to create a new map. It will first stop any ongoing localization, then begin the mapping process. You will be notified when it's ready.");
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

    /**
     * Start LocalizeAndMap with scanning animation
     */
    private boolean startLocalizeAndMap(ToolContext context) {
        try {
            QiContext qiContext = (com.aldebaran.qi.sdk.QiContext) context.getQiContext();
            if (qiContext == null) {
                Log.e(TAG, "Cannot start LocalizeAndMap - QiContext is null");
                return false;
            }
            
            // Stop any existing mapping
            stopCurrentMapping();
            
            Log.i(TAG, "Building LocalizeAndMap action...");
            currentLocalizeAndMap = LocalizeAndMapBuilder.with(qiContext).build();
            
            // Add status listener for localization feedback
            currentLocalizeAndMap.addOnStatusChangedListener(status -> {
                Log.i(TAG, "LocalizeAndMap status changed: " + status);
                switch (status) {
                    case NOT_STARTED:
                        Log.i(TAG, "LocalizeAndMap not started yet");
                        break;
                    case SCANNING:
                        Log.i(TAG, "Robot scanning environment - performing initial localization");
                        context.sendAsyncUpdate("[MAPPING STATUS] Pepper is scanning the environment to determine its position. Please wait...", false);
                        break;
                    case LOCALIZED:
                        Log.i(TAG, "Robot successfully localized - mapping active");
                        
                        // Update navigation status to reflect successful localization during mapping
                        context.notifyServiceStateChange("mappingLocalized");
                        
                        // Notify user that robot is ready to be guided (third person to avoid AI confusion)
                        context.sendAsyncUpdate(
                            "✅ Localization complete! Pepper is now ready to be guided through the environment. " +
                            "Please give movement commands like 'move forward 2 meters', 'turn left 90 degrees', etc. " +
                            "You can also say 'save current location as [name]' to mark important spots. " +
                            "When done exploring, say 'finish the map' to complete the mapping process.", 
                            true // Request response to acknowledge
                        );
                        break;
                }
            });
            
            Log.i(TAG, "Starting LocalizeAndMap with scanning animation...");
            currentMappingFuture = currentLocalizeAndMap.async().run();
            
            // Handle completion/errors asynchronously
            currentMappingFuture.thenConsume(future -> {
                if (future.hasError()) {
                    Log.e(TAG, "LocalizeAndMap failed", future.getError());
                } else {
                    Log.i(TAG, "LocalizeAndMap completed successfully");
                }
            });
            
            Log.i(TAG, "LocalizeAndMap started - robot should perform scanning animation");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to start LocalizeAndMap", e);
            return false;
        }
    }
    
    /**
     * Stop current mapping operation
     */
    private static void stopCurrentMapping() {
        if (currentMappingFuture != null && !currentMappingFuture.isDone()) {
            try {
                Log.i(TAG, "Stopping current mapping operation...");
                currentMappingFuture.requestCancellation();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping current mapping", e);
            }
        }
        currentMappingFuture = null;
        currentLocalizeAndMap = null;
    }
    
    /**
     * Get current LocalizeAndMap for other tools (like FinishEnvironmentMapTool)
     */
    public static LocalizeAndMap getCurrentLocalizeAndMap() {
        return currentLocalizeAndMap;
    }
    
    /**
     * Expose current mapping future for coordinated cancellation
     */
    public static Future<Void> getCurrentMappingFuture() {
        return currentMappingFuture;
    }
    
    /**
     * Check if the charging flap is open, which prevents movement for safety reasons
     */
    private boolean isChargingFlapOpen(ToolContext context) {
        try {
            Power power = ((com.aldebaran.qi.sdk.QiContext) context.getQiContext()).getPower();
            FlapSensor chargingFlap = power.getChargingFlap();
            
            if (chargingFlap != null) {
                FlapState flapState = chargingFlap.getState();
                boolean isOpen = flapState.getOpen();
                Log.d(TAG, "Charging flap status: " + (isOpen ? "OPEN (movement blocked)" : "CLOSED (movement allowed)"));
                return isOpen;
            } else {
                Log.d(TAG, "No charging flap sensor available - assuming movement is allowed");
                return false; // Assume closed if sensor not available
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not check charging flap status: " + e.getMessage(), e);
            return false; // Allow movement if check fails to avoid false blocking
        }
    }

    /**
     * Clear all existing locations since they become invalid with a new map
     */
    private void clearAllLocations(ToolContext context) {
        List<String> deletedLocations = new ArrayList<>();
        try {
            File locationsDir = new File(context.getAppContext().getFilesDir(), "locations");
            if (locationsDir.exists() && locationsDir.isDirectory()) {
                File[] files = locationsDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.getName().endsWith(".loc")) {
                            String locationName = file.getName().replace(".loc", "");
                            if (file.delete()) {
                                deletedLocations.add(locationName);
                                Log.i(TAG, "Deleted location: " + locationName);
                            } else {
                                Log.w(TAG, "Failed to delete location: " + locationName);
                            }
                        }
                    }
                }
            }
            
            if (!deletedLocations.isEmpty()) {
                Log.i(TAG, "Cleared " + deletedLocations.size() + " locations for new map: " + deletedLocations);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error clearing locations", e);
        }
        
    }

}
