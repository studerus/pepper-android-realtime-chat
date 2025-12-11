package ch.fhnw.pepper_realtime.manager

import android.util.Log

/**
 * Stub implementation of LocalizationCoordinator for standalone mode (no robot hardware).
 * Simulates localization functionality.
 */
class LocalizationCoordinator(
    @Suppress("UNUSED_PARAMETER") robotContext: Any?,
    @Suppress("UNUSED_PARAMETER") listener: LocalizationListener?
) {
    interface LocalizationListener {
        fun onLocalizationStatusChanged(isLocalized: Boolean, mapName: String?)
        fun onLocalizationError(error: String?)
    }

    companion object {
        private const val TAG = "LocalizationCoordinator[STUB]"
    }

    init {
        Log.d(TAG, "🤖 [SIMULATED] LocalizationCoordinator created")
    }

    /**
     * Simulates starting localization
     */
    fun startLocalization(@Suppress("UNUSED_PARAMETER") robotContext: Any?, mapName: String?): Any? {
        Log.i(TAG, "🤖 [SIMULATED] Start localization with map: $mapName")
        return null // Return null instead of Future<Void>
    }

    /**
     * Simulates stopping localization
     */
    fun stopLocalization() {
        Log.i(TAG, "🤖 [SIMULATED] Stop localization")
    }

    /**
     * Checks if localized (always false in standalone)
     */
    fun isLocalized(): Boolean = false

    /**
     * Shuts down the coordinator
     */
    fun shutdown() {
        Log.i(TAG, "🤖 [SIMULATED] LocalizationCoordinator shutdown")
    }
}

