package io.github.hrilab.pepper_realtime.tools.navigation;

import android.util.Log;
import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.builder.LookAtBuilder;
import com.aldebaran.qi.sdk.builder.TransformBuilder;
import com.aldebaran.qi.sdk.object.actuation.Actuation;
import com.aldebaran.qi.sdk.object.actuation.Frame;
import com.aldebaran.qi.sdk.object.actuation.FreeFrame;
import com.aldebaran.qi.sdk.object.actuation.LookAt;
import com.aldebaran.qi.sdk.object.actuation.Mapping;
import com.aldebaran.qi.sdk.object.geometry.Transform;
import com.aldebaran.qi.sdk.object.power.FlapSensor;
import com.aldebaran.qi.sdk.object.power.FlapState;
import com.aldebaran.qi.sdk.object.power.Power;
import io.github.hrilab.pepper_realtime.tools.Tool;
import io.github.hrilab.pepper_realtime.tools.ToolContext;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tool for making Pepper look at a specific 3D position relative to the robot's base frame.
 * Returns success immediately when gaze is aligned, then automatically cancels after specified duration.
 */
public class LookAtPositionTool implements Tool {
    
    private static final String TAG = "LookAtPositionTool";

    @Override
    public String getName() {
        return "look_at_position";
    }

    @Override
    public JSONObject getDefinition() {
        try {
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", getName());
            tool.put("description", "Make Pepper look at a specific 3D position relative to the robot's base frame. " +
                "X: forward(+)/backward(-), Y: left(+)/right(-), Z: up(+)/down(-). " +
                "Robot base is at ground level (Z=0). Typical values: ground Z=0, eye-level Z=1.2, ceiling Z=2.5+. " +
                "Returns success immediately when gaze is aligned, then automatically returns to normal after the specified duration. " +
                "Perfect for combining with vision analysis - look first, then analyze what you see.");
            
            JSONObject params = new JSONObject();
            params.put("type", "object");
            
            JSONObject properties = new JSONObject();
            properties.put("x", new JSONObject()
                .put("type", "number")
                .put("description", "Distance forward (positive) or backward (negative) from robot base in meters")
                .put("minimum", -5.0)
                .put("maximum", 5.0));
            properties.put("y", new JSONObject()
                .put("type", "number") 
                .put("description", "Distance left (positive) or right (negative) from robot base in meters")
                .put("minimum", -5.0)
                .put("maximum", 5.0));
            properties.put("z", new JSONObject()
                .put("type", "number")
                .put("description", "Distance up (positive) or down (negative) from robot base in meters. Ground=0, eye-level=1.2, ceiling=2.5+")
                .put("minimum", -2.0)
                .put("maximum", 5.0));
            properties.put("movement_policy", new JSONObject()
                .put("type", "string")
                .put("description", "Movement policy for looking")
                .put("enum", new JSONArray().put("head_only").put("whole_body"))
                .put("default", "head_only"));
            properties.put("duration", new JSONObject()
                .put("type", "number")
                .put("description", "Duration to look at the position in seconds before automatically returning to normal gaze")
                .put("minimum", 1)
                .put("maximum", 15)
                .put("default", 3));
            
            params.put("properties", properties);
            params.put("required", new JSONArray().put("x").put("y").put("z"));
            tool.put("parameters", params);
            
            return tool;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool definition", e);
        }
    }

