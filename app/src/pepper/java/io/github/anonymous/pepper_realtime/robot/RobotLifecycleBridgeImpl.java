package io.github.anonymous.pepper_realtime.robot;

import android.app.Activity;
import android.util.Log;

import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.QiSDK;
import com.aldebaran.qi.sdk.RobotLifecycleCallbacks;

/**
 * Pepper-specific implementation using real QiSDK lifecycle callbacks.
 */
public class RobotLifecycleBridgeImpl implements RobotLifecycleBridge, RobotLifecycleCallbacks {
    
    private static final String TAG = "RobotLifecycleBridge";
    private final RobotControllerImpl robotController;
    private RobotLifecycleListener listener;
    
    public RobotLifecycleBridgeImpl() {
        this.robotController = new RobotControllerImpl();
    }
    
    @Override
    public void register(Activity activity, RobotLifecycleListener listener) {
        this.listener = listener;
        Log.i(TAG, "🤖 Registering with QiSDK for robot lifecycle callbacks...");
        try {
            QiSDK.register(activity, this);
            Log.i(TAG, "🤖 QiSDK registration successful");
        } catch (Exception e) {
            Log.e(TAG, "QiSDK registration failed", e);
        }
    }
    
    @Override
    public void unregister(Activity activity) {
        Log.i(TAG, "🤖 Unregistering from QiSDK");
        QiSDK.unregister(activity, this);
    }
    
    @Override
    public RobotController getRobotController() {
        return robotController;
    }
    
    // ===== QiSDK RobotLifecycleCallbacks =====
    
    @Override
    public void onRobotFocusGained(QiContext qiContext) {
        Log.i(TAG, "🤖 Robot focus gained - Pepper is ready");
        robotController.setQiContext(qiContext);
        if (listener != null) {
            listener.onRobotReady(qiContext);
        }
    }
    
    @Override
    public void onRobotFocusLost() {
        Log.i(TAG, "🤖 Robot focus lost");
        robotController.setQiContext(null);
        if (listener != null) {
            listener.onRobotFocusLost();
        }
    }
    
    @Override
    public void onRobotFocusRefused(String reason) {
        Log.e(TAG, "🤖 Robot focus refused: " + reason);
    }
}


