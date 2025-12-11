package ch.fhnw.pepper_realtime.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.camera.TakePicture
import com.aldebaran.qi.sdk.`object`.image.TimestampedImageHandle
import com.aldebaran.qi.sdk.builder.TakePictureBuilder
import ch.fhnw.pepper_realtime.network.HttpClientManager
import kotlinx.coroutines.*
import ch.fhnw.pepper_realtime.ui.ChatActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Vision service for analyzing images using Pepper's head camera.
 * Uses Groq API or Realtime API to analyze what the camera sees.
 */
@Suppress("SpellCheckingInspection") // API provider names (Groq)
class VisionService(context: Context) {

    interface Callback {
        fun onResult(resultJson: String)
        fun onError(errorMessage: String)
        @Suppress("unused") // May be used in future versions
        fun onInfo(message: String)
        fun onPhotoCaptured(path: String)
    }

    companion object {
        private const val TAG = "VisionService"
        private const val GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions"
    }

    private val context: Context = context.applicationContext
    private val activityRef: ChatActivity? = if (context is ChatActivity) context else null
    private val http: OkHttpClient = HttpClientManager.getInstance().getApiClient()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var qiContext: QiContext? = null
    private var takePictureAction: Future<TakePicture>? = null
    @Volatile private var working = false

    val isInitialized: Boolean
        get() = qiContext != null && takePictureAction != null

    /**
     * Initialize with QiContext for robot camera access
     */
    fun initialize(robotContext: Any?) {
        if (robotContext == null) {
            Log.w(TAG, "VisionService: No robot context available.")
            this.qiContext = null
            this.takePictureAction = null
            return
        }
        val qiContext = robotContext as QiContext
        this.qiContext = qiContext
        // Build the TakePicture action once for reuse
        this.takePictureAction = TakePictureBuilder.with(qiContext).buildAsync()
    }

    fun startAnalyze(prompt: String?, groqApiKey: String?, callback: Callback) {
        if (working) {
            callback.onError("Vision analysis already in progress")
            return
        }
        working = true

        // Groq key is only required when we fall back to Groq analysis (older models)

        if (qiContext == null || takePictureAction == null) {
            working = false
            callback.onError("Robot camera not initialized. Ensure robot has focus.")
            return
        }

        takePictureAndAnalyze(prompt ?: "", groqApiKey ?: "", callback)
    }

