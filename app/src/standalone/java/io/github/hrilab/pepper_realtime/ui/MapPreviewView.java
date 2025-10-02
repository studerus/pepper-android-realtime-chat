package io.github.hrilab.pepper_realtime.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

/**
 * Stub implementation of MapPreviewView for standalone mode (no robot hardware).
 * Simulates map preview functionality.
 */
public class MapPreviewView extends View {
    private static final String TAG = "MapPreviewView[STUB]";

    public MapPreviewView(Context context) {
        super(context);
    }

    public MapPreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MapPreviewView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /**
     * Simulates initializing map preview
     */
    public void initialize(Object qiContext) {
        Log.i(TAG, "🤖 [SIMULATED] MapPreviewView initialized");
    }

    /**
     * Simulates updating the map
     */
    public void updateMap(String mapName) {
        Log.i(TAG, "🤖 [SIMULATED] Update map: " + mapName);
    }

    /**
     * Simulates updating map data
     */
    public void updateData(java.util.List<?> locations, Object state, android.graphics.Bitmap mapBitmap, Object representation) {
        Log.i(TAG, "🤖 [SIMULATED] Update map data with " + (locations != null ? locations.size() : 0) + " locations");
    }

    /**
     * Simulates clearing the map
     */
    public void clearMap() {
        Log.i(TAG, "🤖 [SIMULATED] Clear map");
    }
}

