package io.github.studerus.pepper_android_realtime.managers;

import android.content.Context;
import android.util.Log;

import io.github.studerus.pepper_android_realtime.YouTubePlayerDialog;
import io.github.studerus.pepper_android_realtime.YouTubeSearchService;

/**
 * Manager for YouTube Player Dialog UI functionality.
 * Handles the creation and management of YouTube video players.
 */
public class YouTubePlayerManager {
    
    private static final String TAG = "YouTubePlayerManager";
    
    /**
     * Interface for YouTube player callbacks
     */
    public interface YouTubePlayerCallback {
        void onPlayerOpened();
        void onPlayerClosed();
        boolean isActivityFinishing();
    }
    
    /**
     * Show a YouTube player dialog for the specified video
     * 
     * @param context The activity context
     * @param video The YouTube video to play
     * @param callback Callback interface for handling player events
     */
    public static void showYouTubePlayerDialog(Context context, YouTubeSearchService.YouTubeVideo video, 
                                             YouTubePlayerCallback callback) {
        
        if (callback.isActivityFinishing()) { 
            Log.w(TAG, "Not showing YouTube player because activity is finishing."); 
            return; 
        }
        
        if (video == null) {
            Log.e(TAG, "Cannot show YouTube player - video is null");
            return;
        }
        
        Log.i(TAG, "Opening YouTube player for: " + video.getTitle());
        
        YouTubePlayerDialog youtubePlayer = new YouTubePlayerDialog(context, new YouTubePlayerDialog.PlayerEventListener() {
            @Override
            public void onPlayerOpened() {
                Log.i(TAG, "YouTube player opened - notifying callback");
                callback.onPlayerOpened();
            }
            
            @Override
            public void onPlayerClosed() {
                Log.i(TAG, "YouTube player closed - notifying callback");
                callback.onPlayerClosed();
            }
        });
        
        youtubePlayer.playVideo(video);
        
        Log.i(TAG, "YouTube player dialog created for: " + video.getTitle());
    }
}
