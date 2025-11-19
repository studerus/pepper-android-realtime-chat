package io.github.anonymous.pepper_realtime.tools.navigation;

import android.util.Log;

import com.aldebaran.qi.Future;
import com.aldebaran.qi.sdk.QiContext;

import io.github.anonymous.pepper_realtime.manager.NavigationServiceManager;
import io.github.anonymous.pepper_realtime.robot.RobotSafetyGuard;
import io.github.anonymous.pepper_realtime.data.SavedLocation;
import io.github.anonymous.pepper_realtime.tools.Tool;
import io.github.anonymous.pepper_realtime.tools.ToolContext;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Tool for navigating to previously saved locations.
 * Handles lazy map loading and localization if needed.
 */
public class NavigateToLocationTool implements Tool {
    
    private static final String TAG = "NavigateToLocationTool";

    @Override
    public String getName() {
        return "navigate_to_location";
    }

    @Override
    public JSONObject getDefinition() {
        try {
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", getName());
            
            // Dynamic description would require context, so using static description for now
            tool.put("description", "Navigate Pepper to a previously saved location. Use this when the user wants to go to a specific named place.");
            
            JSONObject params = new JSONObject();
            params.put("type", "object");
            
            JSONObject properties = new JSONObject();
            properties.put("location_name", new JSONObject()
                .put("type", "string")
                .put("description", "Name of the saved location to navigate to"));
            properties.put("speed", new JSONObject()
                .put("type", "number")
                .put("description", "Optional movement speed in m/s (0.1-0.55)")
                .put("minimum", 0.1)
                .put("maximum", 0.55)
                .put("default", 0.3));
            
            params.put("properties", properties);
            params.put("required", new JSONArray().put("location_name"));
            tool.put("parameters", params);
            
            return tool;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool definition", e);
        }
    }

