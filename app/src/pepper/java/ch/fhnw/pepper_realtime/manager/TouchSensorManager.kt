package ch.fhnw.pepper_realtime.manager

import android.util.Log
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.touch.Touch
import com.aldebaran.qi.sdk.`object`.touch.TouchSensor
import com.aldebaran.qi.sdk.`object`.touch.TouchState
import kotlinx.coroutines.*

/**
 * Manages all touch sensors on the Pepper robot
 * Handles touch events and provides callbacks for interruption and messaging
 */
class TouchSensorManager {

    interface TouchEventListener {
        /**
         * Called when a touch sensor is touched
         * @param sensorName Name of the touched sensor (e.g., "Head/Touch")
         * @param touchState The touch state information (TouchState for Pepper, Object for Standalone)
         */
        fun onSensorTouched(sensorName: String, touchState: Any?)

        /**
         * Called when a touch sensor is released
         * @param sensorName Name of the released sensor
         * @param touchState The touch state information (TouchState for Pepper, Object for Standalone)
         */
        fun onSensorReleased(sensorName: String, touchState: Any?)
    }

    companion object {
        private const val TAG = "TouchSensorManager"

        // Available touch sensors on Pepper
        private val TOUCH_SENSOR_NAMES = arrayOf(
            "Head/Touch",
            "LHand/Touch",
            "RHand/Touch",
            "Bumper/FrontLeft",
            "Bumper/FrontRight",
            "Bumper/Back"
        )

        // Debouncing timeout in milliseconds to prevent multiple rapid touches
        private const val TOUCH_DEBOUNCE_MS = 500L

        /**
         * Create human-readable message for touch sensor events
         */
        fun createTouchMessage(sensorName: String): String {
            return when (sensorName) {
                "Head/Touch" -> "[User touched my head]"
                "LHand/Touch" -> "[User touched my left hand]"
                "RHand/Touch" -> "[User touched my right hand]"
                "Bumper/FrontLeft" -> "[User touched my front left bumper]"
                "Bumper/FrontRight" -> "[User touched my front right bumper]"
                "Bumper/Back" -> "[User touched my back bumper]"
                else -> "[User touched sensor: $sensorName]"
            }
        }
    }

    private var listener: TouchEventListener? = null
    private val touchSensors = mutableMapOf<String, TouchSensor>()
    private val lastTouchTimes = mutableMapOf<String, Long>()
    @Volatile private var isPaused = false

    init {
        // Initialize last touch times
        for (sensorName in TOUCH_SENSOR_NAMES) {
            lastTouchTimes[sensorName] = 0L
        }
    }

    /**
     * Set the listener for touch events
     */
    fun setListener(listener: TouchEventListener?) {
        this.listener = listener
    }

    /**
     * Initialize touch sensors with QiContext.
     * Must be called when robot focus is gained.
     * Handles reinitialization after shutdown by clearing old sensors first.
     */
    fun initialize(robotContext: Any?) {
        if (robotContext == null) {
            Log.w(TAG, "Cannot initialize - robot context is null")
            return
        }
        val qiContext = robotContext as QiContext

        try {
            // Clear any old sensors first (handles reinitialization after restart)
            if (touchSensors.isNotEmpty()) {
                Log.i(TAG, "Clearing old touch sensors before reinitialization")
                touchSensors.clear()
            }

            // Reset paused state on reinitialization
            isPaused = false

            // Reset debouncing timestamps on reinitialization
            for (sensorName in TOUCH_SENSOR_NAMES) {
                lastTouchTimes[sensorName] = 0L
            }

            val touch: Touch = qiContext.touch
            val availableSensors = touch.sensorNames

            Log.i(TAG, "Available touch sensors: $availableSensors")

            // Initialize listeners for each available sensor
            for (sensorName in TOUCH_SENSOR_NAMES) {
                if (availableSensors.contains(sensorName)) {
                    initializeSensor(touch, sensorName)
                } else {
                    Log.w(TAG, "Touch sensor not available: $sensorName")
                }
            }

            Log.i(TAG, "TouchSensorManager initialized with ${touchSensors.size} sensors")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize touch sensors", e)
        }
    }

    /**
     * Initialize a specific touch sensor
     */
    private fun initializeSensor(touch: Touch, sensorName: String) {
        try {
            val sensor = touch.getSensor(sensorName)
            touchSensors[sensorName] = sensor

            // Add state change listener with debouncing
            sensor.addOnStateChangedListener { touchState -> handleTouchEvent(sensorName, touchState) }

            Log.d(TAG, "Initialized touch sensor: $sensorName")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize sensor: $sensorName", e)
        }
    }

    /**
     * Pause touch sensor monitoring (for navigation/localization)
     */
    fun pause() {
        isPaused = true
        Log.i(TAG, "TouchSensorManager paused - events will be ignored")
    }

    /**
     * Resume touch sensor monitoring
     */
    fun resume() {
        isPaused = false
        Log.i(TAG, "TouchSensorManager resumed - events will be processed")
    }

    /**
     * Handle touch events with debouncing and callback
     */
    private fun handleTouchEvent(sensorName: String, touchState: TouchState) {
        try {
            // Ignore events if paused
            if (isPaused) {
                Log.d(TAG, "Touch event ignored - TouchSensorManager is paused: $sensorName")
                return
            }

            val currentTime = System.currentTimeMillis()
            val lastTouchTime = lastTouchTimes[sensorName] ?: 0L

            // Debouncing: ignore rapid successive touches
            if (currentTime - lastTouchTime < TOUCH_DEBOUNCE_MS) {
                Log.d(TAG, "Touch event ignored due to debouncing: $sensorName")
                return
            }

            lastTouchTimes[sensorName] = currentTime

            val isTouched = touchState.touched

            Log.i(TAG, "Touch sensor $sensorName ${if (isTouched) "touched" else "released"} at time: ${touchState.time}")

            // Call listener if available
            listener?.let { l ->
                if (isTouched) {
                    l.onSensorTouched(sensorName, touchState)
                } else {
                    l.onSensorReleased(sensorName, touchState)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling touch event for $sensorName", e)
        }
    }

    /**
     * Cleanup when robot focus is lost
     */
    fun shutdown() {
        try {
            // Remove listeners off the main thread to avoid NetworkOnMainThreadException
            val sensorsSnapshot = HashMap(touchSensors)

            // Run cleanup in a coroutine
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                for ((key, sensor) in sensorsSnapshot) {
                    try {
                        sensor.removeAllOnStateChangedListeners()
                        Log.d(TAG, "Removed listeners from sensor: $key")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error removing listeners from sensor: $key", e)
                    }
                }
                Log.i(TAG, "TouchSensorManager listeners removal completed")
            }

            touchSensors.clear()
            Log.i(TAG, "TouchSensorManager shutdown scheduled")

        } catch (e: Exception) {
            Log.e(TAG, "Error during TouchSensorManager shutdown", e)
        }
    }
}


