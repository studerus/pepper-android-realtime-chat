package io.github.anonymous.pepper_realtime.controller;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import io.github.anonymous.pepper_realtime.ui.ChatActivity;
import io.github.anonymous.pepper_realtime.R;
import io.github.anonymous.pepper_realtime.robot.RobotController;
import io.github.anonymous.pepper_realtime.robot.RobotLifecycleBridge;
import io.github.anonymous.pepper_realtime.robot.RobotLifecycleBridgeImpl;

public class RobotFocusManager {
    private static final String TAG = "RobotFocusManager";
    private static final long FOCUS_TIMEOUT_MS = 30000; // 30 seconds

    private final ChatActivity activity;
    private final RobotLifecycleBridge robotLifecycleBridge;
    private final RobotController robotController;
    private final Handler focusTimeoutHandler;
    private Runnable focusTimeoutRunnable;
    
    private boolean isFocusAvailable = false;
    private Object qiContext;
    private Listener listener;

    public interface Listener {
        void onRobotReady(Object robotContext);
        void onRobotFocusLost();
        void onRobotInitializationFailed(String error);
        void onRobotFocusRefused(String reason);
    }

    public RobotFocusManager(ChatActivity activity) {
        this.activity = activity;
        this.robotLifecycleBridge = new RobotLifecycleBridgeImpl();
        this.robotController = robotLifecycleBridge.getRobotController();
        this.focusTimeoutHandler = new Handler(Looper.getMainLooper());
        
        Log.i(TAG, "🤖 Initializing robot controller: " + robotController.getModeName());
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void register() {
        try {
            robotLifecycleBridge.register(activity, new RobotLifecycleBridge.RobotLifecycleListener() {
                @Override
                public void onRobotReady(Object robotContext) {
                    handleRobotReady(robotContext);
                }
                
                @Override
                public void onRobotFocusLost() {
                    handleRobotFocusLost();
                }
            });
            
            startFocusTimeout();
            
        } catch (Exception e) {
            Log.e(TAG, "Robot lifecycle registration failed", e);
            if (listener != null) {
                listener.onRobotInitializationFailed("Robot initialization failed.");
            }
        }
    }

    public void unregister() {
        cancelFocusTimeout();
        if (robotLifecycleBridge != null) {
            robotLifecycleBridge.unregister(activity);
        }
    }

    public RobotController getRobotController() {
        return robotController;
    }

    public Object getQiContext() {
        return qiContext;
    }

    public boolean isFocusAvailable() {
        return isFocusAvailable;
    }

    private void startFocusTimeout() {
        cancelFocusTimeout();
        focusTimeoutRunnable = () -> {
            if (!isFocusAvailable) {
                Log.e(TAG, "🤖 DIAGNOSTIC: TIMEOUT - No robot focus response after 30 seconds!");
                Log.e(TAG, "🤖 DIAGNOSTIC: Mode: " + robotController.getModeName());
                if (robotController.isRobotHardwareAvailable()) {
                    Log.e(TAG, "🤖 DIAGNOSTIC: Check: 1) Robot is awake, 2) Robot is enabled, 3) QiSDK service is running");
                }
                if (listener != null) {
                    listener.onRobotInitializationFailed(activity.getString(R.string.robot_initialization_timeout));
                }
            }
        };
        focusTimeoutHandler.postDelayed(focusTimeoutRunnable, FOCUS_TIMEOUT_MS);
    }

    private void cancelFocusTimeout() {
        if (focusTimeoutRunnable != null) {
            focusTimeoutHandler.removeCallbacks(focusTimeoutRunnable);
            focusTimeoutRunnable = null;
        }
    }

    private void handleRobotReady(Object robotContext) {
        Log.i(TAG, "🤖 Robot ready - Mode: " + robotController.getModeName());
        
        cancelFocusTimeout();
        Log.i(TAG, "🤖 DIAGNOSTIC: Robot focus gained, cancelling startup timeout.");
        
        this.qiContext = robotContext;
        this.isFocusAvailable = true;
        
        if (listener != null) {
            listener.onRobotReady(robotContext);
        }
    }

    private void handleRobotFocusLost() {
        Log.w(TAG, "🤖 Robot focus lost - Mode: " + robotController.getModeName());
        
        this.isFocusAvailable = false;
        this.qiContext = null;
        
        if (listener != null) {
            listener.onRobotFocusLost();
        }
    }
}

