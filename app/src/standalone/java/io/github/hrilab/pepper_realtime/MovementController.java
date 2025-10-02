package io.github.hrilab.pepper_realtime;

import android.util.Log;

/**
 * Stub implementation of MovementController for standalone mode (no robot hardware).
 * Logs all movement commands and simulates successful execution.
 */
public class MovementController {
    private static final String TAG = "MovementController[STUB]";
    
    public interface MovementListener {
        void onMovementStarted();
        void onMovementFinished(boolean success, String error);
    }
    
    private MovementListener listener;
    
    public void setListener(MovementListener listener) {
        this.listener = listener;
    }
    
    /**
     * Simulates moving Pepper in a specified direction
     */
    public void movePepper(Object qiContext, double distanceForward, double distanceSideways, double speed) {
        Log.i(TAG, String.format("🤖 [SIMULATED] Move - Forward: %.2fm, Sideways: %.2fm, Speed: %.2fm/s", 
            distanceForward, distanceSideways, speed));
        
        if (listener != null) {
            listener.onMovementStarted();
            // Simulate successful movement after a short delay
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (listener != null) {
                    listener.onMovementFinished(true, null);
                }
            }, 500);
        }
    }
    
    /**
     * Simulates turning Pepper
     */
    public void turnPepper(Object qiContext, double angleDegrees, double speed) {
        Log.i(TAG, String.format("🤖 [SIMULATED] Turn - Angle: %.1f°, Speed: %.2f", angleDegrees, speed));
        
        if (listener != null) {
            listener.onMovementStarted();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (listener != null) {
                    listener.onMovementFinished(true, null);
                }
            }, 500);
        }
    }
    
    /**
     * Simulates looking at a position
     */
    public void lookAtPosition(Object qiContext, double x, double y, double z) {
        Log.i(TAG, String.format("🤖 [SIMULATED] Look at position - X: %.2f, Y: %.2f, Z: %.2f", x, y, z));
        
        if (listener != null) {
            listener.onMovementStarted();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (listener != null) {
                    listener.onMovementFinished(true, null);
                }
            }, 300);
        }
    }
    
    /**
     * Cancels current movement (no-op in stub)
     */
    public void cancelCurrentMovement() {
        Log.i(TAG, "🤖 [SIMULATED] Cancel movement");
        if (listener != null) {
            listener.onMovementFinished(false, "Cancelled");
        }
    }
    
    /**
     * Shuts down the controller (no-op in stub)
     */
    public void shutdown() {
        Log.i(TAG, "🤖 [SIMULATED] Shutdown");
    }
}

