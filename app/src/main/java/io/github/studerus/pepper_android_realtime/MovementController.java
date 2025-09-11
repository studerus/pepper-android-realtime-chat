package io.github.studerus.pepper_android_realtime;

import android.util.Log;

import com.aldebaran.qi.Future;
import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.object.actuation.Actuation;
import com.aldebaran.qi.sdk.object.actuation.Frame;
import com.aldebaran.qi.sdk.object.actuation.FreeFrame;
import com.aldebaran.qi.sdk.object.actuation.GoTo;
import com.aldebaran.qi.sdk.object.actuation.Mapping;
import com.aldebaran.qi.sdk.object.actuation.OrientationPolicy;
import com.aldebaran.qi.sdk.object.geometry.Transform;
import com.aldebaran.qi.sdk.object.geometry.Quaternion;
import com.aldebaran.qi.sdk.builder.GoToBuilder;
import com.aldebaran.qi.sdk.builder.TransformBuilder;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;

public class MovementController {
    private static final String TAG = "MovementController";
    
    // Movement timeout in seconds - adjust this value as needed
    private static final int MOVEMENT_TIMEOUT_SECONDS = 15;
    
    public interface MovementListener {
        void onMovementStarted();
        void onMovementFinished(boolean success, String error);
    }
    
    private MovementListener listener;
    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
    private Future<Void> currentMovement;
    private ScheduledFuture<?> currentTimeoutTask;
    
    public void setListener(MovementListener listener) {
        this.listener = listener;
    }
    
