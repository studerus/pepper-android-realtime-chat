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
            tool.put("description", "Move Pepper robot in a specific direction for a given distance. Use this when the user asks Pepper to move around the room. Call the function directly without announcing it.");
            
            JSONObject params = new JSONObject();
            params.put("type", "object");
            
            JSONObject properties = new JSONObject();
            properties.put("direction", new JSONObject()
                .put("type", "string")
                .put("description", "Direction to move")
                .put("enum", new JSONArray().put("forward").put("backward").put("left").put("right")));
            properties.put("distance", new JSONObject()
                .put("type", "number")
                .put("description", "Distance to move in meters (0.1-4.0)")
                .put("minimum", 0.1)
                .put("maximum", 4.0));
            properties.put("speed", new JSONObject()
                .put("type", "number")
                .put("description", "Optional maximum speed in m/s (0.1-0.55)")
                .put("minimum", 0.1)
                .put("maximum", 0.55)
                .put("default", 0.4));
            
            params.put("properties", properties);
            params.put("required", new JSONArray().put("direction").put("distance"));
            tool.put("parameters", params);
            
            return tool;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool definition", e);
        }
    }

    @Override
    public String execute(JSONObject args, ToolContext context) throws Exception {
        String direction = args.optString("direction", "");
        double distance = args.optDouble("distance", 0);
        double speed = args.optDouble("speed", 0.4);
        
        // Validate required parameters
        if (direction.isEmpty()) {
            return new JSONObject().put("error", "Missing required parameter: direction").toString();
        }
        if (distance <= 0) {
            return new JSONObject().put("error", "Missing or invalid parameter: distance").toString();
        }
        
        // Validate direction
        if (!direction.equals("forward") && !direction.equals("backward") && 
            !direction.equals("left") && !direction.equals("right")) {
            return new JSONObject().put("error", "Invalid direction. Use: forward, backward, left, right").toString();
        }
        
        // Check if robot is ready
        if (context.isQiContextNotReady()) {
            return new JSONObject().put("error", "Robot not ready").toString();
        }
        
        // Check if charging flap is open (prevents movement for safety)
        if (isChargingFlapOpen(context)) {
            return new JSONObject().put("error", "Cannot move while charging flap is open. Please close the charging flap first for safety.").toString();
        }
        
        Log.i(TAG, "Starting movement: " + direction + " " + distance + "m at " + speed + "m/s");
        
        // Use NavigationServiceManager for coordinated movement with service management
        NavigationServiceManager navManager = context.getNavigationServiceManager();
        if (navManager == null) {
            return new JSONObject().put("error", "Navigation service not available").toString();
        }
        
        // Execute movement using manager with tool-level callback (does not override manager listener)
        final String finalDirection = direction;
        final double finalDistance = distance;
        navManager.movePepper(context.getQiContext(), direction, distance, speed)
            .thenConsume(f -> {
                boolean success = !f.hasError() && f.getValue() != null && f.getValue().success;
                String error = f.hasError() ? (f.getError() != null ? f.getError().getMessage() : "movement error")
                                            : (f.getValue() != null ? f.getValue().error : null);
                Log.i(TAG, "Movement finished (tool future), success=" + success + ", error=" + error);
                String message;
                if (success) {
                message = String.format(Locale.US,
                    "[MOVEMENT COMPLETED] You have successfully moved %s %.1f meters and arrived at your destination. Please inform the user that you have completed the movement.",
                    finalDirection, finalDistance);
                } else {
                String userFriendlyError = translateMovementError(error);
                message = String.format(Locale.US,
                    "[MOVEMENT FAILED] You couldn't complete the movement %s %.1f meters. %s Please inform the user about this problem and offer alternative solutions or ask if they want you to try a different direction.",
                    finalDirection, finalDistance, userFriendlyError);
                }
                context.sendAsyncUpdate(message, true);
            });
        
        // Return immediate confirmation
        JSONObject result = new JSONObject();
        result.put("status", "Movement started");
        result.put("direction", direction);
        result.put("distance", distance);
        result.put("speed", speed);
        result.put("message", String.format(Locale.US, 
            "Movement started. Pepper is now moving %s %.1f meters.", direction, distance));
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
