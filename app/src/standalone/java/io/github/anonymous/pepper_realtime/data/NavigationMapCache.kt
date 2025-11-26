package io.github.anonymous.pepper_realtime.data

import android.util.Log

/**
 * Stub implementation of NavigationMapCache for standalone mode (no robot hardware).
 * Simulates map caching functionality.
 */
class NavigationMapCache(@Suppress("UNUSED_PARAMETER") robotContext: Any?) {
    
    companion object {
        private const val TAG = "NavigationMapCache[STUB]"
    }

    init {
        Log.d(TAG, "🤖 [SIMULATED] NavigationMapCache created")
    }

    /**
     * Simulates loading a map
     */
    fun loadMap(@Suppress("UNUSED_PARAMETER") robotContext: Any?, mapName: String): Any? {
        Log.i(TAG, "🤖 [SIMULATED] Load map: $mapName")
        return null // Return null instead of Future<Boolean>
    }

    /**
     * Checks if map is cached (always false in standalone)
     */
    fun isMapCached(@Suppress("UNUSED_PARAMETER") mapName: String): Boolean = false

    /**
     * Shuts down the cache
     */
    fun shutdown() {
        Log.i(TAG, "🤖 [SIMULATED] NavigationMapCache shutdown")
    }
}

