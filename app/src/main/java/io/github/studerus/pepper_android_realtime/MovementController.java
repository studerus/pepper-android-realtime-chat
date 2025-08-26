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
import com.aldebaran.qi.sdk.builder.GoToBuilder;
import com.aldebaran.qi.sdk.builder.TransformBuilder;

public class MovementController {
    private static final String TAG = "MovementController";
    
    public interface MovementListener {
        void onMovementStarted();
        void onMovementFinished(boolean success, String error);
    }
    
    private MovementListener listener;
    
    public void setListener(MovementListener listener) {
        this.listener = listener;
    }
    
    /**
     * Move Pepper in a specified direction
     * @param qiContext QiContext for accessing robot services
     * @param direction "forward", "backward", "left", "right"
     * @param distance distance in meters (0.5-3.0)
     * @param speed optional speed in m/s (0.1-0.55), default 0.4
     */
    public void movePepper(QiContext qiContext, String direction, double distance, double speed) {
        if (qiContext == null) {
            Log.e(TAG, "QiContext is null - cannot move Pepper");
            if (listener != null) listener.onMovementFinished(false, "Robot not ready");
            return;
        }
        
        try {
            // Validate parameters
            if (distance < 0.5 || distance > 3.0) {
                throw new IllegalArgumentException("Distance must be between 0.5 and 3.0 meters");
            }
            if (speed < 0.1 || speed > 0.55) {
                speed = 0.4; // Use default speed
            }
            
            // Get Actuation and Mapping services
            Actuation actuation = qiContext.getActuation();
            Mapping mapping = qiContext.getMapping();
            
            // Get robot frame
            Frame robotFrame = actuation.robotFrame();
            
            // Create transform based on direction
            Transform transform = createTransform(direction, distance);
            
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
                Log.i(TAG, "Movement started: " + direction + " " + distance + "m");
                if (listener != null) listener.onMovementStarted();
            });
            
            // Execute movement
            Future<Void> goToFuture = goTo.async().run();
            goToFuture.thenConsume(future -> {
                goTo.removeAllOnStartedListeners();
                
                if (future.isSuccess()) {
                    Log.i(TAG, "Movement finished successfully");
                    if (listener != null) listener.onMovementFinished(true, null);
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
    
    private Transform createTransform(String direction, double distance) {
        switch (direction.toLowerCase()) {
            case "forward":
                return TransformBuilder.create().fromXTranslation(distance);
            case "backward":
                return TransformBuilder.create().fromXTranslation(-distance);
            case "left":
                return TransformBuilder.create().from2DTranslation(0, distance);
            case "right":
                return TransformBuilder.create().from2DTranslation(0, -distance);
            default:
                throw new IllegalArgumentException("Invalid direction: " + direction + ". Use forward, backward, left, or right.");
        }
    }
}
