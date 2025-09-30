package io.github.hrilab.pepper_realtime.tools.entertainment;

import android.util.Log;
import io.github.hrilab.pepper_realtime.YouTubeSearchService;
import io.github.hrilab.pepper_realtime.tools.Tool;
import io.github.hrilab.pepper_realtime.tools.ToolContext;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Tool for searching and playing YouTube videos.
 * Uses YouTube Data API to find videos and displays them in the UI.
 */
public class PlayYouTubeVideoTool implements Tool {
    
    private static final String TAG = "PlayYouTubeVideoTool";

    @Override
    public String getName() {
        return "play_youtube_video";
    }

    @Override
    public JSONObject getDefinition() {
        try {
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", getName());
            tool.put("description", "Search and play a YouTube video based on user's request. Use this when the user asks to play music, songs, or videos from YouTube.");
            
            JSONObject params = new JSONObject();
            params.put("type", "object");
            
            JSONObject properties = new JSONObject();
            properties.put("query", new JSONObject()
                .put("type", "string")
                .put("description", "Search query for the video (e.g. 'like a virgin madonna', 'funny cat videos')"));
            
            params.put("properties", properties);
            params.put("required", new JSONArray().put("query"));
            tool.put("parameters", params);
            
            return tool;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool definition", e);
        }
    }

    @Override
    public String execute(JSONObject args, ToolContext context) throws Exception {
        String query = args.optString("query", "");
        
        // Validate required parameters
        if (query.isEmpty()) {
            return new JSONObject().put("error", "Missing required parameter: query").toString();
        }
        
        // Check if YouTube API is available
        if (!context.getApiKeyManager().isYouTubeAvailable()) {
            String setupMessage = context.getApiKeyManager().getYouTubeSetupMessage();
            return new JSONObject().put("error", "YouTube API key not configured").put("setup", setupMessage).toString();
        }
        
        Log.i(TAG, "Searching YouTube for: " + query);
        
        try {
            // Create YouTube search service
            YouTubeSearchService youtubeService = new YouTubeSearchService(context.getApiKeyManager().getYouTubeApiKey());
            
            // Search for video
            YouTubeSearchService.YouTubeVideo video = youtubeService.searchFirstVideo(query);
            
            if (video == null) {
                return new JSONObject().put("error", "No videos found for query: " + query).toString();
            }
            
            // Show video player via manager on main thread
            if (context.hasUi()) {
                context.getActivity().runOnUiThread(() -> io.github.hrilab.pepper_realtime.managers.YouTubePlayerManager.showYouTubePlayerDialog(
                        context.getActivity(), video,
                        new io.github.hrilab.pepper_realtime.managers.YouTubePlayerManager.YouTubePlayerCallback() {
                            @Override
                            public void onPlayerOpened() {
                                context.sendAsyncUpdate("🎵 YouTube video started - microphone muted", false);
                            }
                            
                            @Override
                            public void onPlayerClosed() {
                                context.sendAsyncUpdate("🎵 YouTube video ended - microphone active again", false);
                            }
                            
                            @Override
                            public boolean isActivityFinishing() {
                                return context.getActivity().isFinishing();
                            }
                        }
                    ));
            }
            
            // Return success response
            JSONObject result = new JSONObject();
            result.put("status", "Video player opened");
            result.put("query", query);
            result.put("video_title", video.getTitle());
            result.put("channel", video.getChannelTitle());
            result.put("video_id", video.getVideoId());
            return result.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "Error playing YouTube video", e);
            return new JSONObject().put("error", "Failed to search or play video: " + e.getMessage()).toString();
        }
    }

    @Override
    public boolean requiresApiKey() {
        return true;
    }

    @Override
    public String getApiKeyType() {
        return "YouTube";
    }
}