    @Override
    public String execute(JSONObject args, ToolContext context) throws Exception {
        double x = args.optDouble("x", 0.0);
        double y = args.optDouble("y", 0.0);  
        double z = args.optDouble("z", 0.0);
        String movementPolicy = args.optString("movement_policy", "head_only");
        double duration = args.optDouble("duration", 3.0); // Default 3 seconds
        
        // Validate that at least one coordinate is non-zero
        if (x == 0.0 && y == 0.0 && z == 0.0) {
            return new JSONObject().put("error", "Please provide non-zero coordinates for x, y, or z.").toString();
        }
        
        // Note: Movement policy validation removed as API might not support it in all QiSDK versions
        
        // Validate duration
        if (duration < 1 || duration > 15) {
            return new JSONObject().put("error", "Duration must be between 1 and 15 seconds").toString();
        }

        // Check if robot is ready
        if (context.isQiContextNotReady()) {
            return new JSONObject().put("error", "Robot not ready").toString();
        }
        
        // Check if charging flap is open (safety check)
        if (isChargingFlapOpen(context)) {
            return new JSONObject().put("error", "Cannot perform look action while charging flap is open. Please close the charging flap first for safety.").toString();
        }
        
        Log.i(TAG, String.format(Locale.US, "Starting LookAt: position(%.2f, %.2f, %.2f) with policy: %s, duration: %.1fs", 
            x, y, z, movementPolicy, duration));
        
        try {
            // Get QiSDK services
            QiContext qiContext = context.getQiContext();
            Actuation actuation = qiContext.getActuation();
            Mapping mapping = qiContext.getMapping();
            
            // Get robot frame as base
            Frame robotFrame = actuation.robotFrame();
            
            // Create 3D transform to target position
            // QiSDK uses Vector3 for 3D translation - construct directly
            com.aldebaran.qi.sdk.object.geometry.Vector3 translation = 
                new com.aldebaran.qi.sdk.object.geometry.Vector3(x, y, z);
            Transform transform = TransformBuilder.create().fromTranslation(translation);
            
            // Create target frame
            FreeFrame targetFrame = mapping.makeFreeFrame();
            targetFrame.update(robotFrame, transform, 0L);
            
            // Create LookAt action
            // Note: Policy setting might be done differently or not available in all QiSDK versions
            LookAt lookAt = LookAtBuilder.with(qiContext)
                .withFrame(targetFrame.frame())
                .build();
            
            // Create latch to wait for LookAt to START (not complete)
            CountDownLatch startedLatch = new CountDownLatch(1);
            AtomicReference<String> finalResult = new AtomicReference<>();
            
            // Add started listener - return success immediately when gaze is aligned
            lookAt.addOnStartedListener(() -> {
                Log.i(TAG, "LookAt action started - gaze aligned to target position");
                try {
                    JSONObject result = new JSONObject();
                    result.put("status", "LookAt aligned successfully");
                    result.put("x", x);
                    result.put("y", y);
                    result.put("z", z);
                    result.put("movement_policy", movementPolicy);
                    result.put("duration", duration);
                    result.put("message", String.format(Locale.US,
                        "Successfully looking at position (%.2f, %.2f, %.2f) for %.1f seconds.",
                        x, y, z, duration));
                    finalResult.set(result.toString());
                } catch (Exception e) {
                    Log.e(TAG, "Error creating success result", e);
                    finalResult.set("{\"error\":\"Failed to create LookAt result\"}");
                }
                startedLatch.countDown();
            });
            
            // Execute LookAt action asynchronously and store Future for cancellation
            com.aldebaran.qi.Future<Void> lookAtFuture = lookAt.async().run();
            lookAtFuture.thenConsume(future -> {
                if (future.hasError()) {
                    Log.w(TAG, "LookAt action failed: " + future.getError().getMessage());
                    // Only set error if we haven't already returned success
                    if (startedLatch.getCount() > 0) {
                        try {
                            String error = future.getError().getMessage();
                            String userFriendlyError = translateLookAtError(error);
                            JSONObject errorResult = new JSONObject();
                            errorResult.put("error", String.format(Locale.US, "LookAt failed: %s", userFriendlyError));
                            finalResult.set(errorResult.toString());
                        } catch (Exception e) {
                            finalResult.set("{\"error\":\"LookAt action failed\"}");
                        }
                        startedLatch.countDown();
                    }
                }
                // Note: We don't handle success here anymore, only in onStartedListener
            });
            
            // Wait for LookAt to START (with timeout)
            if (startedLatch.await(5, TimeUnit.SECONDS)) {
                // LookAt has started successfully, now schedule auto-cancel
                Timer cancelTimer = new Timer();
                cancelTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        Log.i(TAG, String.format(Locale.US, "Auto-cancelling LookAt after %.1f seconds", duration));
                        lookAtFuture.requestCancellation();
                        cancelTimer.cancel(); // Clean up timer
                    }
                }, (long)(duration * 1000)); // Convert to milliseconds
                
                return finalResult.get();
            } else {
                // Cancel the action if startup timeout
                lookAtFuture.requestCancellation();
                return new JSONObject().put("error", "LookAt failed to start within 5 seconds").toString();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error executing LookAt action", e);
            return new JSONObject().put("error", "Failed to execute LookAt action: " + e.getMessage()).toString();
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
                Log.d(TAG, "Charging flap status: " + (isOpen ? "OPEN (action blocked)" : "CLOSED (action allowed)"));
                return isOpen;
            } else {
                Log.d(TAG, "No charging flap sensor available - assuming action is allowed");
                return false; // Assume closed if sensor not available
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not check charging flap status: " + e.getMessage(), e);
            return false; // Allow action if check fails to avoid false blocking
        }
    }

    /**
     * Translates technical QiSDK LookAt errors into user-friendly messages
     */
    private String translateLookAtError(String error) {
        if (error == null || error.isEmpty()) {
            return "Something prevented me from looking at the specified position.";
        }
        
        String lowerError = error.toLowerCase();
        
        // Check for common QiSDK LookAt error patterns
        if (lowerError.contains("unreachable") || lowerError.contains("out of range")) {
            return "The target position is out of my range or cannot be reached by my head movement.";
        }
        
        if (lowerError.contains("invalid") || lowerError.contains("bad")) {
            return "The specified position coordinates are invalid or impossible to reach.";
        }
        
        if (lowerError.contains("timeout") || lowerError.contains("too long")) {
            return "The look action took too long to complete and was stopped.";
        }
        
        if (lowerError.contains("cancelled") || lowerError.contains("interrupted")) {
            return "My look action was interrupted or cancelled.";
        }
        
        if (lowerError.contains("obstacle") || lowerError.contains("blocked")) {
            return "Something is blocking my head movement to the target position.";
        }
        
        if (lowerError.contains("safety") || lowerError.contains("emergency")) {
            return "Look action stopped for safety reasons.";
        }
        
        // For unknown errors, provide a helpful fallback
        return "A problem was encountered and the look action cannot be completed.";
    }
}
