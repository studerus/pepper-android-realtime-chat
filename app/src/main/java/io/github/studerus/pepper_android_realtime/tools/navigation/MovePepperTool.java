package io.github.studerus.pepper_android_realtime.tools.navigation;

import android.util.Log;
import com.aldebaran.qi.sdk.object.power.FlapSensor;
import com.aldebaran.qi.sdk.object.power.FlapState;
import com.aldebaran.qi.sdk.object.power.Power;
import io.github.studerus.pepper_android_realtime.NavigationServiceManager;
import io.github.studerus.pepper_android_realtime.tools.Tool;
import io.github.studerus.pepper_android_realtime.tools.ToolContext;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Tool for moving Pepper robot in specific directions.
 * Supports forward, backward, left, right movement with safety checks.
 */
public class MovePepperTool implements Tool {
    
    private static final String TAG = "MovePepperTool";

    @Override
    public String getName() {
        return "move_pepper";
    }

    @Override
    public JSONObject getDefinition() {
        try {
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", getName());
            tool.put("description", "Move Pepper robot in a specific direction for a given distance. Use this when the user asks Pepper to move around the room. Call the function directly without announcing it. You can combine forward/backward and sideways movements.");
            
            JSONObject params = new JSONObject();
            params.put("type", "object");
            
            JSONObject properties = new JSONObject();
            properties.put("distance_forward", new JSONObject()
                .put("type", "number")
                .put("description", "Distance to move forward (positive) or backward (negative) in meters (-4.0 to 4.0). Optional, defaults to 0.")
                .put("minimum", -4.0)
                .put("maximum", 4.0));
            properties.put("distance_sideways", new JSONObject()
                .put("type", "number")
                .put("description", "Distance to move left (positive) or right (negative) in meters (-4.0 to 4.0). Optional, defaults to 0.")
                .put("minimum", -4.0)
                .put("maximum", 4.0));
            properties.put("speed", new JSONObject()
                .put("type", "number")
                .put("description", "Optional maximum speed in m/s (0.1-0.55)")
                .put("minimum", 0.1)
                .put("maximum", 0.55)
                .put("default", 0.4));
            
            params.put("properties", properties);
            // No required parameters, as at least one of the distances should be provided.
            // This is handled in the execute logic.
            tool.put("parameters", params);
            
            return tool;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool definition", e);
        }
    }

    @Override
    public String execute(JSONObject args, ToolContext context) throws Exception {
        double distanceForward = args.optDouble("distance_forward", 0.0);
        double distanceSideways = args.optDouble("distance_sideways", 0.0);
        double speed = args.optDouble("speed", 0.4);
        
        // Validate that at least one movement is requested
        if (distanceForward == 0.0 && distanceSideways == 0.0) {
            return new JSONObject().put("error", "Please provide a non-zero distance for 'distance_forward' or 'distance_sideways'.").toString();
        }

        // Check if robot is ready
        if (context.isQiContextNotReady()) {
            return new JSONObject().put("error", "Robot not ready").toString();
        }
        
        // Check if charging flap is open (prevents movement for safety)
        if (isChargingFlapOpen(context)) {
            return new JSONObject().put("error", "Cannot move while charging flap is open. Please close the charging flap first for safety.").toString();
        }
        
        Log.i(TAG, "Starting movement: forward=" + distanceForward + "m, sideways=" + distanceSideways + "m at " + speed + "m/s");
        
        // Use NavigationServiceManager for coordinated movement with service management
        NavigationServiceManager navManager = context.getNavigationServiceManager();
        if (navManager == null) {
            return new JSONObject().put("error", "Navigation service not available").toString();
        }
        
        // Execute movement using manager with tool-level callback (does not override manager listener)
        final double finalDistanceForward = distanceForward;
        final double finalDistanceSideways = distanceSideways;
        navManager.movePepper(context.getQiContext(), distanceForward, distanceSideways, speed)
            .thenConsume(f -> {
                boolean success = !f.hasError() && f.getValue() != null && f.getValue().success;
                String error = f.hasError() ? (f.getError() != null ? f.getError().getMessage() : "movement error")
                                             : (f.getValue() != null ? f.getValue().error : null);
                Log.i(TAG, "Movement finished (tool future), success=" + success + ", error=" + error);
                String message;
                String movementDesc = buildMovementDescription(finalDistanceForward, finalDistanceSideways);
                if (success) {
                message = String.format(Locale.US,
                    "[MOVEMENT COMPLETED] You have successfully moved %s and arrived at your destination. Please inform the user that you have completed the movement.",
                    movementDesc);
                } else {
                String userFriendlyError = translateMovementError(error);
                message = String.format(Locale.US,
                    "[MOVEMENT FAILED] You couldn't complete the movement %s. %s Please inform the user about this problem and offer alternative solutions or ask if they want you to try a different direction.",
                    movementDesc, userFriendlyError);
                }
                context.sendAsyncUpdate(message, true);
            });
        
        // Return immediate confirmation
        JSONObject result = new JSONObject();
        result.put("status", "Movement started");
        result.put("distance_forward", distanceForward);
        result.put("distance_sideways", distanceSideways);
        result.put("speed", speed);
        result.put("message", String.format(Locale.US, 
            "Movement started. Pepper is now moving %s.", buildMovementDescription(distanceForward, distanceSideways)));
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
     * Check if the charging flap is open, which prevents movement for safety reasons
     */
    private boolean isChargingFlapOpen(ToolContext context) {
        try {
            Power power = context.getQiContext().getPower();
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
     * Builds a human-readable description of the movement for logs and messages.
     */
    private String buildMovementDescription(double forward, double sideways) {
        StringBuilder desc = new StringBuilder();
        if (forward != 0) {
            desc.append(String.format(Locale.US, "%.1f meters %s", Math.abs(forward), forward > 0 ? "forward" : "backward"));
        }
        if (sideways != 0) {
            if (desc.length() > 0) {
                desc.append(" and ");
            }
            desc.append(String.format(Locale.US, "%.1f meters to the %s", Math.abs(sideways), sideways > 0 ? "left" : "right"));
        }
        return desc.toString();
    }

    /**
     * Translates technical QiSDK movement errors into user-friendly messages
     */
    private String translateMovementError(String error) {
        if (error == null || error.isEmpty()) {
            return "My path is blocked by an obstacle.";
        }
        
        String lowerError = error.toLowerCase();
        
        // Check for common QiSDK movement error patterns
        if (lowerError.contains("obstacle") || lowerError.contains("blocked") || 
            lowerError.contains("collision") || lowerError.contains("bump")) {
            return "My path is blocked by an obstacle in front of me.";
        }
        
        if (lowerError.contains("unreachable") || lowerError.contains("no path") || 
            lowerError.contains("path planning") || lowerError.contains("navigation failed")) {
            return "No safe path could be found to reach that location.";
        }
        
        if (lowerError.contains("timeout") || lowerError.contains("too long")) {
            return "Movement took too long and was stopped. There are likely obstacles blocking the path.";
        }
        
        if (lowerError.contains("safety") || lowerError.contains("emergency")) {
            return "Movement stopped for safety reasons - there's something in the path.";
        }
        
        if (lowerError.contains("cancelled") || lowerError.contains("interrupted")) {
            return "My movement was interrupted or cancelled.";
        }
        
        // For unknown errors, provide a helpful fallback that suggests obstacles
        return "An obstacle was encountered and movement cannot continue in that direction.";
    }
}
