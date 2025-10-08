package io.github.anonymous.pepper_realtime;

import android.util.Log;

/**
 * Stub implementation of NavigationMapCache for standalone mode (no robot hardware).
 * Simulates map caching functionality.
 */
public class NavigationMapCache {
    private static final String TAG = "NavigationMapCache[STUB]";

    public NavigationMapCache(Object robotContext) {
        Log.d(TAG, "🤖 [SIMULATED] NavigationMapCache created");
    }

    /**
     * Simulates loading a map
     */
    public Object loadMap(Object robotContext, String mapName) {
        Log.i(TAG, "🤖 [SIMULATED] Load map: " + mapName);
        return null; // Return null instead of Future<Boolean>
    }

    /**
     * Checks if map is cached (always false in standalone)
     */
    public boolean isMapCached(String mapName) {
        return false;
    }

    /**
     * Shuts down the cache
     */
    public void shutdown() {
        Log.i(TAG, "🤖 [SIMULATED] NavigationMapCache shutdown");
    }
}

