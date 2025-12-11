package ch.fhnw.pepper_realtime.robot

import android.app.Activity

/**
 * Bridge interface for robot lifecycle management.
 * Flavor-specific implementations handle QiSDK registration (Pepper) or simulation (Standalone).
 */
interface RobotLifecycleBridge {

    /**
     * Register for robot lifecycle events
     * @param activity The activity to register
     * @param listener Callback for lifecycle events
     */
    fun register(activity: Activity, listener: RobotLifecycleListener)

    /**
     * Unregister from robot lifecycle events
     * @param activity The activity to unregister
     */
    fun unregister(activity: Activity)

    /**
     * Get the robot controller
     */
    fun getRobotController(): RobotController

    /**
     * Callback interface for robot lifecycle events
     */
    interface RobotLifecycleListener {
        /**
         * Called when robot is ready (focus gained or simulation initialized)
         */
        fun onRobotReady(robotContext: Any?)

        /**
         * Called when robot focus is lost (Pepper) or simulation paused (Standalone)
         */
        fun onRobotFocusLost()
    }
}

