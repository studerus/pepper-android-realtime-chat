package ch.fhnw.pepper_realtime.manager

import android.util.Log
import java.io.File

/**
 * Manages temporary session images.
 * Tracks image paths and handles cleanup when session ends.
 */
class SessionImageManager {

    private val sessionImagePaths = mutableListOf<String>()

    /**
     * Add an image path to track for this session
     */
    @Synchronized
    fun addImage(imagePath: String?) {
        if (imagePath != null) {
            sessionImagePaths.add(imagePath)
            Log.d(TAG, "Added session image: $imagePath (total: ${sessionImagePaths.size})")
        }
    }

    /**
     * Delete all tracked session images
     */
    @Synchronized
    fun deleteAllImages() {
        var deletedCount = 0
        var failedCount = 0

        for (path in sessionImagePaths) {
            try {
                val deleted = File(path).delete()
                if (deleted) {
                    deletedCount++
                } else {
                    failedCount++
                    Log.w(TAG, "Failed to delete session image: $path")
                }
            } catch (e: Exception) {
                failedCount++
                Log.w(TAG, "Error deleting session image: $path", e)
            }
        }

        sessionImagePaths.clear()
        Log.i(TAG, "Session image cleanup complete - deleted: $deletedCount, failed: $failedCount")
    }

    /**
     * Get the number of tracked images
     */
    @Synchronized
    fun getImageCount(): Int = sessionImagePaths.size

    companion object {
        private const val TAG = "SessionImageManager"
    }
}

