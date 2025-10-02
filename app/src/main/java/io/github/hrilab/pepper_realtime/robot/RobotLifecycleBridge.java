package io.github.hrilab.pepper_realtime.robot;

import android.app.Activity;

/**
 * Bridge interface for robot lifecycle management.
 * Flavor-specific implementations handle QiSDK registration (Pepper) or simulation (Standalone).
 */
public interface RobotLifecycleBridge {
    
    /**
     * Register for robot lifecycle events
     * @param activity The activity to register
     * @param listener Callback for lifecycle events
     */
    void register(Activity activity, RobotLifecycleListener listener);
    
    /**
     * Unregister from robot lifecycle events
     * @param activity The activity to unregister
     */
    void unregister(Activity activity);
    
    /**
     * Get the robot controller
     */
    RobotController getRobotController();
    
    /**
     * Callback interface for robot lifecycle events
     */
    interface RobotLifecycleListener {
        /**
         * Called when robot is ready (focus gained or simulation initialized)
         */
        void onRobotReady(Object robotContext);
        
        /**
         * Called when robot focus is lost (Pepper) or simulation paused (Standalone)
         */
        void onRobotFocusLost();
    }
}


