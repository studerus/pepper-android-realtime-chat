package io.github.anonymous.pepper_realtime.robot;

import android.util.Log;

/**
 * Stub implementation of RobotSafetyGuard for standalone mode (no robot hardware).
 * Simulates safety monitoring functionality.
 */
public class RobotSafetyGuard {
    private static final String TAG = "RobotSafetyGuard[STUB]";

    /**
     * Simulates initializing safety guard
     */
    public void initialize(Object qiContext) {
        Log.i(TAG, "🤖 [SIMULATED] RobotSafetyGuard initialized");
    }

    /**
     * Simulates starting safety monitoring
     */
    public void startMonitoring() {
        Log.i(TAG, "🤖 [SIMULATED] Safety monitoring started");
    }

    /**
     * Simulates stopping safety monitoring
     */
    public void stopMonitoring() {
        Log.i(TAG, "🤖 [SIMULATED] Safety monitoring stopped");
    }

    /**
     * Checks if safe to move (always true in standalone)
     */
    public boolean isSafeToMove() {
        return true;
    }

    /**
     * Shuts down the guard
     */
    public void shutdown() {
        Log.i(TAG, "🤖 [SIMULATED] RobotSafetyGuard shutdown");
    }
}

