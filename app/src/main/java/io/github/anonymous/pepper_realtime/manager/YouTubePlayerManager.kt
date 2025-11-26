package io.github.anonymous.pepper_realtime.manager

import android.content.Context
import android.util.Log
import io.github.anonymous.pepper_realtime.service.YouTubeSearchService
import io.github.anonymous.pepper_realtime.ui.YouTubePlayerDialog

/**
 * Manager for YouTube Player Dialog UI functionality.
 * Handles the creation and management of YouTube video players.
 */
object YouTubePlayerManager {

    private const val TAG = "YouTubePlayerManager"

    /**
     * Interface for YouTube player callbacks
     */
    interface YouTubePlayerCallback {
        fun onPlayerOpened()
        fun onPlayerClosed()
        fun isActivityFinishing(): Boolean
    }

    /**
     * Show a YouTube player dialog for the specified video
     *
     * @param context The activity context
     * @param video The YouTube video to play
     * @param callback Callback interface for handling player events
     */
    fun showYouTubePlayerDialog(
        context: Context,
        video: YouTubeSearchService.YouTubeVideo?,
        callback: YouTubePlayerCallback
    ) {
        if (callback.isActivityFinishing()) {
            Log.w(TAG, "Not showing YouTube player because activity is finishing.")
            return
        }

        if (video == null) {
            Log.e(TAG, "Cannot show YouTube player - video is null")
            return
        }

        Log.i(TAG, "Opening YouTube player for: ${video.title}")

        val youtubePlayer = YouTubePlayerDialog(context, object : YouTubePlayerDialog.PlayerEventListener {
            override fun onPlayerOpened() {
                Log.i(TAG, "YouTube player opened - notifying callback")
                callback.onPlayerOpened()
            }

            override fun onPlayerClosed() {
                Log.i(TAG, "YouTube player closed - notifying callback")
                callback.onPlayerClosed()
            }
        })

        youtubePlayer.playVideo(video)

        Log.i(TAG, "YouTube player dialog created for: ${video.title}")
    }
}


