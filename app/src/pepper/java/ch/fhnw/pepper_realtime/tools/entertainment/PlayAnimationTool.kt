package ch.fhnw.pepper_realtime.tools.entertainment

import android.util.Log
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.AnimateBuilder
import com.aldebaran.qi.sdk.builder.AnimationBuilder
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.tools.Tool
import ch.fhnw.pepper_realtime.tools.ToolContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool for playing preinstalled Pepper animations.
 * Supports various emotional and gestural animations.
 * 
 * IMPORTANT: This tool stops the GestureController before playing the animation
 * to avoid resource conflicts. The GestureController will automatically restart
 * when the TurnManager enters SPEAKING state again.
 */
class PlayAnimationTool : Tool {

    companion object {
        private const val TAG = "PlayAnimationTool"
    }

    override fun getName(): String = "play_animation"

    // Don't request another response if the model already announced the animation
    override val skipResponseIfAnnounced: Boolean = true

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Play a preinstalled Pepper animation. Use hello_01 when the user wants you to wave or say hello.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("name", JSONObject()
                        .put("type", "string")
                        .put("description", "Animation identifier.")
                        .put("enum", JSONArray()
                            .put("applause_01")
                            .put("bowshort_01")
                            .put("funny_01")
                            .put("happy_01")
                            .put("hello_01")
                            .put("hey_02")
                            .put("kisses_01")
                            .put("laugh_01")
                            .put("showfloor_01")
                            .put("showsky_01")
                            .put("showtablet_02")
                            .put("wings_01")))
                })
                put("required", JSONArray().put("name"))
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        val name = args.optString("name", "")
        if (name.isEmpty()) {
            return JSONObject().put("error", "Missing required parameter: name").toString()
        }

        val resId = mapAnimationNameToResId(name)
        if (resId == null) {
            return JSONObject().put("error", "Unsupported animation name: $name").toString()
        }

        if (context.isQiContextNotReady()) {
            return JSONObject().put("error", "QiContext not ready").toString()
        }

        // Stop gesture controller to free animation resources before starting
        Log.d(TAG, "Stopping GestureController before playing animation: $name")
        context.gestureController.stopNow()

        try {
            val qiContext = context.qiContext as QiContext
            
            // Start animation asynchronously (non-blocking)
            AnimationBuilder.with(qiContext)
                .withResources(resId)
                .buildAsync()
                .andThenCompose { animation ->
                    AnimateBuilder.with(qiContext)
                        .withAnimation(animation)
                        .build()
                        .async()
                        .run()
                }
                .thenConsume { future ->
                    if (future.isSuccess) {
                        Log.i(TAG, "Animation '$name' completed successfully")
                    } else if (future.hasError()) {
                        Log.e(TAG, "Animation '$name' failed", future.error)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting animation '$name'", e)
            return JSONObject()
                .put("status", "failed")
                .put("name", name)
                .put("error", e.message ?: "Unknown error")
                .toString()
        }

        return JSONObject()
            .put("status", "started")
            .put("name", name)
            .toString()
    }


    /**
     * Map animation name to Android resource ID
     */
    @Suppress("SpellCheckingInspection") // Animation names like bowshort, showfloor, etc.
    private fun mapAnimationNameToResId(name: String): Int? {
        return when (name) {
            "applause_01" -> R.raw.applause_01
            "bowshort_01" -> R.raw.bowshort_01
            "funny_01" -> R.raw.funny_01
            "happy_01" -> R.raw.happy_01
            "hello_01" -> R.raw.hello_01
            "hey_02" -> R.raw.hey_02
            "kisses_01" -> R.raw.kisses_01
            "laugh_01" -> R.raw.laugh_01
            "showfloor_01" -> R.raw.showfloor_01
            "showsky_01" -> R.raw.showsky_01
            "showtablet_02" -> R.raw.showtablet_02
            "wings_01" -> R.raw.wings_01
            else -> null
        }
    }
}


