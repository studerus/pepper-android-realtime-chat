package io.github.anonymous.pepper_realtime.tools.entertainment

import android.util.Log
import io.github.anonymous.pepper_realtime.tools.BaseTool
import io.github.anonymous.pepper_realtime.tools.ToolContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stub implementation of PlayAnimationTool for standalone mode.
 */
class PlayAnimationTool : BaseTool() {

    companion object {
        private const val TAG = "PlayAnimationTool[STUB]"
    }

    override fun getName(): String = "play_animation"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Play an animation on Pepper robot.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("animation_name", JSONObject()
                        .put("type", "string")
                        .put("description", "Name of animation to play")
                        .put("enum", JSONArray()
                            .put("wave")
                            .put("bow")
                            .put("shrug")
                            .put("think")
                            .put("celebrate")))
                })
                put("required", JSONArray().put("animation_name"))
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        val animationName = args.optString("animation_name", "")
        Log.i(TAG, "🤖 [SIMULATED] Play animation: $animationName")
        return JSONObject()
            .put("success", true)
            .put("message", "Would play animation: $animationName")
            .toString()
    }

    override fun requiresApiKey(): Boolean = false

    override fun getApiKeyType(): String? = null
}

