package io.github.anonymous.pepper_realtime.service;

import android.util.Log;
import io.github.anonymous.pepper_realtime.data.PerceptionData;

import java.util.ArrayList;
import java.util.List;

/**
 * Stub implementation of PerceptionService for standalone mode (no robot hardware).
 * Simulates perception capabilities without actual sensor data.
 */
public class PerceptionService {

    private static final String TAG = "PerceptionService[STUB]";

    public interface PerceptionListener {
        void onHumansDetected(List<PerceptionData.HumanInfo> humans);
        void onPerceptionError(String error);
        void onServiceStatusChanged(boolean isActive);
    }

    private PerceptionListener listener;
    private boolean isMonitoring = false;

    public PerceptionService() {
        Log.d(TAG, "🤖 [SIMULATED] PerceptionService created");
    }

    /**
     * Set the perception listener for callbacks
     */
    public void setListener(PerceptionListener listener) {
        this.listener = listener;
    }

    /**
     * Simulates initializing the perception service
     */
    public void initialize(Object qiContext) {
        Log.i(TAG, "🤖 [SIMULATED] PerceptionService initialized");
    }

    /**
     * Simulates starting perception monitoring
     */
    public void startMonitoring() {
        if (isMonitoring) {
            Log.d(TAG, "🤖 [SIMULATED] Already monitoring");
            return;
        }
        isMonitoring = true;
        Log.i(TAG, "🤖 [SIMULATED] Started perception monitoring");
        
        if (listener != null) {
            listener.onServiceStatusChanged(true);
        }
    }

    /**
     * Simulates stopping perception monitoring
     */
    public void stopMonitoring() {
        if (!isMonitoring) {
            Log.d(TAG, "🤖 [SIMULATED] Already stopped");
            return;
        }
        isMonitoring = false;
        Log.i(TAG, "🤖 [SIMULATED] Stopped perception monitoring");
        
        if (listener != null) {
            listener.onServiceStatusChanged(false);
        }
    }

    /**
     * Returns empty list (no humans detected in standalone mode)
     */
    public List<PerceptionData.HumanInfo> getCurrentHumans() {
        return new ArrayList<>();
    }

    /**
     * Checks if monitoring is active
     */
    public boolean isMonitoring() {
        return isMonitoring;
    }

    /**
     * Simulates getting the number of detected humans
     */
    public int getHumanCount() {
        return 0; // No humans in standalone mode
    }

    /**
     * Checks if service is initialized
     */
    public boolean isInitialized() {
        return true; // Always initialized in standalone mode
    }

    /**
     * Simulates getting perception data
     */
    public PerceptionData getPerceptionData() {
        return new PerceptionData();
    }

    /**
     * Shuts down the service
     */
    public void shutdown() {
        stopMonitoring();
        Log.i(TAG, "🤖 [SIMULATED] PerceptionService shutdown");
    }
}

