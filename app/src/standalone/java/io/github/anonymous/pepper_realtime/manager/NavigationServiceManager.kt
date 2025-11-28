package io.github.anonymous.pepper_realtime.manager

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.anonymous.pepper_realtime.controller.GestureController
import io.github.anonymous.pepper_realtime.controller.MovementController
import io.github.anonymous.pepper_realtime.service.PerceptionService

/**
 * Stub implementation of NavigationServiceManager for standalone mode (no robot hardware).
 * Simulates navigation and mapping functionality.
 */
class NavigationServiceManager(@Suppress("UNUSED_PARAMETER") movementController: MovementController?) {

    companion object {
        private const val TAG = "NavigationServiceManager[STUB]"
    }

    init {
        Log.d(TAG, "🤖 [SIMULATED] NavigationServiceManager created")
    }

    /**
     * Simulates initializing navigation service
     */
    fun initialize(@Suppress("UNUSED_PARAMETER") qiContext: Any?) {
        Log.i(TAG, "🤖 [SIMULATED] NavigationServiceManager initialized")
    }

    /**
     * Simulates setting dependencies
     */
    fun setDependencies(
        @Suppress("UNUSED_PARAMETER") perceptionService: PerceptionService?,
        @Suppress("UNUSED_PARAMETER") touchSensorManager: TouchSensorManager?,
        @Suppress("UNUSED_PARAMETER") gestureController: GestureController?
    ) {
        Log.i(TAG, "🤖 [SIMULATED] Dependencies set")
    }

    /**
     * Simulates ensuring localization
     */
    fun ensureLocalizationIfNeeded(
        @Suppress("UNUSED_PARAMETER") robotContext: Any?,
        onLocalized: Runnable?,
        @Suppress("UNUSED_PARAMETER") onFailed: Runnable?
    ): Any? {
        Log.i(TAG, "🤖 [SIMULATED] Ensure localization")
        onLocalized?.let {
            Handler(Looper.getMainLooper()).postDelayed(it, 300)
        }
        return null // Return null instead of Future<Boolean>
    }

    /**
     * Checks if localized (always false in standalone)
     */
    fun isLocalized(): Boolean = false

    /**
     * Checks if map is saved on disk
     */
    fun isMapSavedOnDisk(@Suppress("UNUSED_PARAMETER") appContext: Context): Boolean = false

    /**
     * Checks if map is loaded
     */
    fun isMapLoaded(): Boolean = false

    /**
     * Checks if localization is ready
     */
    fun isLocalizationReady(): Boolean = false

    /**
     * Gets map bitmap (returns null in standalone)
     */
    fun getMapBitmap(): Bitmap? = null

    /**
     * Gets map top graphical representation (returns null in standalone)
     */
    fun getMapTopGraphicalRepresentation(): Any? = null

    /**
     * Gets platform-independent map graph info (returns null in standalone)
     */
    fun getMapGraphInfo(): io.github.anonymous.pepper_realtime.data.MapGraphInfo? = null

    /**
     * Checks if gestures are suppressed
     */
    fun areGesturesSuppressed(): Boolean = false

    /**
     * Handles service state change
     */
    fun handleServiceStateChange(mode: String) {
        Log.i(TAG, "🤖 [SIMULATED] Handle service state change: $mode")
    }

    /**
     * Moves Pepper (delegates to MovementController)
     */
    fun movePepper(
        @Suppress("UNUSED_PARAMETER") qiContext: Any?,
        distanceForward: Double,
        distanceSideways: Double,
        speed: Double
    ) {
        Log.i(TAG, "🤖 [SIMULATED] Move Pepper: forward=%.2f, sideways=%.2f, speed=%.2f".format(
            distanceForward, distanceSideways, speed))
    }

    /**
     * Shuts down the manager
     */
    fun shutdown() {
        Log.i(TAG, "🤖 [SIMULATED] NavigationServiceManager shutdown")
    }
}

