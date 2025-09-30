package io.github.hrilab.pepper_realtime.tools.navigation;

import android.util.Log;
import com.aldebaran.qi.sdk.object.power.FlapSensor;
import com.aldebaran.qi.sdk.object.power.FlapState;
import com.aldebaran.qi.sdk.object.power.Power;
import io.github.hrilab.pepper_realtime.NavigationServiceManager;
import io.github.hrilab.pepper_realtime.tools.Tool;
import io.github.hrilab.pepper_realtime.tools.ToolContext;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tool for turning Pepper robot left or right by specific degrees.
 * This is a synchronous tool that waits for turn completion.
 */
public class TurnPepperTool implements Tool {
    
    private static final String TAG = "TurnPepperTool";

    @Override
    public String getName() {
        return "turn_pepper";
    }

    @Override
    public JSONObject getDefinition() {
        try {
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", getName());
            tool.put("description", "Turn Pepper robot left or right by a specific number of degrees. Use this when the user asks Pepper to turn or rotate.");
            
            JSONObject params = new JSONObject();
            params.put("type", "object");
            
            JSONObject properties = new JSONObject();
            properties.put("direction", new JSONObject()
                .put("type", "string")
                .put("description", "Direction to turn")
                .put("enum", new JSONArray().put("left").put("right")));
            properties.put("degrees", new JSONObject()
                .put("type", "number")
                .put("description", "Degrees to turn (15-180)")
                .put("minimum", 15)
                .put("maximum", 180));
            properties.put("speed", new JSONObject()
                .put("type", "number")
                .put("description", "Optional turning speed in rad/s (0.1-1.0)")
                .put("minimum", 0.1)
                .put("maximum", 1.0)
                .put("default", 0.5));
            
            params.put("properties", properties);
            params.put("required", new JSONArray().put("direction").put("degrees"));
            tool.put("parameters", params);
            
            return tool;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool definition", e);
        }
    }

    @Override
    public String execute(JSONObject args, ToolContext context) throws Exception {
        String direction = args.optString("direction", "");
        double degrees = args.optDouble("degrees", 0);
        double speed = args.optDouble("speed", 0.5);
        
        // Validate required parameters
        if (direction.isEmpty()) {
            return new JSONObject().put("error", "Missing required parameter: direction").toString();
        }
        if (degrees <= 0) {
            return new JSONObject().put("error", "Missing or invalid parameter: degrees").toString();
        }
        
        // Validate direction
        if (!direction.equals("left") && !direction.equals("right")) {
            return new JSONObject().put("error", "Invalid direction. Use: left, right").toString();
        }
        
        // Validate degrees range
        if (degrees < 15 || degrees > 180) {
            return new JSONObject().put("error", "Degrees must be between 15 and 180").toString();
        }
        
        // Check if robot is ready
        if (context.isQiContextNotReady()) {
            return new JSONObject().put("error", "Robot not ready").toString();
        }
        
        // Check if charging flap is open (prevents movement for safety)
        if (isChargingFlapOpen(context)) {
            return new JSONObject().put("error", "Cannot turn while charging flap is open. Please close the charging flap first for safety.").toString();
        }
        
        // Use NavigationServiceManager for coordinated turn with service management
        NavigationServiceManager navManager = context.getNavigationServiceManager();
        if (navManager == null) {
            return new JSONObject().put("error", "Navigation service not available").toString();
        }
        
        Log.i(TAG, "Starting synchronous turn: " + direction + " " + degrees + " degrees at " + speed + " rad/s");
        
        // Create latch to wait for turn completion
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> finalResult = new AtomicReference<>();
        
        // Execute coordinated turn with service management; return result via Future
        navManager.turnPepper(context.getQiContext(), direction, degrees, speed)
            .thenConsume(f -> {
                boolean success = !f.hasError() && f.getValue() != null && f.getValue().success;
                String error = f.hasError() ? (f.getError() != null ? f.getError().getMessage() : "turn error")
                                            : (f.getValue() != null ? f.getValue().error : null);
                Log.i(TAG, "Turn finished (tool future), success=" + success + ", error=" + error);
                try {
                    JSONObject result = new JSONObject();
                    if (success) {
                        result.put("status", "Turn completed successfully");
                        result.put("direction", direction);
                        result.put("degrees", degrees);
                        result.put("message", String.format(Locale.US,
                            "Turn completed successfully. Pepper has turned %s %.0f degrees.",
                            direction, degrees));
                    } else {
                        String userFriendlyError = translateTurnError(error);
                        result.put("error", String.format(Locale.US,
                            "Turn failed: %s", userFriendlyError));
                    }
                    finalResult.set(result.toString());
                } catch (Exception e) {
                    finalResult.set("{\"error\":\"Failed to create turn result\"}");
                }
                latch.countDown();
            });
        
        // Wait for turn to complete (with timeout)
        if (latch.await(20, TimeUnit.SECONDS)) {
            return finalResult.get();
        } else {
            return new JSONObject().put("error", "Turn timeout after 20 seconds").toString();
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
     * Translates technical QiSDK turn errors into user-friendly messages
     */
    private String translateTurnError(String error) {
        if (error == null || error.isEmpty()) {
            return "Something prevented me from turning.";
        }
        
        String lowerError = error.toLowerCase();
        
        // Check for common QiSDK turn error patterns
        if (lowerError.contains("obstacle") || lowerError.contains("blocked") || 
            lowerError.contains("collision") || lowerError.contains("bump")) {
            return "There's an obstacle preventing me from turning safely.";
        }
        
        if (lowerError.contains("timeout") || lowerError.contains("too long")) {
            return "Turn took too long to complete and was stopped. There might be obstacles in the way.";
        }
        
        if (lowerError.contains("safety") || lowerError.contains("emergency")) {
            return "Turn stopped for safety reasons - something is in the path.";
        }
        
        if (lowerError.contains("cancelled") || lowerError.contains("interrupted")) {
            return "My turn was interrupted or cancelled.";
        }
        
        if (lowerError.contains("unreachable") || lowerError.contains("no space")) {
            return "Not enough space available to complete this turn safely.";
        }
        
        // For unknown errors, provide a helpful fallback
        return "A problem was encountered and the turn cannot be completed.";
    }
}