    @Override
    public String execute(JSONObject args, ToolContext context) throws Exception {
        String locationName = args.optString("location_name", "");
        double speed = args.optDouble("speed", 0.3);
        
        // Validate location name
        if (locationName.trim().isEmpty()) {
            return new JSONObject().put("error", "Location name is required").toString();
        }
        
        // Validate speed
        if (speed < 0.1 || speed > 0.55) {
            return new JSONObject().put("error", "Speed must be between 0.1 and 0.55 m/s").toString();
        }
        
        // Check if robot is ready
        if (context.isQiContextNotReady()) {
            return new JSONObject().put("error", "Robot not ready").toString();
        }
        
        RobotSafetyGuard.Result safety = RobotSafetyGuard.evaluateMovementSafety((com.aldebaran.qi.sdk.QiContext) context.getQiContext());
        if (!safety.isOk()) {
            String message = safety.message != null ? safety.message : "Navigation blocked by safety check";
            return new JSONObject().put("error", message).toString();
        }
        
        Log.i(TAG, "Navigating to location: " + locationName);
        
        try {
            // Load the saved location
            SavedLocation savedLocation = loadLocationFromStorage(context, locationName);
            if (savedLocation == null) {
                return new JSONObject().put("error", "Location '" + locationName + "' not found. Please save the location first.").toString();
            }
            
            // Get dependencies
            NavigationServiceManager navManager = context.getNavigationServiceManager();
            if (navManager == null) {
                return new JSONObject().put("error", "Navigation service not available").toString();
            }
            QiContext qiContext = (com.aldebaran.qi.sdk.QiContext) context.getQiContext();
            
            // --- PRE-CHECK FOR RACE CONDITION ---
            // Check if the robot is already localized to prevent sending a redundant success message later.
            final boolean wasAlreadyLocalized = navManager.isLocalizationReady();

            // --- ASYNCHRONOUS NAVIGATION FLOW ---
            // This part runs in the background. The initial return value is determined synchronously below.
            Log.i(TAG, "Step 1: Starting navigation preparation - ensuring map is loaded.");
            navManager.ensureMapLoadedIfNeeded(qiContext, context.getAppContext(), () ->
                context.sendAsyncUpdate("[MAP LOADED] Pepper has loaded the map into memory and will now orient itself.", true)
            ).thenConsume(mapReadyFuture -> {
                try {
                    if (mapReadyFuture.hasError() || !Boolean.TRUE.equals(mapReadyFuture.getValue())) {
                        Log.e(TAG, "Step 1 FAILED: Map could not be loaded.", mapReadyFuture.getError());
                        context.sendAsyncUpdate("[NAVIGATION ERROR] No usable map available. Please create a new map.", true);
                        return;
                    }

                    Log.i(TAG, "Step 1 SUCCESS: Map is loaded. Now, Step 2: Ensuring localization.");
                    navManager.ensureLocalizationIfNeeded(qiContext,
                        () -> { // onLocalized Callback
                            try {
                                Log.i(TAG, "Step 2 SUCCESS: Localization is confirmed. Now, Step 3: Starting actual navigation.");
                                // CRITICAL FIX: Only send this update if localization was NOT already complete when the tool was called.
                                if (!wasAlreadyLocalized) {
                                    String note = savedLocation.highPrecision ? " (high-precision location)" : "";
                                    context.sendAsyncUpdate(String.format(Locale.US,
                                            "[LOCALIZATION COMPLETED] Pepper is oriented and starting navigation to %s%s.",
                                            locationName, note), true);
                                }
                                
                                Future<NavigationServiceManager.MovementResult> navFuture = navManager.navigateToLocation(qiContext, savedLocation, (float) speed);
                                
                                navFuture.thenConsume(navResultFuture -> {
                                    try {
                                        NavigationServiceManager.MovementResult result = navResultFuture.getValue();
                                        boolean success = !navResultFuture.hasError() && result != null && result.success;
                                        String error = navResultFuture.hasError() ? navResultFuture.getError().getMessage() : (result != null ? result.error : "unknown error");
                                        
                                        String message;
                                        if (success) {
                                            message = String.format(Locale.US,
                                                "[NAVIGATION COMPLETED] Pepper has arrived at %s.",
                                                locationName);
                                            Log.i(TAG, "Step 3 SUCCESS: Navigation to " + locationName + " completed.");
                                        } else {
                                            String friendlyError = translateNavigationError(error);
                                            
                                            // Build the base error message
                                            StringBuilder messageBuilder = new StringBuilder();
                                            messageBuilder.append(String.format(Locale.US,
                                                "[NAVIGATION FAILED] Could not reach %s. Reason: %s.",
                                                locationName, friendlyError));
                                            
                                            // Add vision analysis suggestion for obstacle-related errors in the same message
                                            boolean isObstacleError = isObstacleRelatedNavigationError(error);
                                            Log.i(TAG, "Navigation error: '" + error + "' -> obstacle-related: " + isObstacleError);
                                            if (isObstacleError) {
                                                messageBuilder.append(String.format(Locale.US,
                                                    " Use vision analysis to identify what is blocking your path - " +
                                                    "look around your current position, starting with (%.2f, %.2f, %.2f) in front of you, to see what obstacles are nearby.",
                                                    1.0, 0.0, 0.0)); // Always look 1m forward from current position
                                            }
                                            
                                            message = messageBuilder.toString();
                                            Log.e(TAG, "Step 3 FAILED: Navigation to " + locationName + " failed. Reason: " + error);
                                        }
                                        context.sendAsyncUpdate(message, true);
                                    } catch (Exception e) {
                                         Log.e(TAG, "Error processing navigation result future.", e);
                                    }
                                });
                            } catch (Exception e) {
                                Log.e(TAG, "Step 3 FAILED: Error initiating navigation.", e);
                                context.sendAsyncUpdate("[NAVIGATION ERROR] Failed to start navigation: " + e.getMessage(), true);
                            }
                        },
                        () -> { // onFailed Callback
                            Log.e(TAG, "Step 2 FAILED: Localization failed. Navigation cancelled.");
                            context.sendAsyncUpdate("[LOCALIZATION FAILED] Orientation failed. Navigation cannot start.", true);
                        }
                    );
                } catch (Exception e) {
                    Log.e(TAG, "Error in map loading callback chain.", e);
                }
            });
            
            // --- SYNCHRONOUS INITIAL RESPONSE ---
            // Determine the immediate response based on the current state, before async operations begin.
            JSONObject result = new JSONObject();
            if (!navManager.isMapLoaded()) {
                result.put("status", "starting_full_navigation_setup");
                result.put("message", "The environment map is not currently loaded. Tell the user that Pepper must first load the map, which can take up to 30 seconds. Explain that after the map is loaded, Pepper will need to localize itself, and only then will it begin navigating to the target '" + locationName + "'.");
            } else if (!navManager.isLocalizationReady()) {
                result.put("status", "starting_localization_before_navigation");
                result.put("message", "The map is loaded, but Pepper is not yet localized. Tell the user that Pepper will first orient itself within the map. Explain that once localization is complete, it will start navigating to the target '" + locationName + "'.");
            } else {
                result.put("status", "navigation_started_immediately");
                result.put("message", "The map is loaded and Pepper is localized. Tell the user that Pepper is now starting its navigation directly to the target location '" + locationName + "'.");
            }
            return result.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "Error navigating to location", e);
            return new JSONObject().put("error", "Failed to navigate to location: " + e.getMessage()).toString();
        }
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
     * Load a location from internal storage
     */
    private SavedLocation loadLocationFromStorage(ToolContext context, String locationName) {
        try {
            File locationsDir = new File(context.getAppContext().getFilesDir(), "locations");
            File locationFile = new File(locationsDir, locationName + ".loc");
            
            if (!locationFile.exists()) {
                Log.w(TAG, "Location file not found: " + locationFile.getAbsolutePath());
                return null;
            }
            
            // Read JSON data
            StringBuilder content = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(locationFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line);
                }
            }
            
