package io.github.anonymous.pepper_realtime.manager;

import android.util.Log;

import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.object.touch.Touch;
import com.aldebaran.qi.sdk.object.touch.TouchSensor;
import com.aldebaran.qi.sdk.object.touch.TouchState;

import io.github.anonymous.pepper_realtime.manager.ThreadManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages all touch sensors on the Pepper robot
 * Handles touch events and provides callbacks for interruption and messaging
 */
public class TouchSensorManager {
    
    public interface TouchEventListener {
        /**
         * Called when a touch sensor is touched
         * @param sensorName Name of the touched sensor (e.g., "Head/Touch")
         * @param touchState The touch state information (TouchState for Pepper, Object for Standalone)
         */
        void onSensorTouched(String sensorName, Object touchState);
        
        /**
         * Called when a touch sensor is released
         * @param sensorName Name of the released sensor
         * @param touchState The touch state information (TouchState for Pepper, Object for Standalone)
         */
        void onSensorReleased(String sensorName, Object touchState);
    }
    
    private static final String TAG = "TouchSensorManager";
    
    // Available touch sensors on Pepper
    private static final String[] TOUCH_SENSOR_NAMES = {
        "Head/Touch",
        "LHand/Touch", 
        "RHand/Touch",
        "Bumper/FrontLeft",
        "Bumper/FrontRight", 
        "Bumper/Back"
    };
    
    // Debouncing timeout in milliseconds to prevent multiple rapid touches
    private static final long TOUCH_DEBOUNCE_MS = 500;
    
    private TouchEventListener listener;
    private final Map<String, TouchSensor> touchSensors = new HashMap<>();
    private final Map<String, Long> lastTouchTimes = new HashMap<>();
    private volatile boolean isPaused = false;
    
    public TouchSensorManager() {
        // Initialize last touch times
        for (String sensorName : TOUCH_SENSOR_NAMES) {
            lastTouchTimes.put(sensorName, 0L);
        }
    }
    
    /**
     * Set the listener for touch events
     */
    public void setListener(TouchEventListener listener) {
        this.listener = listener;
    }
    
    /**
     * Initialize touch sensors with QiContext.
     * Must be called when robot focus is gained.
     * Handles reinitialization after shutdown by clearing old sensors first.
     */
    public void initialize(Object robotContext) {
        if (robotContext == null) {
            Log.w(TAG, "Cannot initialize - robot context is null");
            return;
        }
        QiContext qiContext = (QiContext) robotContext;
        
        try {
            // Clear any old sensors first (handles reinitialization after restart)
            if (!touchSensors.isEmpty()) {
                Log.i(TAG, "Clearing old touch sensors before reinitialization");
                touchSensors.clear();
            }
            
            // Reset paused state on reinitialization
            isPaused = false;
            
            // Reset debouncing timestamps on reinitialization
            for (String sensorName : TOUCH_SENSOR_NAMES) {
                lastTouchTimes.put(sensorName, 0L);
            }
            
            Touch touch = qiContext.getTouch();
            List<String> availableSensors = touch.getSensorNames();
            
            Log.i(TAG, "Available touch sensors: " + availableSensors);
            
            // Initialize listeners for each available sensor
            for (String sensorName : TOUCH_SENSOR_NAMES) {
                if (availableSensors.contains(sensorName)) {
                    initializeSensor(touch, sensorName);
                } else {
                    Log.w(TAG, "Touch sensor not available: " + sensorName);
                }
            }
            
            Log.i(TAG, "TouchSensorManager initialized with " + touchSensors.size() + " sensors");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize touch sensors", e);
        }
    }
    
    /**
     * Initialize a specific touch sensor
     */
    private void initializeSensor(Touch touch, String sensorName) {
        try {
            TouchSensor sensor = touch.getSensor(sensorName);
            touchSensors.put(sensorName, sensor);
            
            // Add state change listener with debouncing
            sensor.addOnStateChangedListener(touchState -> handleTouchEvent(sensorName, touchState));
            
            Log.d(TAG, "Initialized touch sensor: " + sensorName);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize sensor: " + sensorName, e);
        }
    }
    
