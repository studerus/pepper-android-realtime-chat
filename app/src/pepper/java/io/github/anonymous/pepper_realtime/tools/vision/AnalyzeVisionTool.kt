package io.github.anonymous.pepper_realtime.tools.vision

import android.util.Log
import com.aldebaran.qi.sdk.QiContext
import io.github.anonymous.pepper_realtime.R
import io.github.anonymous.pepper_realtime.service.VisionService
import io.github.anonymous.pepper_realtime.tools.Tool
import io.github.anonymous.pepper_realtime.tools.ToolContext
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Tool for analyzing robot's camera image using AI vision.
 * Uses Groq API to analyze what the robot is currently seeing.
 */
@Suppress("SpellCheckingInspection") // "Groq" is the correct API provider name
class AnalyzeVisionTool : Tool {

    companion object {
        private const val TAG = "AnalyzeVisionTool"
    }

    override fun getName(): String = "analyze_vision"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description",
                "Analyzes the current camera image of the robot and describes what the robot is seeing. Use this function if the user asks what you are seeing or how the user looks. Tell the user to wait a moment before you perform the function.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("prompt", JSONObject()
                        .put("type", "string")
                        .put("description",
                            "Optional additional instruction for the vision analysis (e.g. 'how old is the person?' if the user asks you to estimate his age)"))
                })
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        val prompt = args.optString("prompt", "")

        // Vision analysis now works with gpt-realtime (built-in) or Groq (optional)
        // No longer require API key check as it's handled by VisionService internally

        Log.i(TAG, "Analyzing vision with prompt: ${if (prompt.isEmpty()) "(default analysis)" else prompt}")

        return try {
            // Use existing VisionService - pass Activity for access to SettingsManager and SessionManager
            val visionService = VisionService(context.activity!!)

            // Initialize with QiContext for robot camera access
            visionService.initialize(context.qiContext as QiContext)

            if (!visionService.isInitialized) {
                return JSONObject().put("error", "Robot camera not available. Ensure robot has focus.").toString()
            }

            val apiKey = context.apiKeyManager.groqApiKey

            // Gestures now only move arms, not head - no need to stop them for vision

            // Update UI status by sending async update
            context.sendAsyncUpdate("📸 Taking photo with robot camera...", false)

            // Use CountDownLatch to handle async vision analysis
            val latch = CountDownLatch(1)
            val result = AtomicReference<String>()

            visionService.startAnalyze(prompt, apiKey, object : VisionService.Callback {
                override fun onResult(resultJson: String) {
                    try {
                        // For Groq path, append context to the description.
                        // The gpt-realtime path is handled after latch.await().
                        val obj = JSONObject(resultJson)
                        if (obj.has("description")) {
                            val desc = obj.optString("description", "")
                            val contextSuffix = context.appContext.getString(R.string.vision_context_suffix)
                            if (desc.isNotEmpty()) {
                                obj.put("description", desc + contextSuffix)
                            }
                            result.set(obj.toString())
                        } else {
                            result.set(resultJson)
                        }
                        latch.countDown()
                    } catch (e: Exception) {
                        // Not a JSON or doesn't have "description", likely the gpt-realtime status.
                        result.set(resultJson)
                        latch.countDown()
                    }
                }

                override fun onError(errorMessage: String) {
                    try {
                        result.set(JSONObject().put("error", errorMessage).toString())
                        latch.countDown()
                    } catch (e: Exception) {
                        result.set("{\"error\":\"Error processing vision result\"}")
                        latch.countDown()
                    }
                }

                override fun onInfo(message: String) {
                    // Send info messages as async updates
                    context.sendAsyncUpdate(message, false)
                }

                override fun onPhotoCaptured(path: String) {
                    // Add image to chat UI and session cleanup (unchanged)
                    if (context.hasUi()) {
                        context.toolHost.addImageMessage(path)
                        context.toolHost.getSessionImageManager().addImage(path)
                    }
                    // Inform about photo capture via async update
                    context.sendAsyncUpdate("📷 Photo captured.", false)
                }
            })

            // Wait for vision analysis to complete (blocking call)
            try {
                val finished = latch.await(30, TimeUnit.SECONDS)
                if (!finished) {
                    return JSONObject().put("error", "Vision analysis timed out").toString()
                }

                // Check if this was the GA path (direct image send). If so, formulate a
                // descriptive result.
                var finalResult = result.get()
                try {
                    val resultJson = JSONObject(finalResult)
                    if ("sent_to_realtime" == resultJson.optString("status")) {
                        val contextMessage = context.appContext.getString(R.string.vision_context_suffix)
                        finalResult = JSONObject()
                            .put("description", "A photo has been sent for you to analyze.$contextMessage")
                            .toString()
                    }
                } catch (e: Exception) {
                    // Not a JSON or doesn't have the status field, proceed with original result
                }

                finalResult
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                JSONObject().put("error", "Vision analysis interrupted").toString()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during vision analysis", e)
            JSONObject().put("error", "Vision analysis failed: ${e.message}").toString()
        }
    }

    override fun requiresApiKey(): Boolean = false // Vision now works with gpt-realtime built-in, Groq is optional

    override fun getApiKeyType(): String? = null // No specific API key required
}


