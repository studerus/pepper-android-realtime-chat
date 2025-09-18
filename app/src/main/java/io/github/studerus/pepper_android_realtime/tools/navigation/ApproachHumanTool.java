package io.github.studerus.pepper_android_realtime.tools.navigation;

import android.util.Log;

import com.aldebaran.qi.Future;
import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.object.human.Human;
import com.aldebaran.qi.sdk.object.power.FlapSensor;
import com.aldebaran.qi.sdk.object.power.FlapState;
import com.aldebaran.qi.sdk.object.power.Power;
import com.aldebaran.qi.sdk.object.humanawareness.ApproachHuman;
import com.aldebaran.qi.sdk.builder.ApproachHumanBuilder;

import io.github.studerus.pepper_android_realtime.PerceptionService;
import io.github.studerus.pepper_android_realtime.tools.Tool;
import io.github.studerus.pepper_android_realtime.tools.ToolContext;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Tool for making Pepper approach a detected human to initiate interaction.
 * Uses QiSDK's ApproachHuman action with safety checks and status updates.
 */
public class ApproachHumanTool implements Tool {
    
    private static final String TAG = "ApproachHumanTool";

    @Override
    public String getName() {
        return "approach_human";
    }

    @Override
    public JSONObject getDefinition() {
        try {
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", getName());
            tool.put("description", "Make Pepper approach a detected human to initiate interaction. Use this when the user wants Pepper to approach him. Pepper will move closer to the person while maintaining appropriate social distance.");
            
            JSONObject params = new JSONObject();
            params.put("type", "object");
            
            JSONObject properties = new JSONObject();
            properties.put("human_id", new JSONObject()
                .put("type", "integer")
                .put("description", "Optional ID of specific human to approach (from human detection). If not provided, approaches the most suitable person based on engagement and attention signals."));
            properties.put("speed", new JSONObject()
                .put("type", "number")
                .put("description", "Optional movement speed in m/s (0.1-0.55)")
                .put("minimum", 0.1)
                .put("maximum", 0.55)
                .put("default", 0.3));
            
            params.put("properties", properties);
            // No required parameters - tool can work with or without specific human ID
            tool.put("parameters", params);
            
            return tool;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool definition", e);
        }
    }

