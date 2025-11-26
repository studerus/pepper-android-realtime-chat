package io.github.anonymous.pepper_realtime.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.util.Log
import android.view.View

/**
 * Stub implementation of MapPreviewView for standalone mode (no robot hardware).
 * Simulates map preview functionality.
 */
class MapPreviewView constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "MapPreviewView[STUB]"
    }

    /**
     * Simulates initializing map preview
     */
    fun initialize(@Suppress("UNUSED_PARAMETER") qiContext: Any?) {
        Log.i(TAG, "🤖 [SIMULATED] MapPreviewView initialized")
    }

    /**
     * Simulates updating the map
     */
    fun updateMap(mapName: String?) {
        Log.i(TAG, "🤖 [SIMULATED] Update map: $mapName")
    }

    /**
     * Simulates updating map data
     */
    fun updateData(
        locations: List<*>?,
        @Suppress("UNUSED_PARAMETER") state: Any?,
        @Suppress("UNUSED_PARAMETER") mapBitmap: Bitmap?,
        @Suppress("UNUSED_PARAMETER") representation: Any?
    ) {
        Log.i(TAG, "🤖 [SIMULATED] Update map data with ${locations?.size ?: 0} locations")
    }

    /**
     * Simulates clearing the map
     */
    fun clearMap() {
        Log.i(TAG, "🤖 [SIMULATED] Clear map")
    }
}


