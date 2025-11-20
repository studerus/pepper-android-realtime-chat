package io.github.anonymous.pepper_realtime.controller;

import android.util.Log;

/**
 * Stub implementation of GestureController for standalone mode (no robot hardware).
 * Logs all gesture/animation commands without executing them.
 */
public class GestureController {
    public interface BoolSupplier { boolean get(); }
    public interface IntSupplier { Integer get(); }

    private static final String TAG = "GestureController[STUB]";
    private volatile boolean running = false;

    /**
     * Simulates starting gesture loop
     */
    public void start(Object qiContext, BoolSupplier keepRunning, IntSupplier nextResId) {
        if (running) {
            Log.d(TAG, "🤖 [SIMULATED] GestureController already running");
            return;
        }
        running = true;
        Log.i(TAG, "🤖 [SIMULATED] GestureController started - gestures will be logged");
    }

    /**
     * Simulates stopping gesture loop
     */
    public void stop() {
        if (!running) {
            Log.d(TAG, "🤖 [SIMULATED] GestureController already stopped");
            return;
        }
        running = false;
        Log.i(TAG, "🤖 [SIMULATED] GestureController stopped");
    }

    /**
     * Simulates playing a single animation
     */
    public void playAnimation(Object qiContext, int animationResourceId, Runnable onComplete) {
        Log.i(TAG, "🤖 [SIMULATED] Playing animation with resource ID: " + animationResourceId);
        
        if (onComplete != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(onComplete, 300);
        }
    }

    /**
     * Simulates stopping immediately
     */
    public void stopNow() {
        running = false;
        Log.i(TAG, "🤖 [SIMULATED] GestureController stopped immediately");
    }

    /**
     * Checks if controller is running
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Pause gesture controller (for background transitions)
     */
    public void pause() {
        Log.i(TAG, "🤖 [SIMULATED] GestureController paused");
        stopNow();
    }
    
    /**
     * Resume gesture controller (from background)
     */
    public void resume() {
        Log.d(TAG, "🤖 [SIMULATED] GestureController resumed");
    }
    
    /**
     * Shuts down the controller
     */
    public void shutdown() {
        running = false;
        Log.i(TAG, "🤖 [SIMULATED] GestureController shutdown");
    }
}

