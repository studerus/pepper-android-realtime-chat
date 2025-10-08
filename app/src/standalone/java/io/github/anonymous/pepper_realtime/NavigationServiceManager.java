package io.github.anonymous.pepper_realtime;

import android.content.Context;
import android.util.Log;

/**
 * Stub implementation of NavigationServiceManager for standalone mode (no robot hardware).
 * Simulates navigation and mapping functionality.
 */
public class NavigationServiceManager {
    private static final String TAG = "NavigationServiceManager[STUB]";

    private final Context context;
    private final MovementController movementController;

    public NavigationServiceManager(MovementController movementController) {
        this.movementController = movementController;
        this.context = null; // No context needed in standalone mode
        Log.d(TAG, "🤖 [SIMULATED] NavigationServiceManager created");
    }

    /**
     * Simulates initializing navigation service
     */
    public void initialize(Object qiContext) {
        Log.i(TAG, "🤖 [SIMULATED] NavigationServiceManager initialized");
    }

    /**
     * Simulates setting dependencies
     */
    public void setDependencies(PerceptionService perceptionService, TouchSensorManager touchSensorManager, GestureController gestureController) {
        Log.i(TAG, "🤖 [SIMULATED] Dependencies set");
    }

    /**
     * Simulates ensuring localization
     */
    public Object ensureLocalizationIfNeeded(Object robotContext, Runnable onLocalized, Runnable onFailed) {
        Log.i(TAG, "🤖 [SIMULATED] Ensure localization");
        if (onLocalized != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(onLocalized, 300);
        }
        return null; // Return null instead of Future<Boolean>
    }

    /**
     * Checks if localized (always false in standalone)
     */
    public boolean isLocalized() {
        return false;
    }

    /**
     * Checks if map is saved on disk
     */
    public boolean isMapSavedOnDisk(Context appContext) {
        return false; // No maps in standalone mode
    }

    /**
     * Checks if map is loaded
     */
    public boolean isMapLoaded() {
        return false;
    }

    /**
     * Checks if localization is ready
     */
    public boolean isLocalizationReady() {
        return false;
    }

    /**
     * Gets map bitmap (returns null in standalone)
     */
    public android.graphics.Bitmap getMapBitmap() {
        return null;
    }

    /**
     * Gets map top graphical representation (returns null in standalone)
     */
    public Object getMapTopGraphicalRepresentation() {
        return null;
    }

    /**
     * Checks if gestures are suppressed
     */
    public boolean areGesturesSuppressed() {
        return false;
    }

    /**
     * Handles service state change
     */
    public void handleServiceStateChange(String mode) {
        Log.i(TAG, "🤖 [SIMULATED] Handle service state change: " + mode);
    }

    /**
     * Moves Pepper (delegates to MovementController)
     */
    public void movePepper(Object qiContext, double distanceForward, double distanceSideways, double speed) {
        Log.i(TAG, String.format("🤖 [SIMULATED] Move Pepper: forward=%.2f, sideways=%.2f, speed=%.2f", 
            distanceForward, distanceSideways, speed));
    }

    /**
     * Shuts down the manager
     */
    public void shutdown() {
        Log.i(TAG, "🤖 [SIMULATED] NavigationServiceManager shutdown");
    }
}

