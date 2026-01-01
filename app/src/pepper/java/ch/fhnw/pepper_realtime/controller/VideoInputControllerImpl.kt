package ch.fhnw.pepper_realtime.controller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import ch.fhnw.pepper_realtime.network.RealtimeSessionManager
import ch.fhnw.pepper_realtime.ui.ChatActivity
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.camera.TakePicture
import com.aldebaran.qi.sdk.`object`.image.TimestampedImageHandle
import com.aldebaran.qi.sdk.builder.TakePictureBuilder
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.scopes.ActivityScoped
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pepper implementation of VideoInputController.
 * Uses Pepper's head camera via QiSDK TakePicture API for video streaming to Gemini Live API.
 *
 * Captures frames at 1 FPS and sends them via realtimeInput.video format.
 */
@ActivityScoped
class VideoInputControllerImpl @Inject constructor(
    @ActivityContext private val context: Context,
    private val sessionManager: RealtimeSessionManager
) : VideoInputController {

    companion object {
        private const val TAG = "VideoInputController[Pepper]"
        private const val FRAME_INTERVAL_MS = 1000L // 1 FPS
        private const val TARGET_SIZE = 512 // Max dimension for token efficiency
        private const val JPEG_QUALITY = 70
    }

    private val _isStreaming = MutableStateFlow(false)
    override val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _currentFrame = MutableStateFlow<Bitmap?>(null)
    override val currentFrame: StateFlow<Bitmap?> = _currentFrame.asStateFlow()

    // QiSDK resources
    private var qiContext: QiContext? = null
    private var takePictureAction: TakePicture? = null

    // Streaming loop
    private var streamingJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun startStreaming(): Boolean {
        if (_isStreaming.value) {
            Log.w(TAG, "Video streaming already active")
            return true
        }

        // Get QiContext from ChatActivity
        val activity = context as? ChatActivity
        if (activity == null) {
            Log.e(TAG, "Cannot start streaming - not a ChatActivity context")
            return false
        }

        val robotContext = activity.getQiContext()
        if (robotContext == null) {
            Log.e(TAG, "Cannot start streaming - no robot context available")
            return false
        }

        qiContext = robotContext as QiContext

        // Check WebSocket connection
        if (!sessionManager.isConnected) {
            Log.e(TAG, "WebSocket not connected")
            return false
        }

        Log.i(TAG, "Starting video streaming at 1 FPS using Pepper's head camera")

        // Build TakePicture action
        try {
            takePictureAction = TakePictureBuilder.with(qiContext).build()
            Log.d(TAG, "TakePicture action built successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build TakePicture action", e)
            return false
        }

        _isStreaming.value = true
        startStreamingLoop()
        return true
    }

    private fun startStreamingLoop() {
        streamingJob = serviceScope.launch {
            Log.i(TAG, "Streaming loop started")
            
            while (isActive && _isStreaming.value) {
                try {
                    captureAndSendFrame()
                } catch (e: CancellationException) {
                    Log.d(TAG, "Streaming loop cancelled")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in streaming loop", e)
                }
                
                delay(FRAME_INTERVAL_MS)
            }
            
            Log.i(TAG, "Streaming loop ended")
        }
    }

    private suspend fun captureAndSendFrame() {
        val action = takePictureAction ?: return

        try {
            // Take picture synchronously (we're already on IO dispatcher)
            val timestampedImageHandle = withContext(Dispatchers.IO) {
                action.run()
            }

            // Convert to bitmap
            val bitmap = convertToBitmap(timestampedImageHandle)
            if (bitmap == null) {
                Log.w(TAG, "Failed to convert camera image to bitmap")
                return
            }

            // Optimize for token efficiency
            val optimizedBitmap = optimizeBitmap(bitmap, TARGET_SIZE)

            // Update preview frame
            _currentFrame.value = optimizedBitmap

            // Compress to JPEG
            val jpegBytes: ByteArray
            ByteArrayOutputStream().use { baos ->
                optimizedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
                jpegBytes = baos.toByteArray()
            }

            // Base64 encode
            val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

            // Send to Gemini Live API
            val sent = sessionManager.sendGoogleMediaFrame(base64, "image/jpeg")
            if (sent) {
                Log.d(TAG, "Video frame sent (${jpegBytes.size} bytes)")
            } else {
                Log.w(TAG, "Failed to send video frame")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error capturing frame", e)
        }
    }

    /**
     * Convert TimestampedImageHandle to Bitmap (same logic as VisionService)
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
     * Optimize bitmap by scaling down while maintaining aspect ratio
     */
    private fun optimizeBitmap(original: Bitmap, maxSize: Int): Bitmap {
        val width = original.width
        val height = original.height

        if (width <= maxSize && height <= maxSize) {
            return original
        }

        val scale = min(maxSize.toFloat() / width, maxSize.toFloat() / height)
        val newWidth = (width * scale).roundToInt()
        val newHeight = (height * scale).roundToInt()

        Log.d(TAG, "Optimizing bitmap: ${width}x$height → ${newWidth}x$newHeight")

        return Bitmap.createScaledBitmap(original, newWidth, newHeight, true)
    }

    override fun stopStreaming() {
        if (!_isStreaming.value) return

        Log.i(TAG, "Stopping video streaming")
        _isStreaming.value = false

        // Cancel streaming job
        streamingJob?.cancel()
        streamingJob = null

        // Clear resources
        takePictureAction = null
        qiContext = null

        // Clear preview frame
        _currentFrame.value = null
    }

    override fun toggleStreaming(): Boolean {
        return if (_isStreaming.value) {
            stopStreaming()
            false
        } else {
            startStreaming()
        }
    }

    override fun shutdown() {
        Log.d(TAG, "Shutting down VideoInputController")
        stopStreaming()
        serviceScope.cancel()
    }
}
