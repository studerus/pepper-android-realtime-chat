package io.github.anonymous.pepper_realtime.manager

import android.util.Log

/**
 * Stub implementation of TouchSensorManager for standalone mode (no robot hardware).
 * Simulates touch sensor functionality without actual hardware.
 */
class TouchSensorManager {

    interface TouchEventListener {
        fun onSensorTouched(sensorName: String, touchState: Any?)
        fun onSensorReleased(sensorName: String, touchState: Any?)
    }

    companion object {
        private const val TAG = "TouchSensorManager[STUB]"

        /**
         * Creates a human-readable touch message
         */
        @JvmStatic
        fun createTouchMessage(sensorName: String): String {
            return "[Sensor touched: $sensorName]"
        }
    }

    /**
     * Simulates initializing touch sensors
     */
    fun initialize(@Suppress("UNUSED_PARAMETER") qiContext: Any?) {
        Log.i(TAG, "🤖 [SIMULATED] TouchSensorManager initialized")
    }

    /**
     * Sets the touch event listener
     */
    fun setListener(@Suppress("UNUSED_PARAMETER") listener: TouchEventListener?) {
        // Listener not used in standalone simulation
    }

    /**
     * Shuts down the manager
     */
    fun shutdown() {
        Log.i(TAG, "🤖 [SIMULATED] TouchSensorManager shutdown")
    }

    fun pause() {
        Log.i(TAG, "🤖 [SIMULATED] TouchSensorManager paused")
    }

    fun resume() {
        Log.i(TAG, "🤖 [SIMULATED] TouchSensorManager resumed")
    }
}

