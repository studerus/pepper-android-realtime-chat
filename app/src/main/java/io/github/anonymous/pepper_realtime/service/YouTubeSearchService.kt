package io.github.anonymous.pepper_realtime.service

import android.util.Log
import io.github.anonymous.pepper_realtime.di.IoDispatcher
import io.github.anonymous.pepper_realtime.network.HttpClientManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject

/**
 * Service for searching YouTube videos via the YouTube Data API v3.
 */
class YouTubeSearchService(
    private val apiKey: String
) {
    // Secondary constructor for DI (if needed in future)
    @Inject
    constructor(
        apiKey: String,
        @Suppress("UNUSED_PARAMETER") httpClientManager: HttpClientManager,
        @Suppress("UNUSED_PARAMETER") @IoDispatcher ioDispatcher: CoroutineDispatcher
    ) : this(apiKey)
    companion object {
        private const val TAG = "YouTubeSearchService"
        private const val YOUTUBE_API_BASE_URL = "https://www.googleapis.com/youtube/v3/search"
    }

    /**
     * Represents a YouTube video search result.
     */
    data class YouTubeVideo(
        val videoId: String,
        val title: String,
        val channelTitle: String,
        val thumbnailUrl: String
    ) {
        override fun toString(): String {
            return "YouTubeVideo{videoId='$videoId', title='$title', channelTitle='$channelTitle'}"
        }
    }

    private val httpClient by lazy { HttpClientManager.getInstance().getQuickApiClient() }

    /**
     * Search for YouTube videos based on query.
     * This is a suspend function - preferred for new Kotlin code.
     *
     * @param query Search query (e.g. "like a virgin madonna")
     * @param maxResults Maximum number of results (default: 1 for first result)
     * @return First video result or null if no results
     */
    suspend fun searchVideo(query: String, maxResults: Int = 1): YouTubeVideo? = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "Search query cannot be empty" }
        require(apiKey.isNotEmpty()) { "YouTube API key is required" }

        Log.i(TAG, "Searching YouTube for: $query")

        // Build API URL
        val url = "$YOUTUBE_API_BASE_URL" +
                "?part=snippet" +
                "&type=video" +
                "&maxResults=$maxResults" +
                "&q=${URLEncoder.encode(query, "UTF-8")}" +
                "&key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    Log.e(TAG, "YouTube API error: ${response.code} - $errorBody")
                    throw IOException("YouTube API request failed: ${response.code}")
                }

                val responseBody = response.body?.string()
                Log.d(TAG, "YouTube API response received")

                if (responseBody == null) {
                    Log.e(TAG, "Empty response body from YouTube API")
                    return@withContext null
                }

                val jsonResponse = JSONObject(responseBody)
                val items = jsonResponse.getJSONArray("items")

                if (items.length() == 0) {
                    Log.w(TAG, "No videos found for query: $query")
                    return@withContext null
                }

                // Extract first video
                val firstItem = items.optJSONObject(0)
                if (firstItem == null) {
                    Log.w(TAG, "Empty first item in YouTube response")
                    return@withContext null
                }

                val idObj = firstItem.optJSONObject("id")
                val videoId = idObj?.optString("videoId", "") ?: ""

                val snippet = firstItem.getJSONObject("snippet")
                val title = snippet.optString("title", "")
                val channelTitle = snippet.optString("channelTitle", "")

                // Get thumbnail URL (null-safe)
                val thumbnails = snippet.optJSONObject("thumbnails")
                val defaultThumb = thumbnails?.optJSONObject("default")
                val thumbnailUrl = defaultThumb?.optString("url", "") ?: ""

                val video = YouTubeVideo(videoId, title, channelTitle, thumbnailUrl)
                Log.i(TAG, "Found video: $video")

                video
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching YouTube", e)
            throw e
        }
    }

    /**
     * Search for first video result.
     *
     * @param query Search query
     * @return First video result or null if no results
     */
    suspend fun searchFirstVideo(query: String): YouTubeVideo? = searchVideo(query, 1)
}


