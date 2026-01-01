@file:Suppress("DEPRECATION")

package ch.fhnw.pepper_realtime.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import ch.fhnw.pepper_realtime.network.HttpClientManager
import kotlinx.coroutines.*
import ch.fhnw.pepper_realtime.ui.ChatActivity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Standalone implementation of VisionService using Android device camera.
 * Automatically captures photos with the front camera and sends them to vision API.
 */
class VisionService(context: Context) {

    interface Callback {
        fun onResult(resultJson: String)
        fun onError(errorMessage: String)
        @Suppress("unused") // May be used in future versions
        fun onInfo(message: String)
        fun onPhotoCaptured(path: String)
    }

    interface PictureCallback {
        fun onPictureTaken(bitmap: Bitmap?)
    }

    companion object {
        private const val TAG = "VisionService[Standalone]"
        private const val GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions"
    }

    private val context: Context = context.applicationContext
    private val activityRef: ChatActivity? = if (context is ChatActivity) context else null
    private val http = HttpClientManager.getInstance().getApiClient()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var working = false
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    
    // Dummy surface for preview (required by some devices like Samsung)
    private var dummySurfaceTexture: SurfaceTexture? = null
    private var dummySurface: Surface? = null

    init {
        Log.d(TAG, "VisionService created (using Android camera)")
    }

    /**
     * Initialize (no-op in standalone mode, Android camera is always available)
     */
    fun initialize(@Suppress("UNUSED_PARAMETER") qiContext: Any?) {
        Log.i(TAG, "VisionService initialized (Android camera ready)")

        // Start camera background thread
        cameraThread = HandlerThread("CameraBackground").also {
            it.start()
            cameraHandler = Handler(it.looper)
        }
    }

    /**
     * Pause vision service (stop ongoing analysis)
     */
    fun pause() {
        if (working) {
            Log.i(TAG, "Vision analysis interrupted by pause")
            working = false
        }
    }

    /**
     * Resume vision service
     */
    fun resume() {
        Log.d(TAG, "VisionService resumed")
    }

    /**
     * Check if service is initialized (always true for standalone)
     */
    val isInitialized: Boolean = true

    /**
     * Start vision analysis by automatically capturing with front camera
     */
    fun startAnalyze(prompt: String?, apiKey: String?, callback: Callback) {
        if (working) {
            callback.onError("Vision analysis already in progress")
            return
        }

        if (activityRef == null) {
            callback.onError("Activity reference not available")
            return
        }

        // Check camera permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            callback.onError("Camera permission not granted")
            return
        }

        working = true
        Log.i(TAG, "Starting automatic photo capture with front camera...")

