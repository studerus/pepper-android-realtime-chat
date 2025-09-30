package io.github.hrilab.pepper_realtime;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

import androidx.annotation.NonNull;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

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
        @SuppressWarnings("unused")
        public String getThumbnailUrl() { return thumbnailUrl; }
        
        @NonNull
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
                String errorBody = (response.body() != null) ? safeBodyString(response) : "Unknown error";
                Log.e(TAG, "YouTube API error: " + response.code() + " - " + errorBody);
                throw new IOException("YouTube API request failed: " + response.code());
            }
            
            String responseBody = (response.body() != null) ? safeBodyString(response) : null;
            Log.d(TAG, "YouTube API response received");
            
            // Parse JSON response
            if (responseBody == null) {
                Log.e(TAG, "Empty response body from YouTube API");
                return null;
            }
            JSONObject jsonResponse = new JSONObject(responseBody);
            JSONArray items = jsonResponse.getJSONArray("items");
            
            if (items.length() == 0) {
                Log.w(TAG, "No videos found for query: " + query);
                return null;
            }
            
            // Extract first video
            JSONObject firstItem = items.optJSONObject(0);
            if (firstItem == null) {
                Log.w(TAG, "Empty first item in YouTube response");
                return null;
            }
            JSONObject idObj = firstItem.optJSONObject("id");
            String videoId = idObj != null ? idObj.optString("videoId", "") : "";
            
            JSONObject snippet = firstItem.getJSONObject("snippet");
            String title = snippet.optString("title", "");
            String channelTitle = snippet.optString("channelTitle", "");
            
            // Get thumbnail URL (null-safe locals to satisfy inspections)
            JSONObject thumbnails = snippet.optJSONObject("thumbnails");
            JSONObject defaultThumb = thumbnails != null ? thumbnails.optJSONObject("default") : null;
            String thumbnailUrl = defaultThumb != null ? defaultThumb.optString("url", "") : "";
            
            YouTubeVideo video = new YouTubeVideo(videoId, title, channelTitle, thumbnailUrl);
            Log.i(TAG, "Found video: " + video);
            
            return video;
            
        } catch (Exception e) {
            Log.e(TAG, "Error searching YouTube", e);
            throw e;
        }
    }

    private static String safeBodyString(Response response) throws IOException {
        ResponseBody body = response.body();
        return body != null ? body.string() : null;
    }
    
    /**
     * Search for first video result
     * @param query Search query
     * @return First video result or null if no results
     */
    public YouTubeVideo searchFirstVideo(String query) throws Exception {
        return searchVideo(query, 1);
    }
    
    
    // Convenience overload removed as unused
}
