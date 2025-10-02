package io.github.hrilab.pepper_realtime;

import android.content.Context;
import android.util.Log;

/**
 * Stub implementation of LocalizationCoordinator for standalone mode (no robot hardware).
 * Simulates localization functionality.
 */
public class LocalizationCoordinator {
    private static final String TAG = "LocalizationCoordinator[STUB]";

    public interface LocalizationListener {
        void onLocalizationStatusChanged(boolean isLocalized, String mapName);
        void onLocalizationError(String error);
    }

    public LocalizationCoordinator(Object robotContext, LocalizationListener listener) {
        Log.d(TAG, "🤖 [SIMULATED] LocalizationCoordinator created");
    }

    /**
     * Simulates starting localization
     */
    public Object startLocalization(Object robotContext, String mapName) {
        Log.i(TAG, "🤖 [SIMULATED] Start localization with map: " + mapName);
        return null; // Return null instead of Future<Void>
    }

    /**
     * Simulates stopping localization
     */
    public void stopLocalization() {
        Log.i(TAG, "🤖 [SIMULATED] Stop localization");
    }

    /**
     * Checks if localized (always false in standalone)
     */
    public boolean isLocalized() {
        return false;
    }

    /**
     * Shuts down the coordinator
     */
    public void shutdown() {
        Log.i(TAG, "🤖 [SIMULATED] LocalizationCoordinator shutdown");
    }
}

