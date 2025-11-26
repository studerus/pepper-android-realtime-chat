package io.github.anonymous.pepper_realtime.controller

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Stub implementation of GestureController for standalone mode (no robot hardware).
 * Logs all gesture/animation commands without executing them.
 */
class GestureController {
    
    fun interface BoolSupplier {
        fun get(): Boolean
    }

    fun interface IntSupplier {
        fun get(): Int?
    }

    companion object {
        private const val TAG = "GestureController[STUB]"
    }

    @Volatile
    private var running = false

    /**
     * Simulates starting gesture loop
     */
    fun start(qiContext: Any?, keepRunning: BoolSupplier, nextResId: IntSupplier) {
        if (running) {
            Log.d(TAG, "🤖 [SIMULATED] GestureController already running")
            return
        }
        running = true
        Log.i(TAG, "🤖 [SIMULATED] GestureController started - gestures will be logged")
    }

    /**
     * Simulates stopping gesture loop
     */
    fun stop() {
        if (!running) {
            Log.d(TAG, "🤖 [SIMULATED] GestureController already stopped")
            return
        }
        running = false
        Log.i(TAG, "🤖 [SIMULATED] GestureController stopped")
    }

    /**
     * Simulates playing a single animation
     */
    fun playAnimation(qiContext: Any?, animationResourceId: Int, onComplete: Runnable?) {
        Log.i(TAG, "🤖 [SIMULATED] Playing animation with resource ID: $animationResourceId")

        onComplete?.let {
            Handler(Looper.getMainLooper()).postDelayed(it, 300)
        }
    }

    /**
     * Simulates stopping immediately
     */
    fun stopNow() {
        running = false
        Log.i(TAG, "🤖 [SIMULATED] GestureController stopped immediately")
    }

    /**
     * Checks if controller is running
     */
    fun isRunning(): Boolean = running

    /**
     * Pause gesture controller (for background transitions)
     */
    fun pause() {
        Log.i(TAG, "🤖 [SIMULATED] GestureController paused")
        stopNow()
    }

    /**
     * Resume gesture controller (from background)
     */
    fun resume() {
        Log.d(TAG, "🤖 [SIMULATED] GestureController resumed")
    }

    /**
     * Shuts down the controller
     */
    fun shutdown() {
        running = false
        Log.i(TAG, "🤖 [SIMULATED] GestureController shutdown")
    }

    fun getRandomExplainAnimationResId(): Int {
        // Return a dummy ID for standalone mode
        return 0
    }
}

