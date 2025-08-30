package io.github.studerus.pepper_android_realtime;

import android.util.Log;

import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.object.touch.Touch;
import com.aldebaran.qi.sdk.object.touch.TouchSensor;
import com.aldebaran.qi.sdk.object.touch.TouchState;

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
         * @param touchState The touch state information
         */
        void onSensorTouched(String sensorName, TouchState touchState);
        
        /**
         * Called when a touch sensor is released
         * @param sensorName Name of the released sensor
         * @param touchState The touch state information  
         */
        void onSensorReleased(String sensorName, TouchState touchState);
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
     * Initialize touch sensors with QiContext
     * Must be called when robot focus is gained
     */
    public void initialize(QiContext qiContext) {
        
        if (qiContext == null) {
            Log.w(TAG, "Cannot initialize - QiContext is null");
            return;
        }
        
        try {
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
     * Check if touch sensor is currently paused
     */
    public boolean isPaused() {
        return isPaused;
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
     * Cleanup when robot focus is lost
     */
    public void shutdown() {
        try {
            touchSensors.clear();
            
            Log.i(TAG, "TouchSensorManager shutdown completed");
            
        } catch (Exception e) {
            Log.e(TAG, "Error during TouchSensorManager shutdown", e);
        }
    }
}