    /**
     * Pause touch sensor monitoring (for navigation/localization)
     */
    public void pause() {
        isPaused = true;
        Log.i(TAG, "TouchSensorManager paused - events will be ignored");
    }
    
    /**
     * Resume touch sensor monitoring
     */
    public void resume() {
        isPaused = false;
        Log.i(TAG, "TouchSensorManager resumed - events will be processed");
    }
    
    /**
     * Handle touch events with debouncing and callback
     */
    private void handleTouchEvent(String sensorName, TouchState touchState) {
        try {
            // Ignore events if paused
            if (isPaused) {
                Log.d(TAG, "Touch event ignored - TouchSensorManager is paused: " + sensorName);
                return;
            }
            
            long currentTime = System.currentTimeMillis();
            Long lastTouchBoxed = lastTouchTimes.get(sensorName);
            long lastTouchTime = lastTouchBoxed != null ? lastTouchBoxed : 0L;
            
            // Debouncing: ignore rapid successive touches
            if (currentTime - lastTouchTime < TOUCH_DEBOUNCE_MS) {
                Log.d(TAG, "Touch event ignored due to debouncing: " + sensorName);
                return;
            }
            
            lastTouchTimes.put(sensorName, currentTime);
            
            boolean isTouched = touchState.getTouched();
            
            Log.i(TAG, "Touch sensor " + sensorName + " " + (isTouched ? "touched" : "released") + 
                       " at time: " + touchState.getTime());
            
            // Call listener if available
            if (listener != null) {
                if (isTouched) {
                    listener.onSensorTouched(sensorName, touchState);
                } else {
                    listener.onSensorReleased(sensorName, touchState);
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling touch event for " + sensorName, e);
        }
    }
    
    /**
     * Create human-readable message for touch sensor events
     */
    public static String createTouchMessage(String sensorName) {
        switch (sensorName) {
            case "Head/Touch":
                return "[User touched my head]";
            case "LHand/Touch":
                return "[User touched my left hand]";
            case "RHand/Touch":
                return "[User touched my right hand]";
            case "Bumper/FrontLeft":
                return "[User touched my front left bumper]";
            case "Bumper/FrontRight":
                return "[User touched my front right bumper]";
            case "Bumper/Back":
                return "[User touched my back bumper]";
            default:
                return "[User touched sensor: " + sensorName + "]";
        }
    }
    
    /**
     * Cleanup when robot focus is lost
     */
    public void shutdown() {
        try {
            // Remove listeners off the main thread to avoid NetworkOnMainThreadException
            final Map<String, TouchSensor> sensorsSnapshot = new java.util.HashMap<>(touchSensors);
            
            // Run cleanup in a new thread if ThreadManager is no longer available
            // (can happen during app shutdown when ThreadManager is destroyed first)
            Runnable cleanupTask = () -> {
                for (Map.Entry<String, TouchSensor> entry : sensorsSnapshot.entrySet()) {
                    try {
                        TouchSensor sensor = entry.getValue();
                        if (sensor != null) {
                            sensor.removeAllOnStateChangedListeners();
                            Log.d(TAG, "Removed listeners from sensor: " + entry.getKey());
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Error removing listeners from sensor: " + entry.getKey(), e);
                    }
                }
                Log.i(TAG, "TouchSensorManager listeners removal completed");
            };
            
            try {
                ThreadManager.getInstance().executeNetwork(cleanupTask);
            } catch (IllegalStateException e) {
                // ThreadManager already shut down, run cleanup directly on a new thread
                Log.d(TAG, "ThreadManager unavailable, running cleanup on new thread");
                new Thread(cleanupTask, "touch-sensor-cleanup").start();
            }

            touchSensors.clear();
            Log.i(TAG, "TouchSensorManager shutdown scheduled");

        } catch (Exception e) {
            Log.e(TAG, "Error during TouchSensorManager shutdown", e);
        }
    }
}
