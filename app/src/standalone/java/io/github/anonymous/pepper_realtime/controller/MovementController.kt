package io.github.anonymous.pepper_realtime.controller

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Stub implementation of MovementController for standalone mode (no robot hardware).
 * Logs all movement commands and simulates successful execution.
 */
class MovementController {
    
    interface MovementListener {
        fun onMovementStarted()
        fun onMovementFinished(success: Boolean, error: String?)
    }

    companion object {
        private const val TAG = "MovementController[STUB]"
    }

    private var listener: MovementListener? = null

    fun setListener(listener: MovementListener?) {
        this.listener = listener
    }

    /**
     * Simulates moving Pepper in a specified direction
     */
    @Suppress("UNUSED_PARAMETER")
    fun movePepper(qiContext: Any?, distanceForward: Double, distanceSideways: Double, speed: Double) {
        Log.i(TAG, "🤖 [SIMULATED] Move - Forward: %.2fm, Sideways: %.2fm, Speed: %.2fm/s".format(
            distanceForward, distanceSideways, speed))

        listener?.let { l ->
            l.onMovementStarted()
            // Simulate successful movement after a short delay
            Handler(Looper.getMainLooper()).postDelayed({
                l.onMovementFinished(true, null)
            }, 500)
        }
    }

    /**
     * Simulates turning Pepper
     */
    @Suppress("UNUSED_PARAMETER")
    fun turnPepper(qiContext: Any?, direction: String, degrees: Double, speed: Double) {
        Log.i(TAG, "🤖 [SIMULATED] Turn - Direction: $direction, Angle: %.1f°, Speed: %.2f".format(degrees, speed))

        listener?.let { l ->
            l.onMovementStarted()
            Handler(Looper.getMainLooper()).postDelayed({
                l.onMovementFinished(true, null)
            }, 500)
        }
    }

    /**
     * Simulates looking at a position
     */
    @Suppress("UNUSED_PARAMETER")
    fun lookAtPosition(qiContext: Any?, x: Double, y: Double, z: Double) {
        Log.i(TAG, "🤖 [SIMULATED] Look at position - X: %.2f, Y: %.2f, Z: %.2f".format(x, y, z))

        listener?.let { l ->
            l.onMovementStarted()
            Handler(Looper.getMainLooper()).postDelayed({
                l.onMovementFinished(true, null)
            }, 300)
        }
    }

    /**
     * Simulates navigation to a saved location
     */
    @Suppress("UNUSED_PARAMETER")
    fun navigateToLocation(qiContext: Any?, savedLocation: Any?, speed: Float) {
        Log.i(TAG, "🤖 [SIMULATED] Navigate to location with speed: $speed m/s")

        listener?.let { l ->
            l.onMovementStarted()
            Handler(Looper.getMainLooper()).postDelayed({
                l.onMovementFinished(true, null)
            }, 1000)
        }
    }

    /**
     * Cancels current movement (no-op in stub)
     */
    fun cancelCurrentMovement() {
        Log.i(TAG, "🤖 [SIMULATED] Cancel movement")
        listener?.onMovementFinished(false, "Cancelled")
    }

    /**
     * Shuts down the controller (no-op in stub)
     */
    fun shutdown() {
        Log.i(TAG, "🤖 [SIMULATED] Shutdown")
    }
}

