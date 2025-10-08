package io.github.anonymous.pepper_realtime.robot;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Standalone implementation - simulates robot lifecycle without QiSDK.
 */
public class RobotLifecycleBridgeImpl implements RobotLifecycleBridge {
    
    private static final String TAG = "RobotLifecycleBridge";
    private final RobotControllerImpl robotController;
    private RobotLifecycleListener listener;
    
    public RobotLifecycleBridgeImpl() {
        this.robotController = new RobotControllerImpl();
    }
    
    @Override
    public void register(Activity activity, RobotLifecycleListener listener) {
        this.listener = listener;
        Log.i(TAG, "🤖 STANDALONE MODE: Simulating robot initialization...");
        
        // Initialize simulation controller
        robotController.initialize();
        
        // Simulate robot ready callback after a short delay (like real QiSDK would)
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Log.i(TAG, "🤖 STANDALONE MODE: Simulation ready");
            if (this.listener != null) {
                this.listener.onRobotReady(null);
            }
        }, 500);
    }
    
    @Override
    public void unregister(Activity activity) {
        Log.i(TAG, "🤖 STANDALONE MODE: Unregistering simulation");
        // No cleanup needed for simulation
    }
    
    @Override
    public RobotController getRobotController() {
        return robotController;
    }
}