    /**
     * Move Pepper in a specified direction
     * @param qiContext QiContext for accessing robot services
     * @param distanceForward distance forward (positive) or backward (negative) in meters
     * @param distanceSideways distance left (positive) or right (negative) in meters
     * @param speed optional speed in m/s (0.1-0.55), default 0.4
     */
    public void movePepper(QiContext qiContext, double distanceForward, double distanceSideways, double speed) {
        if (qiContext == null) {
            Log.e(TAG, "QiContext is null - cannot move Pepper");
            if (listener != null) listener.onMovementFinished(false, "Robot not ready");
            return;
        }
        
        try {
            // Validate parameters
            if (speed < 0.1 || speed > 0.55) {
                speed = 0.4; // Use default speed
            }
            
            // Get Actuation and Mapping services
            Actuation actuation = qiContext.getActuation();
            Mapping mapping = qiContext.getMapping();
            
            // Get robot frame
            Frame robotFrame = actuation.robotFrame();
            
            // Create transform based on direction
            // X is forward/backward, Y is left(+)/right(-)
            Transform transform = TransformBuilder.create().from2DTranslation(distanceForward, distanceSideways);
            
            // Create target frame
            FreeFrame targetFrame = mapping.makeFreeFrame();
            targetFrame.update(robotFrame, transform, 0L);
            
            // Create and execute GoTo action
            GoTo goTo = GoToBuilder.with(qiContext)
                    .withFrame(targetFrame.frame())
                    .withMaxSpeed((float) speed)
                    .withFinalOrientationPolicy(OrientationPolicy.ALIGN_X)
                    .build();
            
            // Add listeners
            goTo.addOnStartedListener(() -> {
                Log.i(TAG, "Movement started: forward=" + distanceForward + "m, sideways=" + distanceSideways + "m");
                if (listener != null) listener.onMovementStarted();
            });
            
            // Execute movement
            currentMovement = goTo.async().run();
            
            // Set up timeout to cancel movement if it takes too long
            timeoutExecutor.schedule(() -> {
                if (currentMovement != null && !currentMovement.isDone()) {
                    Log.w(TAG, "Movement timeout after " + MOVEMENT_TIMEOUT_SECONDS + " seconds - cancelling");
                    currentMovement.requestCancellation();
                    if (listener != null) {
                        listener.onMovementFinished(false, "Movement timeout - the robot took too long to reach the destination, possibly due to obstacles");
                    }
                }
            }, MOVEMENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            
            currentMovement.thenConsume(future -> {
                goTo.removeAllOnStartedListeners();
                
                if (future.isSuccess()) {
                    Log.i(TAG, "Movement finished successfully");
                    if (listener != null) listener.onMovementFinished(true, null);
                } else if (future.isCancelled()) {
                    Log.w(TAG, "Movement was cancelled (likely due to timeout)");
                    // Don't call listener here - timeout handler already did
                } else if (future.hasError()) {
                    String errorMsg = future.getError() != null ? future.getError().getMessage() : "Unknown movement error";
                    Log.e(TAG, "Movement failed: " + errorMsg, future.getError());
                    if (listener != null) listener.onMovementFinished(false, errorMsg);
                }
            });
            
        } catch (Exception e) {
            Log.e(TAG, "Error creating movement", e);
            if (listener != null) listener.onMovementFinished(false, e.getMessage());
        }
    }
    
    /**
     * Turn Pepper by a specified angle
     * @param qiContext QiContext for accessing robot services
     * @param direction "left", "right"
     * @param degrees degrees to turn (15-180)
     * @param speed optional angular speed in rad/s (0.1-1.0), default 0.5
     */
    public void turnPepper(QiContext qiContext, String direction, double degrees, double speed) {
        if (qiContext == null) {
            Log.e(TAG, "QiContext is null - cannot turn Pepper");
            if (listener != null) listener.onMovementFinished(false, "Robot not ready");
            return;
        }
        
        try {
            // Validate parameters
            if (degrees < 15 || degrees > 180) {
                throw new IllegalArgumentException("Degrees must be between 15 and 180");
            }
            if (speed < 0.1 || speed > 1.0) {
                speed = 0.5; // Use default speed
            }
            
            // Get Actuation and Mapping services
            Actuation actuation = qiContext.getActuation();
            Mapping mapping = qiContext.getMapping();
            
            // Get robot frame
            Frame robotFrame = actuation.robotFrame();
            
            // Create rotation transform
            Transform transform = createRotationTransform(direction, degrees);
            
            // Create target frame
            FreeFrame targetFrame = mapping.makeFreeFrame();
            targetFrame.update(robotFrame, transform, 0L);
            
            // Create and execute GoTo action
            GoTo goTo = GoToBuilder.with(qiContext)
                    .withFrame(targetFrame.frame())
                    .withMaxSpeed((float) speed)
                    .withFinalOrientationPolicy(OrientationPolicy.ALIGN_X)
                    .build();
            
            // Add listeners
            goTo.addOnStartedListener(() -> {
                Log.i(TAG, "Turn started: " + direction + " " + degrees + " degrees");
                if (listener != null) listener.onMovementStarted();
            });
            
            // Execute turn
            currentMovement = goTo.async().run();
            
            // Set up timeout to cancel turn if it takes too long
            timeoutExecutor.schedule(() -> {
                if (currentMovement != null && !currentMovement.isDone()) {
                    Log.w(TAG, "Turn timeout after " + MOVEMENT_TIMEOUT_SECONDS + " seconds - cancelling");
                    currentMovement.requestCancellation();
                    if (listener != null) {
                        listener.onMovementFinished(false, "Turn timeout - the robot took too long to complete the turn, possibly due to obstacles");
                    }
                }
            }, MOVEMENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            
            currentMovement.thenConsume(future -> {
                goTo.removeAllOnStartedListeners();
                
                if (future.isSuccess()) {
                    Log.i(TAG, "Turn finished successfully");
                    if (listener != null) listener.onMovementFinished(true, null);
                } else if (future.isCancelled()) {
                    Log.w(TAG, "Turn was cancelled (likely due to timeout)");
                    // Don't call listener here - timeout handler already did
                } else if (future.hasError()) {
                    String errorMsg = future.getError() != null ? future.getError().getMessage() : "Unknown turn error";
                    Log.e(TAG, "Turn failed: " + errorMsg, future.getError());
                    if (listener != null) listener.onMovementFinished(false, errorMsg);
                }
            });
            
        } catch (Exception e) {
            Log.e(TAG, "Error creating turn", e);
            if (listener != null) listener.onMovementFinished(false, e.getMessage());
        }
    }
    
    private Transform createRotationTransform(String direction, double degrees) {
        // Convert degrees to radians
        double radians = Math.toRadians(degrees);
        
        switch (direction.toLowerCase()) {
            case "left":
                // Positive rotation around Z-axis (counter-clockwise)
                // Quaternion for Z-axis rotation: (0, 0, sin(θ/2), cos(θ/2))
                double halfAngle = radians / 2.0;
                Quaternion leftQuaternion = new Quaternion(0.0, 0.0, Math.sin(halfAngle), Math.cos(halfAngle));
                return TransformBuilder.create().fromRotation(leftQuaternion);
            case "right":
                // Negative rotation around Z-axis (clockwise)
                // Quaternion for Z-axis rotation: (0, 0, sin(-θ/2), cos(-θ/2))
                double halfAngleRight = -radians / 2.0;
                Quaternion rightQuaternion = new Quaternion(0.0, 0.0, Math.sin(halfAngleRight), Math.cos(halfAngleRight));
                return TransformBuilder.create().fromRotation(rightQuaternion);
            default:
                throw new IllegalArgumentException("Invalid turn direction: " + direction + ". Use left or right.");
        }
    }
    
    /**
     * Navigate to a saved location using GoTo
     * @param qiContext QiSDK context
     * @param savedLocation Location data containing position and orientation  
     * @param speed Movement speed in m/s
     */
    public void navigateToLocation(QiContext qiContext, Object savedLocation, float speed) {
        if (currentMovement != null && !currentMovement.isDone()) {
            Log.w(TAG, "Cannot start navigation - another movement is in progress");
            if (listener != null) {
                listener.onMovementFinished(false, "Another movement is already in progress");
            }
            return;
        }
        
        // Validate speed
        if (speed < 0.1f || speed > 0.55f) {
            Log.e(TAG, "Invalid navigation speed: " + speed + ". Must be between 0.1 and 0.55 m/s");
            if (listener != null) {
                listener.onMovementFinished(false, "Invalid speed: " + speed);
            }
            return;
        }
        
        Log.d(TAG, "Starting navigation to saved location with speed: " + speed + " m/s");
        
        try {
            // Extract location data (assuming savedLocation has public fields)
            // This is a simplified approach - in a real implementation, you'd need proper casting
            java.lang.reflect.Field translationField = savedLocation.getClass().getDeclaredField("translation");
            translationField.setAccessible(true);
            
            double[] translation = (double[]) translationField.get(savedLocation);
            
            // Validate translation data
            if (translation == null || translation.length < 2) {
                Log.e(TAG, "Invalid translation data in saved location");
                if (listener != null) {
                    listener.onMovementFinished(false, "Invalid location data - translation missing");
                }
                return;
            }
            
            // Create target transform from saved location data
            // The saved location is relative to the map frame
            Transform targetTransform = TransformBuilder.create().from2DTranslation(translation[0], translation[1]);
            
            // Get map frame as reference (where the location was saved relative to)
            Frame mapFrame;
            try {
                mapFrame = qiContext.getMapping().mapFrame();
            } catch (Exception e) {
                Log.e(TAG, "Map frame not available for navigation: " + e.getMessage());
                if (listener != null) {
                    listener.onMovementFinished(false, "No map available for navigation. Please create a map first.");
                }
                return;
            }
            
            // Create a FreeFrame at the target location
            FreeFrame targetFrame = qiContext.getMapping().makeFreeFrame();
            targetFrame.update(mapFrame, targetTransform, 0L);
            
            // Create GoTo action
            GoTo goTo = GoToBuilder.with(qiContext)
                .withFrame(targetFrame.frame())
                .withMaxSpeed(speed)
                .withFinalOrientationPolicy(OrientationPolicy.FREE_ORIENTATION)
                .build();
            
            // Set up timeout
            currentTimeoutTask = timeoutExecutor.schedule(() -> {
                if (!currentMovement.isDone()) {
                    Log.w(TAG, "Navigation timeout after " + MOVEMENT_TIMEOUT_SECONDS + " seconds");
                    currentMovement.requestCancellation();
                    if (listener != null) {
                        listener.onMovementFinished(false, "timeout");
                    }
                }
            }, MOVEMENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            
            // Execute navigation
            currentMovement = goTo.async().run();
            
            if (listener != null) {
                listener.onMovementStarted();
            }
            
            // Handle completion
            currentMovement.thenConsume(future -> {
                // Cancel timeout task
                if (currentTimeoutTask != null && !currentTimeoutTask.isDone()) {
                    currentTimeoutTask.cancel(false);
                }
                
                if (listener != null) {
                    if (future.isSuccess()) {
                        Log.d(TAG, "Navigation completed successfully");
                        listener.onMovementFinished(true, null);
                    } else if (future.isCancelled()) {
                        Log.w(TAG, "Navigation was cancelled");
                        listener.onMovementFinished(false, "cancelled");
                    } else if (future.hasError()) {
                        String error = future.getError() != null ? future.getError().getMessage() : "Unknown error";
                        Log.e(TAG, "Navigation failed: " + error);
                        listener.onMovementFinished(false, error);
                    }
                }
                
                currentMovement = null;
                currentTimeoutTask = null;
            });
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting navigation", e);
            if (listener != null) {
                listener.onMovementFinished(false, "Navigation setup failed: " + e.getMessage());
            }
        }
    }
    
    /**
     * Clean up resources when MovementController is no longer needed
     */
    @SuppressWarnings("unused")
    public void shutdown() {
        if (timeoutExecutor != null && !timeoutExecutor.isShutdown()) {
            timeoutExecutor.shutdown();
            Log.d(TAG, "MovementController executor shutdown");
        }
    }
}
