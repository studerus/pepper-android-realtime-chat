package io.github.hrilab.pepper_realtime.robot;

import android.util.Log;

/**
 * Standalone implementation of RobotController for Android smartphones/tablets.
 * Returns null for robot context - services will provide stub/simulated behavior.
 */
public class RobotControllerImpl implements RobotController {
    
    private static final String TAG = "RobotControllerImpl";
    private boolean isInitialized = false;
    
    /**
     * Initialize the standalone controller
     */
    public void initialize() {
        Log.i(TAG, "🤖 STANDALONE MODE: Initializing simulation environment");
        isInitialized = true;
        Log.i(TAG, "🤖 STANDALONE MODE: Ready - robot features will be simulated");
    }
    
    @Override
    public Object getRobotContext() {
        return null;  // No QiContext in standalone mode
    }
    
    @Override
    public boolean isRobotHardwareAvailable() {
        return false;  // Running on generic Android device
    }
    
    @Override
    public boolean isReady() {
        return isInitialized;
    }
    
    @Override
    public String getModeName() {
        return "Standalone Mode (Simulation)";
    }
}


