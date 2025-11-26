package io.github.anonymous.pepper_realtime.tools.information

import android.util.Log
import io.github.anonymous.pepper_realtime.tools.Tool
import io.github.anonymous.pepper_realtime.tools.ToolContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlin.random.Random

/**
 * Tool for getting random jokes from the local jokes database.
 * Reads jokes from assets/jokes.json file.
 */
class GetRandomJokeTool : Tool {

    override fun getName(): String = "get_random_joke"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Returns a random joke. Use this function whenever the user asks for a joke. Do not tell your own jokes, only jokes from the function.")

            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject()) // empty properties object required by schema
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        // Read jokes database and sanitize problematic quotes before JSON parsing
        val json = readJokesFromAssets(context)
        if (json.isNullOrEmpty()) {
            return JSONObject().put("error", "jokes.json not found").toString()
        }

        val root = JSONObject(json)
        val arr = root.optJSONArray("jokes")
        if (arr == null || arr.length() == 0) {
            return JSONObject().put("error", "No jokes available").toString()
        }

        val joke = arr.getJSONObject(Random.nextInt(arr.length()))

        return JSONObject().apply {
            put("id", joke.optInt("id"))
            put("text", joke.optString("text"))
        }.toString()
    }

    override fun requiresApiKey(): Boolean = false

    override fun getApiKeyType(): String? = null

    /**
     * Read jokes from assets folder
     */
    private fun readJokesFromAssets(context: ToolContext): String? {
        return try {
            context.appContext.assets.open("jokes.json").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
                    buildString {
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            append(line).append('\n')
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "readJokesFromAssets failed: jokes.json", e)
            null
        }
    }

    companion object {
        private const val TAG = "GetRandomJokeTool"
    }
}


