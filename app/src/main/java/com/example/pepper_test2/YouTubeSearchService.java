package com.example.pepper_test2;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class YouTubeSearchService {
    private static final String TAG = "YouTubeSearchService";
    private static final String YOUTUBE_API_BASE_URL = "https://www.googleapis.com/youtube/v3/search";
    
    private final String apiKey;
    private final OkHttpClient httpClient;
    
    public static class YouTubeVideo {
        private final String videoId;
        private final String title;
        private final String channelTitle;
        private final String thumbnailUrl;
        
        public YouTubeVideo(String videoId, String title, String channelTitle, String thumbnailUrl) {
            this.videoId = videoId;
            this.title = title;
            this.channelTitle = channelTitle;
            this.thumbnailUrl = thumbnailUrl;
        }
        
        public String getVideoId() { return videoId; }
        public String getTitle() { return title; }
        public String getChannelTitle() { return channelTitle; }
        public String getThumbnailUrl() { return thumbnailUrl; }
        
        @Override
        public String toString() {
            return "YouTubeVideo{" +
                    "videoId='" + videoId + '\'' +
                    ", title='" + title + '\'' +
                    ", channelTitle='" + channelTitle + '\'' +
                    '}';
        }
    }
    
    public YouTubeSearchService(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = OptimizedHttpClientManager.getInstance().getQuickApiClient();
    }
    
    /**
     * Search for YouTube videos based on query
     * @param query Search query (e.g. "like a virgin madonna")
     * @param maxResults Maximum number of results (default: 1 for first result)
     * @return First video result or null if no results
     */
    public YouTubeVideo searchVideo(String query, int maxResults) throws Exception {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("Search query cannot be empty");
        }
        
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("YouTube API key is required");
        }
        
        Log.i(TAG, "Searching YouTube for: " + query);
        
        // Build API URL
        String url = YOUTUBE_API_BASE_URL + 
                "?part=snippet" +
                "&type=video" +
                "&maxResults=" + maxResults +
                "&q=" + java.net.URLEncoder.encode(query, "UTF-8") +
                "&key=" + apiKey;
        
        // Make API request
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                Log.e(TAG, "YouTube API error: " + response.code() + " - " + errorBody);
                throw new IOException("YouTube API request failed: " + response.code());
            }
            
            String responseBody = response.body().string();
            Log.d(TAG, "YouTube API response received");
            
            // Parse JSON response
            JSONObject jsonResponse = new JSONObject(responseBody);
            JSONArray items = jsonResponse.getJSONArray("items");
            
            if (items.length() == 0) {
                Log.w(TAG, "No videos found for query: " + query);
                return null;
            }
            
            // Extract first video
            JSONObject firstItem = items.getJSONObject(0);
            String videoId = firstItem.getJSONObject("id").getString("videoId");
            
            JSONObject snippet = firstItem.getJSONObject("snippet");
            String title = snippet.getString("title");
            String channelTitle = snippet.getString("channelTitle");
            
            // Get thumbnail URL
            String thumbnailUrl = "";
            if (snippet.has("thumbnails") && snippet.getJSONObject("thumbnails").has("default")) {
                thumbnailUrl = snippet.getJSONObject("thumbnails").getJSONObject("default").getString("url");
            }
            
            YouTubeVideo video = new YouTubeVideo(videoId, title, channelTitle, thumbnailUrl);
            Log.i(TAG, "Found video: " + video);
            
            return video;
            
        } catch (Exception e) {
            Log.e(TAG, "Error searching YouTube", e);
            throw e;
        }
    }
    
    /**
     * Search for first video result
     * @param query Search query
     * @return First video result or null if no results
     */
    public YouTubeVideo searchFirstVideo(String query) throws Exception {
        return searchVideo(query, 1);
    }
    
    /**
     * Generate YouTube embed URL for a video
     * @param videoId YouTube video ID
     * @param autoplay Whether to autoplay the video (default: true)
     * @return Embed URL for WebView
     */
    public static String getEmbedUrl(String videoId, boolean autoplay) {
        String autoplayParam = autoplay ? "1" : "0";
        return "https://www.youtube.com/embed/" + videoId + "?autoplay=" + autoplayParam + "&rel=0&showinfo=0";
    }
    
    /**
     * Generate YouTube embed URL with autoplay enabled
     * @param videoId YouTube video ID
     * @return Embed URL for WebView
     */
    public static String getEmbedUrl(String videoId) {
        return getEmbedUrl(videoId, true);
    }
}
