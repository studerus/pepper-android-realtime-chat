package io.github.anonymous.pepper_realtime.manager;

import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages temporary session images.
 * Tracks image paths and handles cleanup when session ends.
 */
public class SessionImageManager {
    private static final String TAG = "SessionImageManager";

    private final List<String> sessionImagePaths = new ArrayList<>();

    /**
     * Add an image path to track for this session
     */
    public synchronized void addImage(String imagePath) {
        if (imagePath != null) {
            sessionImagePaths.add(imagePath);
            Log.d(TAG, "Added session image: " + imagePath + " (total: " + sessionImagePaths.size() + ")");
        }
    }

    /**
     * Delete all tracked session images
     */
    public synchronized void deleteAllImages() {
        int deletedCount = 0;
        int failedCount = 0;

        for (String path : sessionImagePaths) {
            try {
                if (path != null) {
                    boolean deleted = new File(path).delete();
                    if (deleted) {
                        deletedCount++;
                    } else {
                        failedCount++;
                        Log.w(TAG, "Failed to delete session image: " + path);
                    }
                }
            } catch (Exception e) {
                failedCount++;
                Log.w(TAG, "Error deleting session image: " + path, e);
            }
        }

        sessionImagePaths.clear();
        Log.i(TAG, "Session image cleanup complete - deleted: " + deletedCount + ", failed: " + failedCount);
    }

    /**
     * Get the number of tracked images
     */
    public synchronized int getImageCount() {
        return sessionImagePaths.size();
    }
}
