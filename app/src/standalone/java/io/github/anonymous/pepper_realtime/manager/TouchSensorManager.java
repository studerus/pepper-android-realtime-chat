package io.github.anonymous.pepper_realtime.manager;

import android.util.Log;

/**
 * Stub implementation of TouchSensorManager for standalone mode (no robot
 * hardware).
 * Simulates touch sensor functionality without actual hardware.
 */
public class TouchSensorManager {
    private static final String TAG = "TouchSensorManager[STUB]";

    public interface TouchEventListener {
        void onSensorTouched(String sensorName, Object touchState);

        void onSensorReleased(String sensorName, Object touchState);
    }

    /**
     * Simulates initializing touch sensors
     */
    public void initialize(Object qiContext) {
        Log.i(TAG, "🤖 [SIMULATED] TouchSensorManager initialized");
    }

    /**
     * Sets the touch event listener
     */
    public void setListener(TouchEventListener listener) {
        // Listener not used in standalone simulation
    }

    /**
     * Creates a human-readable touch message
     */
    public static String createTouchMessage(String sensorName) {
        return "[Sensor touched: " + sensorName + "]";
    }

    /**
     * Shuts down the manager
     */
    public void shutdown() {
        Log.i(TAG, "🤖 [SIMULATED] TouchSensorManager shutdown");
    }

    public void pause() {
        Log.i(TAG, "🤖 [SIMULATED] TouchSensorManager paused");
    }

    public void resume() {
        Log.i(TAG, "🤖 [SIMULATED] TouchSensorManager resumed");
    }
}