    @Override
    public String execute(JSONObject args, ToolContext context) throws Exception {
        Integer humanId = args.has("human_id") ? args.optInt("human_id") : null;
        double speed = args.optDouble("speed", 0.3);
        
        // Validate speed
        if (speed < 0.1 || speed > 0.55) {
            speed = 0.3; // Use default speed if invalid
        }

        // Check if robot is ready
        if (context.isQiContextNotReady()) {
            return new JSONObject().put("error", "Robot not ready").toString();
        }
        
        // Check if charging flap is open (prevents movement for safety)
        if (isChargingFlapOpen(context)) {
            return new JSONObject().put("error", "Cannot approach human while charging flap is open. Please close the charging flap first for safety.").toString();
        }

        Log.i(TAG, "Starting human approach - target ID: " + humanId + ", speed: " + speed + " m/s");
        
        try {
            QiContext qiContext = context.getQiContext();
            PerceptionService perceptionService = context.getPerceptionService();
            
            // Check if PerceptionService is available
            if (perceptionService == null || !perceptionService.isInitialized()) {
                return new JSONObject()
                    .put("error", "Human detection service not available. Please wait for the robot to initialize.")
                    .toString();
            }
            
            // Find target human
            Human targetHuman;
            String targetDescription;
            
            if (humanId != null) {
                // Try to find specific human by ID using PerceptionService
                targetHuman = perceptionService.getHumanById(humanId);
                
                if (targetHuman == null) {
                    return new JSONObject()
                        .put("error", "Human with ID " + humanId + " not found. The person may have moved away.")
                        .toString();
                }
                targetDescription = "human with ID " + humanId;
            } else {
                // Get recommended human to approach using PerceptionService
                targetHuman = perceptionService.getRecommendedHumanToApproach();
                if (targetHuman == null) {
                    return new JSONObject()
                        .put("error", "No suitable human found to approach. Make sure there are people nearby who are interested in interacting.")
                        .toString();
                }
                targetDescription = "recommended human";
            }

            // Start asynchronous approach
            final String finalTargetDescription = targetDescription;
            
            ApproachHuman approachHuman = ApproachHumanBuilder.with(qiContext)
                .withHuman(targetHuman)
                .build();

            // Add listener for temporary unreachable state
            approachHuman.addOnHumanIsTemporarilyUnreachableListener(() -> {
                Log.i(TAG, "Human temporarily unreachable - sending status update");
                context.sendAsyncUpdate("[APPROACH INTERRUPTED] Pepper cannot reach the person. The path may be blocked by obstacles. " +
                    "Use vision analysis to identify what is blocking your path - look around your current position, starting with (1.0, 0.0, 0.0) in front of you, to see what obstacles are nearby.", true);
            });

            // Execute approach asynchronously
            Future<Void> approachFuture = approachHuman.async().run();
            
            approachFuture.thenConsume(future -> {
                boolean success = !future.hasError();
                String error = future.hasError() ? 
                    (future.getError() != null ? future.getError().getMessage() : "Unknown approach error") : null;
                
                Log.i(TAG, "Approach completed - success: " + success + ", error: " + error);
                
                String message;
                if (success) {
                    message = String.format(Locale.US, 
                        "[APPROACH COMPLETED] Pepper has successfully approached the %s and is now ready for interaction. The robot is positioned at an appropriate social distance.",
                        finalTargetDescription);
                } else {
                    String userFriendlyError = translateApproachError(error);
                    
                    // Build the base error message
                    StringBuilder messageBuilder = new StringBuilder();
                    messageBuilder.append(String.format(Locale.US,
                        "[APPROACH FAILED] Pepper could not approach the %s. %s Please inform the user and suggest alternatives like asking the person to come closer.",
                        finalTargetDescription, userFriendlyError));
                    
                    // Add vision analysis suggestion for obstacle-related errors in the same message
                    boolean isObstacleError = isObstacleRelatedApproachError(error);
                    Log.i(TAG, "Approach error: '" + error + "' -> obstacle-related: " + isObstacleError);
                    if (isObstacleError) {
                        messageBuilder.append(String.format(Locale.US,
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
            result.put("status", "approach_started");
            result.put("target_human", finalTargetDescription);
            result.put("speed", speed);
            result.put("message", String.format(Locale.US, 
                "Approach started. Pepper is now moving towards the %s to initiate interaction.", 
                finalTargetDescription));
            return result.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "Error during human approach", e);
            return new JSONObject()
                .put("error", "Failed to approach human: " + e.getMessage())
                .toString();
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
     * Check if the approach error is related to obstacles that could benefit from vision analysis
     */
    private boolean isObstacleRelatedApproachError(String error) {
        if (error == null || error.isEmpty()) {
            return true; // Default fallback suggests obstacles
        }
        
        String lowerError = error.toLowerCase();
        
        // Approach errors that are likely obstacle-related and would benefit from vision analysis
        return lowerError.contains("obstacle") || lowerError.contains("blocked") || 
               lowerError.contains("collision") || lowerError.contains("bump") ||
               lowerError.contains("unreachable") || lowerError.contains("no path") || 
               lowerError.contains("path planning") || lowerError.contains("navigation failed") ||
               lowerError.contains("timeout") || lowerError.contains("took too long") ||
               lowerError.contains("safety") || lowerError.contains("emergency") ||
               lowerError.contains("approach failed") || lowerError.contains("failed to complete");
        // Note: Cancelled/lost/disappeared errors are NOT obstacle-related, so no vision suggestion
    }

    /**
     * Translates technical QiSDK approach errors into user-friendly messages
     */
    private String translateApproachError(String technicalError) {
        if (technicalError == null || technicalError.isEmpty()) {
            return "An unknown error occurred during approach.";
        }
        
        String lowerError = technicalError.toLowerCase();
        
        if (lowerError.contains("obstacle") || lowerError.contains("blocked")) {
            return "The path to the person is blocked by obstacles.";
        } else if (lowerError.contains("unreachable") || lowerError.contains("no path")) {
            return "The person cannot be reached from the current position.";
        } else if (lowerError.contains("timeout") || lowerError.contains("took too long")) {
            return "The approach took too long and was cancelled for safety.";
        } else if (lowerError.contains("lost") || lowerError.contains("disappeared")) {
            return "The person was lost during approach - they may have moved away.";
        } else if (lowerError.contains("cancelled") || lowerError.contains("interrupted")) {
            return "The approach was interrupted or cancelled.";
        } else if (lowerError.contains("safety") || lowerError.contains("emergency")) {
            return "The approach was stopped for safety reasons.";
        } else {
            return "Approach failed: " + technicalError;
        }
    }
}