    private fun takePictureAndAnalyze(prompt: String, apiKey: String, callback: Callback) {
        val action = takePictureAction
        if (action == null) {
            working = false
            callback.onError("Robot camera not available")
            return
        }

        Log.i(TAG, "Taking picture with robot head camera...")

        // Execute on IO dispatcher to avoid blocking QiSDK MainEventLoop
        serviceScope.launch {
            try {
                action.andThenCompose { takePicture -> takePicture.async().run() }
                    .andThenConsume { timestampedImageHandle ->
                        try {
                            val bitmap = convertToBitmap(timestampedImageHandle)
                            if (bitmap == null) {
                                working = false
                                callback.onError("Failed to convert robot camera image")
                                return@andThenConsume
                            }

                            // Optimize bitmap for API upload (smaller size, lower quality)
                            val optimizedBitmap = optimizeBitmapForApi(bitmap)

                            // Single compression for both cache file and Base64
                            val jpegBytes: ByteArray
                            ByteArrayOutputStream().use { baos ->
                                optimizedBitmap?.compress(Bitmap.CompressFormat.JPEG, 75, baos)
                                jpegBytes = baos.toByteArray()
                            }

                            // Save to cache file (async, non-blocking)
                            serviceScope.launch {
                                try {
                                    val out = File(context.cacheDir, "vision_${System.currentTimeMillis()}.jpg")
                                    FileOutputStream(out).use { fos ->
                                        fos.write(jpegBytes)
                                    }
                                    callback.onPhotoCaptured(out.absolutePath)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to save preview file", e)
                                }
                            }

                            // Convert to Base64 once (reuse for GA direct image or Groq text analysis)
                            val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

                            // Decide path: if GA gpt-realtime is active, send image directly via WS
                            val useGaImagePath = shouldSendImageDirectToRealtime()
                            if (useGaImagePath) {
                                serviceScope.launch {
                                    try {
                                        val activity = activityRef
                                            ?: throw IllegalStateException("Activity reference not available")
                                        val sm = activity.sessionManager
                                        if (!sm.isConnected) throw IllegalStateException("Realtime session not connected")
                                        val sentItem = sm.sendUserImageMessage(base64, "image/jpeg")
                                        if (!sentItem) throw IllegalStateException("Failed to send image message")
                                        working = false
                                        callback.onResult(JSONObject().put("status", "sent_to_realtime").toString())
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to send image to realtime API, falling back to Groq", e)
                                        analyzeWithGroq(base64, prompt, apiKey, callback)
                                    }
                                }
                            } else {
                                // Offload network analysis to coroutine (Groq path)
                                serviceScope.launch {
                                    analyzeWithGroq(base64, prompt, apiKey, callback)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing robot camera image", e)
                            working = false
                            callback.onError("Failed processing robot camera image: ${e.message}")
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Robot camera capture failed", e)
                working = false
                callback.onError("Robot camera capture failed: ${e.message}")
            }
        }
    }

    /**
     * Decide if we should send the image directly to the Realtime API.
     * Only gpt-realtime and gpt-realtime-mini support direct image analysis.
     */
    private fun shouldSendImageDirectToRealtime(): Boolean {
        return try {
            val activity = activityRef ?: run {
                Log.w(TAG, "Cannot determine model - activityRef is null")
                return false
            }
            val model = activity.getModel()
            Log.d(TAG, "Current model for vision decision: $model")
            // Only gpt-realtime and gpt-realtime-mini support direct image analysis
            model == "gpt-realtime" || model == "gpt-realtime-mini"
        } catch (e: Exception) {
            Log.w(TAG, "Error checking model for vision path", e)
            false
        }
    }

    /**
     * Convert TimestampedImageHandle to Bitmap (same logic as PerceptionService)
     */
    private fun convertToBitmap(timestampedImageHandle: TimestampedImageHandle): Bitmap? {
        return try {
            val encodedImageHandle = timestampedImageHandle.image
            val encodedImage = encodedImageHandle.value
            val buffer = encodedImage.data
            buffer.rewind()
            val pictureBufferArray = ByteArray(buffer.remaining())
            buffer.get(pictureBufferArray)
            BitmapFactory.decodeByteArray(pictureBufferArray, 0, pictureBufferArray.size)
        } catch (e: Exception) {
            Log.e(TAG, "Error converting TimestampedImageHandle to Bitmap", e)
            null
        }
    }

    /**
     * Optimize bitmap for API upload by reducing size while maintaining aspect ratio
     */
    private fun optimizeBitmapForApi(original: Bitmap?): Bitmap? {
        if (original == null) return null

        // Target max dimension for API upload (smaller = faster processing)
        val maxDimension = 800
        val width = original.width
        val height = original.height

        // Skip resizing if already small enough
        if (width <= maxDimension && height <= maxDimension) {
            return original
        }

        // Calculate scale factor to maintain aspect ratio
        val scale = min(maxDimension.toFloat() / width, maxDimension.toFloat() / height)
        val newWidth = (width * scale).roundToInt()
        val newHeight = (height * scale).roundToInt()

        Log.d(TAG, "Optimizing bitmap: ${width}x$height → ${newWidth}x$newHeight (scale: %.2f)".format(scale))

        return Bitmap.createScaledBitmap(original, newWidth, newHeight, true)
    }

    private fun analyzeWithGroq(base64Jpeg: String, prompt: String, apiKey: String, callback: Callback) {
        try {
            val body = JSONObject().apply {
                put("model", "meta-llama/llama-4-scout-17b-16e-instruct")
                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", if (prompt.isNotEmpty()) prompt else "What's in this image?")
                            })
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64Jpeg"))
                            })
                        })
                    })
                }
                put("messages", messages)
            }

            val req = Request.Builder()
                .url(GROQ_API_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    callback.onError("Groq request failed: $resp")
                    working = false
                    return
                }

                val responseBody = resp.body
                if (responseBody == null) {
                    callback.onError("Groq request failed: Empty response body")
                    working = false
                    return
                }

                val s = responseBody.string()
                val json = JSONObject(s)
                var description = ""

                try {
                    val msg = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                    description = msg.optString("content", "")
                    if (description.isEmpty() && msg.has("content") && msg.get("content") is JSONArray) {
                        val parts = msg.getJSONArray("content")
                        val sb = StringBuilder()
                        for (i in 0 until parts.length()) {
                            val p = parts.getJSONObject(i)
                            if ("text" == p.optString("type")) {
                                sb.append(p.optString("text")).append(" ")
                            }
                        }
                        description = sb.toString().trim()
                    }
                } catch (ignore: Exception) {
                }

                if (description.isEmpty()) description = "No description returned."

                val result = JSONObject().put("description", description)
                callback.onResult(result.toString())
            }
        } catch (e: Exception) {
            callback.onError("Groq analysis failed: ${e.message}")
        } finally {
            working = false
        }
    }

    /**
     * Pause vision service (stop ongoing analysis)
     * Called when app goes to background
     */
    fun pause() {
        if (working) {
            Log.i(TAG, "Vision analysis interrupted by pause")
            working = false
        }
    }

    /**
     * Resume vision service
     * Called when app comes back from background
     */
    fun resume() {
        // Nothing to do - service is ready when initialized
        Log.d(TAG, "VisionService resumed")
    }
}