            JSONObject locationData = new JSONObject(content.toString());
            
            // Create SavedLocation object
            SavedLocation savedLocation = new SavedLocation();
            savedLocation.name = locationData.optString("name", locationName);
            savedLocation.description = locationData.optString("description", "");
            savedLocation.timestamp = locationData.optLong("timestamp", 0);
            savedLocation.highPrecision = locationData.optBoolean("high_precision", false);
            
            JSONArray translationArray = locationData.getJSONArray("translation");
            savedLocation.translation = new double[]{
                translationArray.getDouble(0),
                translationArray.getDouble(1),
                translationArray.getDouble(2)
            };
            
            JSONArray rotationArray = locationData.getJSONArray("rotation");
            savedLocation.rotation = new double[]{
                rotationArray.getDouble(0),
                rotationArray.getDouble(1),
                rotationArray.getDouble(2),
                rotationArray.getDouble(3)
            };
            
            Log.d(TAG, "Location loaded from: " + locationFile.getAbsolutePath());
            return savedLocation;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to load location", e);
            return null;
        }
    }

    // Localization is handled centrally by NavigationServiceManager

    /**
     * Check if the navigation error is related to obstacles that could benefit from vision analysis
     */
    private boolean isObstacleRelatedNavigationError(String error) {
        if (error == null || error.isEmpty()) {
            return true; // Default fallback suggests obstacles
        }
        
        String lowerError = error.toLowerCase();
        
        // Navigation errors that are likely obstacle-related and would benefit from vision analysis
        return lowerError.contains("obstacle") || lowerError.contains("blocked") || 
               lowerError.contains("collision") || lowerError.contains("bump") ||
               lowerError.contains("unreachable") || lowerError.contains("no path") || 
               lowerError.contains("path planning") || lowerError.contains("navigation failed") ||
               lowerError.contains("timeout") || lowerError.contains("took too long") ||
               lowerError.contains("safety") || lowerError.contains("emergency") ||
               lowerError.contains("goto failed") || lowerError.contains("failed to complete");
        // Note: Cancelled/localization errors are NOT obstacle-related, so no vision suggestion
    }

    /**
     * Translates technical QiSDK navigation errors into user-friendly messages
     */
    private String translateNavigationError(String technicalError) {
        if (technicalError == null || technicalError.isEmpty()) {
            return "Unknown navigation error occurred";
        }
        
        String lowerError = technicalError.toLowerCase();
        
        if (lowerError.contains("obstacle") || lowerError.contains("blocked")) {
            return "My path to the destination is blocked by obstacles";
        } else if (lowerError.contains("unreachable") || lowerError.contains("no path")) {
            return "The destination cannot be reached from my current position";
        } else if (lowerError.contains("timeout") || lowerError.contains("took too long")) {
            return "Navigation took too long to reach the destination and was stopped";
        } else if (lowerError.contains("localization") || lowerError.contains("lost")) {
            return "Position tracking was lost and navigation cannot continue safely";
        } else if (lowerError.contains("cancelled")) {
            return "Navigation was cancelled";
        } else {
            return "Navigation failed: " + technicalError;
        }
    }
}
