package ch.fhnw.pepper_realtime.tools.information

import android.util.Log
import ch.fhnw.pepper_realtime.network.HttpClientManager
import ch.fhnw.pepper_realtime.tools.ApiKeyRequirement
import ch.fhnw.pepper_realtime.tools.ApiKeyType
import ch.fhnw.pepper_realtime.tools.Tool
import ch.fhnw.pepper_realtime.tools.ToolContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool for searching the internet using Tavily API.
 * Provides real-time information that the AI doesn't have in its training data.
 */
class SearchInternetTool : Tool {

    override fun getName(): String = "search_internet"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Searches the internet for facts, information, or people that you cannot answer from memory. Always respond to the user before you perform the function. Always first tell the user to wait a moment and then immediately perform the function.")

            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("query", JSONObject()
                        .put("type", "string")
                        .put("description", "The search query"))
                })
                put("required", JSONArray().put("query"))
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        val query = args.optString("query", "")
        if (query.isEmpty()) {
            return JSONObject().put("error", "Missing required parameter: query").toString()
        }

        if (!context.apiKeyManager.isInternetSearchAvailable()) {
            return JSONObject().put("error", context.apiKeyManager.searchSetupMessage).toString()
        }

        val apiKey = context.apiKeyManager.tavilyApiKey

        // Use optimized shared client for better performance and connection reuse
        val client = HttpClientManager.getInstance().getQuickApiClient()

        val payload = JSONObject()
        payload.put("api_key", apiKey)
        payload.put("query", query)
        payload.put("search_depth", "basic")
        payload.put("include_answer", true)
        payload.put("max_results", 3)

        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.tavily.com/search")
            .post(body)
            .addHeader("Content-Type", "application/json")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return JSONObject().put("error", "Internet search failed: HTTP ${response.code}").toString()
                }

                val responseBody = response.body
                    ?: return JSONObject().put("error", "Internet search failed: Empty response body").toString()

                val resp = responseBody.string()
                val data = JSONObject(resp)
                var result = data.optString("answer", "")

                if (result.isEmpty()) {
                    val results = data.optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        val first = results.getJSONObject(0)
                        result = first.optString("content", "")
                    }
                }

                if (result.isEmpty()) {
                    result = "Sorry, I couldn't find any information about that."
                }

                JSONObject().put("answer", result).toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in internet search", e)
            JSONObject().put("error", e.message ?: "Failed to search internet").toString()
        }
    }

    override val apiKeyRequirement = ApiKeyRequirement.Required(ApiKeyType.TAVILY)

    companion object {
        private const val TAG = "SearchInternetTool"
    }
}


