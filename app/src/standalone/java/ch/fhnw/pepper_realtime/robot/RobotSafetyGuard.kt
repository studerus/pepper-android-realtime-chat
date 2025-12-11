package ch.fhnw.pepper_realtime.robot

import android.util.Log

/**
 * Stub implementation of RobotSafetyGuard for standalone mode (no robot hardware).
 * Simulates safety monitoring functionality.
 */
class RobotSafetyGuard {

    companion object {
        private const val TAG = "RobotSafetyGuard[STUB]"
    }

    /**
     * Simulates initializing safety guard
     */
    fun initialize(@Suppress("UNUSED_PARAMETER") qiContext: Any?) {
        Log.i(TAG, "🤖 [SIMULATED] RobotSafetyGuard initialized")
    }

    /**
     * Simulates starting safety monitoring
     */
    fun startMonitoring() {
        Log.i(TAG, "🤖 [SIMULATED] Safety monitoring started")
    }

    /**
     * Simulates stopping safety monitoring
     */
    fun stopMonitoring() {
        Log.i(TAG, "🤖 [SIMULATED] Safety monitoring stopped")
    }

    /**
     * Checks if safe to move (always true in standalone)
     */
    fun isSafeToMove(): Boolean = true

    /**
     * Shuts down the guard
     */
    fun shutdown() {
        Log.i(TAG, "🤖 [SIMULATED] RobotSafetyGuard shutdown")
    }
}

