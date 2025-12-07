package io.github.anonymous.pepper_realtime.tools.vision

import android.util.Log
import io.github.anonymous.pepper_realtime.R
import io.github.anonymous.pepper_realtime.service.VisionService
import io.github.anonymous.pepper_realtime.tools.Tool
import io.github.anonymous.pepper_realtime.tools.ToolContext
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Standalone implementation of AnalyzeVisionTool using Android device camera.
 * Uses Groq API or Realtime API to analyze what the camera sees.
 */
@Suppress("SpellCheckingInspection") // "Groq" is the correct API provider name
class AnalyzeVisionTool : Tool {

    companion object {
        private const val TAG = "AnalyzeVisionTool[Standalone]"
    }

    override fun getName(): String = "analyze_vision"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description",
                "Takes a photo with the device camera and analyzes what is visible in the real world. Use this ONLY for real-world vision: when the user asks what you see around you or what's in front of you. Do NOT use this for drawings on the tablet - drawings are automatically sent to your context and you can describe them directly without any function call. Tell the user to wait a moment before you perform this function.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("prompt", JSONObject()
                        .put("type", "string")
                        .put("description",
                            "Optional additional instruction for the vision analysis (e.g. 'how old is the person?' if the user asks you to estimate age)"))
                })
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        val prompt = args.optString("prompt", "")

        Log.i(TAG, "Analyzing vision with Android camera, prompt: ${if (prompt.isEmpty()) "(default analysis)" else prompt}")

        return try {
            // Use VisionService - pass Activity for access to SettingsManager and SessionManager
            val visionService = VisionService(context.activity!!)

            // Initialize with null qiContext (standalone mode)
            visionService.initialize(null)

            if (!visionService.isInitialized) {
                return JSONObject().put("error", "Camera not available on this device.").toString()
            }

            val apiKey = context.apiKeyManager.groqApiKey

            // Update UI status
            context.sendAsyncUpdate("📸 Opening camera to take photo...", false)

            // Use CountDownLatch to handle async vision analysis
            val latch = CountDownLatch(1)
            val result = AtomicReference<String>()

            visionService.startAnalyze(prompt, apiKey, object : VisionService.Callback {
                override fun onResult(resultJson: String) {
                    try {
                        // For Groq path, append context to the description.
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
                    // Add image to chat UI and session cleanup
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
                val finished = latch.await(60, TimeUnit.SECONDS) // 60s timeout
                if (!finished) {
                    return JSONObject().put("error", "Vision analysis timed out or cancelled").toString()
                }

                // Check if this was the GA path (direct image send)
                var finalResult = result.get()
                try {
                    val resultJson = JSONObject(finalResult)
                    if (resultJson.optString("status") == "sent_to_realtime") {
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

}


