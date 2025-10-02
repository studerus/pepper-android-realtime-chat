package io.github.hrilab.pepper_realtime.tools.navigation;

import android.util.Log;
import io.github.hrilab.pepper_realtime.RobotSafetyGuard;
import io.github.hrilab.pepper_realtime.NavigationServiceManager;
import io.github.hrilab.pepper_realtime.tools.Tool;
import io.github.hrilab.pepper_realtime.tools.ToolContext;

import org.json.JSONObject;


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
        RobotSafetyGuard.Result safety = RobotSafetyGuard.evaluateMovementSafety((com.aldebaran.qi.sdk.QiContext) context.getQiContext());
        if (!safety.isOk()) {
            String message = safety.message != null ? safety.message : "Movement blocked by safety check";
            return new JSONObject().put("error", message).toString();
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
        navManager.movePepper((com.aldebaran.qi.sdk.QiContext) context.getQiContext(), distanceForward, distanceSideways, speed)
            .thenConsume(f -> {
                boolean success = !f.hasError() && f.getValue() != null && f.getValue().success;
                String error = f.hasError() ? (f.getError() != null ? f.getError().getMessage() : "movement error")
                                             : (f.getValue() != null ? f.getValue().error : null);
                Log.i(TAG, "Movement finished (tool future), success=" + success + ", error=" + error);
                String message;
                String movementDesc = buildMovementDescription(finalDistanceForward, finalDistanceSideways);
                if (success) {
                message = String.format(java.util.Locale.US,
                    "[MOVEMENT COMPLETED] You have successfully moved %s and arrived at your destination. Please inform the user that you have completed the movement.",
                    movementDesc);
                } else {
                String userFriendlyError = translateMovementError(error);
                
                // Build the base error message
                StringBuilder messageBuilder = new StringBuilder();
                messageBuilder.append(String.format(java.util.Locale.US,
                    "[MOVEMENT FAILED] You couldn't complete the movement %s. %s Please inform the user about this problem and offer alternative solutions or ask if they want you to try a different direction.",
                    movementDesc, userFriendlyError));
                
                // Add vision analysis suggestion for obstacle-related errors in the same message
                boolean isObstacleError = isObstacleRelatedError(error);
                Log.i(TAG, "Movement error: '" + error + "' -> obstacle-related: " + isObstacleError);
                if (isObstacleError) {
                    messageBuilder.append(String.format(java.util.Locale.US,
                        " Use vision analysis to identify what is blocking your path - " +
                        "look around your current position, starting with (%.2f, %.2f, %.2f) in front of you, to see what obstacles are nearby.",
                        1.0, 0.0, 0.0)); // Always look 1m forward from current position
                }
                
                message = messageBuilder.toString();
                }
                context.sendAsyncUpdate(message, true);
            });
        
        // Return immediate confirmation
        JSONObject result = new JSONObject();
        result.put("status", "Movement started");
        result.put("distance_forward", distanceForward);
        result.put("distance_sideways", distanceSideways);
        result.put("speed", speed);
        result.put("message", String.format(java.util.Locale.US, 
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
     * Builds a human-readable description of the movement for logs and messages.
     */
    private String buildMovementDescription(double forward, double sideways) {
        StringBuilder desc = new StringBuilder();
        if (forward != 0) {
            desc.append(String.format(java.util.Locale.US, "%.1f meters %s", Math.abs(forward), forward > 0 ? "forward" : "backward"));
        }
        if (sideways != 0) {
            if (desc.length() > 0) {
                desc.append(" and ");
            }
            desc.append(String.format(java.util.Locale.US, "%.1f meters to the %s", Math.abs(sideways), sideways > 0 ? "left" : "right"));
        }
        return desc.toString();
    }

    /**
     * Check if the error is related to obstacles that could benefit from vision analysis
     */
    private boolean isObstacleRelatedError(String error) {
        if (error == null || error.isEmpty()) {
            return true; // Default fallback suggests obstacles
        }
        
        String lowerError = error.toLowerCase();
        
        // Most movement errors are obstacle-related and would benefit from vision analysis
        return lowerError.contains("obstacle") || lowerError.contains("blocked") || 
               lowerError.contains("collision") || lowerError.contains("bump") ||
               lowerError.contains("unreachable") || lowerError.contains("no path") || 
               lowerError.contains("path planning") || lowerError.contains("navigation failed") ||
               lowerError.contains("timeout") || lowerError.contains("too long") ||
               lowerError.contains("safety") || lowerError.contains("emergency") ||
               lowerError.contains("goto failed") || lowerError.contains("failed to complete") ||
               lowerError.contains("movement failed") || lowerError.contains("cannot move");
        // Note: Cancelled/interrupted errors are NOT obstacle-related, so no vision suggestion
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
