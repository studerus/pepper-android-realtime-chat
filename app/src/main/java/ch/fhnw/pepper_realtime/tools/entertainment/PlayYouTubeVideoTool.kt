package ch.fhnw.pepper_realtime.tools.entertainment

import android.util.Log
import ch.fhnw.pepper_realtime.manager.YouTubePlayerManager
import ch.fhnw.pepper_realtime.service.YouTubeSearchService
import ch.fhnw.pepper_realtime.tools.ApiKeyRequirement
import ch.fhnw.pepper_realtime.tools.ApiKeyType
import ch.fhnw.pepper_realtime.tools.Tool
import ch.fhnw.pepper_realtime.tools.ToolContext
import org.json.JSONArray
import org.json.JSONObject

import kotlinx.coroutines.runBlocking

/**
 * Tool for searching and playing YouTube videos.
 * Uses YouTube Data API to find videos and displays them in the UI.
 */
class PlayYouTubeVideoTool : Tool {

    override fun getName(): String = "play_youtube_video"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Search and play a YouTube video based on user's request. Use this when the user asks to play music, songs, or videos from YouTube.")

            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("query", JSONObject()
                        .put("type", "string")
                        .put("description", "Search query for the video (e.g. 'like a virgin madonna', 'funny cat videos')"))
                })
                put("required", JSONArray().put("query"))
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        val query = args.optString("query", "")

        // Validate required parameters
        if (query.isEmpty()) {
            return JSONObject().put("error", "Missing required parameter: query").toString()
        }

        // Check if YouTube API is available
        if (!context.apiKeyManager.isYouTubeAvailable()) {
            val setupMessage = context.apiKeyManager.youTubeSetupMessage
            val result = JSONObject()
            result.put("error", "YouTube API key not configured")
            result.put("setup", setupMessage as String?)
            return result.toString()
        }

        Log.i("PlayYouTubeVideoTool", "Searching YouTube for: $query")

        return try {
            // Create YouTube search service
            val youtubeService = YouTubeSearchService(context.apiKeyManager.youTubeApiKey)

            // Search for video using runBlocking since execute is synchronous
            val video = runBlocking {
                youtubeService.searchFirstVideo(query)
            } ?: return JSONObject().put("error", "No videos found for query: $query").toString()

            // Show video player via manager on main thread
            if (context.hasUi()) {
                val activity = context.activity
                activity?.runOnUiThread {
                    YouTubePlayerManager.showYouTubePlayerDialog(
                        activity,
                        video,
                        object : YouTubePlayerManager.YouTubePlayerCallback {
                            override fun onPlayerOpened() {
                                // Mute microphone while YouTube video is playing
                                context.toolHost.muteMicrophone()
                                context.sendAsyncUpdate("🎵 YouTube video started - microphone muted", false)
                            }

                            override fun onPlayerClosed() {
                                // Unmute microphone when video ends
                                context.toolHost.unmuteMicrophone()
                                context.sendAsyncUpdate("🎵 YouTube video ended - microphone active again", false)
                                // Refresh chat UI to show any updates that were missed while overlay was active
                                context.toolHost.refreshChatMessages()
                            }

                            override fun isActivityFinishing(): Boolean {
                                return activity.isFinishing
                            }
                        }
                    )
                }
            }

            // Return success response
            JSONObject().apply {
                put("status", "Video player opened")
                put("query", query)
                put("video_title", video.title)
                put("channel", video.channelTitle)
                put("video_id", video.videoId)
            }.toString()

        } catch (e: Exception) {
            Log.e("PlayYouTubeVideoTool", "Error playing YouTube video", e)
            JSONObject().put("error", "Failed to search or play video: ${e.message}").toString()
        }
    }

    override val apiKeyRequirement = ApiKeyRequirement.Required(ApiKeyType.YOUTUBE)


}