        // Capture photo automatically
        capturePhotoAutomatically(prompt, apiKey, callback)
    }

    /**
     * Automatically capture photo using Camera2 API
     */
    private fun capturePhotoAutomatically(prompt: String?, apiKey: String?, callback: Callback) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            // Find front camera
            var frontCameraId: String? = null
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    frontCameraId = cameraId
                    break
                }
            }

            if (frontCameraId == null) {
                working = false
                callback.onError("Front camera not found")
                return
            }

            // Prepare dummy surface for preview (required for Samsung/some devices to init sensor)
            dummySurfaceTexture = SurfaceTexture(10) // Random texture ID
            dummySurfaceTexture?.setDefaultBufferSize(640, 480)
            dummySurface = Surface(dummySurfaceTexture)

            // Setup ImageReader for capture (2 buffers to prevent starvation)
            imageReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 2).also { reader ->
                reader.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage()
                    if (image != null) {
                        processCapturedImage(image, prompt, apiKey, callback)
                        image.close()
                    }
                }, cameraHandler)
            }

            // Open camera
            cameraManager.openCamera(frontCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    Log.i(TAG, "Front camera opened, capturing photo...")
                    takePictureNow(callback)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    if (working) {
                        working = false
                        callback.onError("Camera disconnected")
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    working = false
                    callback.onError("Camera error: $error")
                }
            }, cameraHandler)

        } catch (e: CameraAccessException) {
            working = false
            Log.e(TAG, "Camera access exception", e)
            callback.onError("Failed to access camera: ${e.message}")
        } catch (e: SecurityException) {
            working = false
            Log.e(TAG, "Camera permission denied", e)
            callback.onError("Camera permission denied")
        }
    }

    /**
     * Capture photo from opened camera
     */
    private fun takePictureNow(callback: Callback) {
        val device = cameraDevice
        val reader = imageReader
        val previewSurface = dummySurface
        
        if (device == null || reader == null || previewSurface == null) {
            working = false
            callback.onError("Camera resources not ready")
            return
        }

        try {
            // 1. Create Capture Session with BOTH surfaces (Reader + Dummy Preview)
            device.createCaptureSession(
                listOf(reader.surface, previewSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            // 2. Start Preview (activates sensor/AE/AF)
                            val previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            previewRequestBuilder.addTarget(previewSurface)
                            // Enable auto-focus/auto-exposure
                            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                            
                            session.setRepeatingRequest(previewRequestBuilder.build(), null, cameraHandler)

                            // 3. Wait for sensor to adjust (warmup) then capture
                            cameraHandler?.postDelayed({
                                try {
                                    Log.i(TAG, "Taking picture after preview warmup...")
                                    val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                    captureBuilder.addTarget(reader.surface)
                                    // Use same AF/AE settings
                                    captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                    captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                                    
                                    // Orientation is handled in bitmap processing, but we can set hint
                                    // captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ...) 

                                    session.capture(captureBuilder.build(), null, cameraHandler)
                                } catch (e: CameraAccessException) {
                                    Log.e(TAG, "Capture failed", e)
                                    cleanupCamera()
                                    working = false
                                    callback.onError("Failed to capture photo")
                                }
                            }, 800) // 800ms delay for sensor warmup/focus
                        } catch (e: Exception) {
                            Log.e(TAG, "Preview/Capture sequence failed", e)
                            cleanupCamera()
                            working = false
                            callback.onError("Failed to capture photo sequence")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        cleanupCamera()
                        working = false
                        callback.onError("Camera configuration failed")
                    }
                },
                cameraHandler
            )

        } catch (e: CameraAccessException) {
            cleanupCamera()
            working = false
            Log.e(TAG, "Failed to create capture session", e)
            callback.onError("Failed to create capture session")
        }
    }

    /**
     * Process captured image from Camera2
     */
    private fun processCapturedImage(image: Image, prompt: String?, apiKey: String?, callback: Callback) {
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

            if (bitmap == null) {
                cleanupCamera()
                working = false
                callback.onError("Failed to decode captured image")
                return
            }

            Log.i(TAG, "Photo captured successfully: ${bitmap.width}x${bitmap.height}")

            // Fix rotation for front camera (front camera is mirrored and rotated)
            bitmap = rotateBitmapForFrontCamera(bitmap)

            // Cleanup camera immediately after capture
            cleanupCamera()

            // Process the bitmap
            processBitmapAndAnalyze(bitmap, prompt, apiKey, callback)

        } catch (e: Exception) {
            cleanupCamera()
            working = false
            Log.e(TAG, "Error processing captured image", e)
            callback.onError("Error processing captured image: ${e.message}")
        }
    }

    /**
     * Rotate bitmap to correct orientation for front camera
     */
    private fun rotateBitmapForFrontCamera(bitmap: Bitmap): Bitmap {
        return try {
            // Get device rotation
            val rotation = activityRef?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0

            // Front camera requires rotation correction
            val rotationDegrees = when (rotation) {
                Surface.ROTATION_0 -> 270   // Portrait: Rotate 270° to correct
                Surface.ROTATION_90 -> 0    // Landscape (right): No rotation needed
                Surface.ROTATION_180 -> 90  // Portrait (upside down): Rotate 90°
                Surface.ROTATION_270 -> 180 // Landscape (left): Rotate 180°
                else -> 0
            }

            if (rotationDegrees == 0) {
                return bitmap // No rotation needed
            }

            Log.d(TAG, "Rotating bitmap by $rotationDegrees° (device rotation: $rotation)")

            val matrix = Matrix().apply {
                postRotate(rotationDegrees.toFloat())
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmap, 0, 0,
                bitmap.width, bitmap.height, matrix, true
            )

            // Recycle original bitmap if different from rotated
            if (rotatedBitmap != bitmap) {
                bitmap.recycle()
            }

            rotatedBitmap

        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate bitmap, using original", e)
            bitmap
        }
    }

    /**
     * Cleanup camera resources
     */
    private fun cleanupCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            
            dummySurface?.release()
            dummySurface = null
            dummySurfaceTexture?.release()
            dummySurfaceTexture = null
        } catch (e: Exception) {
            Log.w(TAG, "Error cleanup camera resources", e)
        }
    }

    /**
     * Process captured bitmap and send to vision API
     */
    private fun processBitmapAndAnalyze(bitmap: Bitmap, prompt: String?, apiKey: String?, callback: Callback) {
        serviceScope.launch {
            try {
                // Optimize bitmap for API upload
                val optimizedBitmap = optimizeBitmapForApi(bitmap)

                // Single compression for both cache file and Base64
                val jpegBytes: ByteArray
                ByteArrayOutputStream().use { baos ->
                    optimizedBitmap.compress(Bitmap.CompressFormat.JPEG, 75, baos)
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

                // Convert to Base64
                val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

                // Decide path: if Realtime API is active (OpenAI or Google), send image directly via WS
                val useRealtimeImagePath = shouldSendImageDirectToRealtime()
                if (useRealtimeImagePath) {
                    try {
                        val activity = activityRef ?: throw IllegalStateException("Activity reference not available")
                        val sm = activity.sessionManager
                        if (!sm.isConnected) throw IllegalStateException("Realtime session not connected")
                        
                        // Use different method based on provider
                        val sentItem = if (isGoogleProvider()) {
                            // Google Live API: use clientContent with inlineData
                            Log.d(TAG, "Sending image to Google Gemini Live API")
                            sm.sendGoogleImageMessage(base64, "image/jpeg")
                        } else {
                            // OpenAI Realtime API: use conversation.item.create with input_image
                            Log.d(TAG, "Sending image to OpenAI Realtime API")
                            sm.sendUserImageMessage(base64, "image/jpeg")
                        }
                        
                        if (!sentItem) throw IllegalStateException("Failed to send image message")
                        working = false
                        callback.onResult(JSONObject().put("status", "sent_to_realtime").toString())
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send image to realtime API, falling back to Groq", e)
                        analyzeWithGroq(base64, prompt, apiKey, callback)
                    }
                } else {
                    analyzeWithGroq(base64, prompt, apiKey, callback)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error processing camera image", e)
                working = false
                callback.onError("Failed processing camera image: ${e.message}")
            }
        }
    }

    /**
     * Decide if we should send the image directly to the Realtime API.
     * Supports both OpenAI Realtime API and Google Gemini Live API.
     */
    private fun shouldSendImageDirectToRealtime(): Boolean {
        return try {
            val activity = activityRef ?: run {
                Log.w(TAG, "Cannot determine model - activityRef is null")
                return false
            }
            val model = activity.getModel()
            Log.d(TAG, "Current model for vision decision: $model")
            // OpenAI Realtime API models
            model == "gpt-realtime" || model == "gpt-realtime-mini" ||
            // Google Gemini Live API models (with or without models/ prefix)
            model.startsWith("models/gemini") || model.contains("gemini")
        } catch (e: Exception) {
            Log.w(TAG, "Error checking model for vision path", e)
            false
        }
    }

    /**
     * Check if the current provider is Google Gemini.
     */
    private fun isGoogleProvider(): Boolean {
        return try {
            val activity = activityRef ?: return false
            val model = activity.getModel()
            model.startsWith("models/gemini") || model.contains("gemini")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Optimize bitmap for API upload
     */
    private fun optimizeBitmapForApi(original: Bitmap): Bitmap {
        val maxDimension = 800
        val width = original.width
        val height = original.height

        if (width <= maxDimension && height <= maxDimension) {
            return original
        }

        val scale = minOf(maxDimension.toFloat() / width, maxDimension.toFloat() / height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        Log.d(TAG, "Optimizing bitmap: ${width}x$height → ${newWidth}x$newHeight (scale: %.2f)".format(scale))

        return Bitmap.createScaledBitmap(original, newWidth, newHeight, true)
    }

    /**
     * Send image to Groq Vision API for analysis
     */
    private fun analyzeWithGroq(base64Jpeg: String, prompt: String?, apiKey: String?, callback: Callback) {
        try {
            val body = JSONObject().apply {
                put("model", "meta-llama/llama-4-scout-17b-16e-instruct")
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", if (!prompt.isNullOrEmpty()) prompt else "What's in this image?")
                            })
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64Jpeg"))
                            })
                        })
                    })
                })
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
                            if (p.optString("type") == "text") sb.append(p.optString("text")).append(" ")
                        }
                        description = sb.toString().trim()
                    }
                } catch (ignored: Exception) {
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
     * Shuts down the service
     */
    fun shutdown() {
        working = false
        cleanupCamera()
        cameraThread?.let { thread ->
            thread.quitSafely()
            try {
                thread.join()
                cameraThread = null
                cameraHandler = null
            } catch (e: InterruptedException) {
                Log.e(TAG, "Camera thread interrupted during shutdown", e)
            }
        }
        Log.i(TAG, "VisionService shutdown")
    }
}

