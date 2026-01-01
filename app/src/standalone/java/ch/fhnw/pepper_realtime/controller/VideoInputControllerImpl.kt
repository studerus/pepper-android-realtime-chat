package ch.fhnw.pepper_realtime.controller

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import ch.fhnw.pepper_realtime.network.RealtimeSessionManager
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.scopes.ActivityScoped
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Standalone implementation of VideoInputController using Android Camera2 API.
 * Captures frames from the front camera at 1 FPS and sends them to Gemini Live API.
 *
 * Only active when Google provider is selected and user enables video streaming.
 */
@ActivityScoped
class VideoInputControllerImpl @Inject constructor(
    @ActivityContext private val context: Context,
    private val sessionManager: RealtimeSessionManager
) : VideoInputController {

    companion object {
        private const val TAG = "VideoInputController"
        private const val FRAME_INTERVAL_MS = 1000L // 1 FPS
        private const val TARGET_SIZE = 512 // Max dimension for token efficiency
        private const val JPEG_QUALITY = 70
    }

    // Public state
    private val _isStreaming = MutableStateFlow(false)
    override val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _currentFrame = MutableStateFlow<Bitmap?>(null)
    override val currentFrame: StateFlow<Bitmap?> = _currentFrame.asStateFlow()

    // Camera resources
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var dummySurfaceTexture: SurfaceTexture? = null
    private var dummySurface: Surface? = null

    // Frame timing
    private var frameRunnable: Runnable? = null
    private var lastFrameTime = 0L

    /**
     * Start continuous video streaming at 1 FPS.
     * @return true if streaming started successfully
     */
    override fun startStreaming(): Boolean {
        if (_isStreaming.value) {
            Log.w(TAG, "Video streaming already active")
            return true
        }

        // Check camera permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Camera permission not granted")
            return false
        }

        // Check WebSocket connection
        if (!sessionManager.isConnected) {
            Log.e(TAG, "WebSocket not connected")
            return false
        }

        Log.i(TAG, "Starting video streaming at 1 FPS")

        // Start camera background thread
        cameraThread = HandlerThread("VideoStreamThread").also {
            it.start()
            cameraHandler = Handler(it.looper)
        }

        return openCamera()
    }

    /**
     * Stop video streaming and release camera resources.
     */
    override fun stopStreaming() {
        if (!_isStreaming.value) return

        Log.i(TAG, "Stopping video streaming")
        _isStreaming.value = false

        // Cancel frame capture loop
        frameRunnable?.let { cameraHandler?.removeCallbacks(it) }
        frameRunnable = null

        // Release camera
        cleanupCamera()

        // Stop thread
        cameraThread?.quitSafely()
        try {
            cameraThread?.join()
        } catch (e: InterruptedException) {
            Log.w(TAG, "Thread join interrupted", e)
        }
        cameraThread = null
        cameraHandler = null

        // Clear preview frame
        _currentFrame.value = null
    }

    /**
     * Toggle video streaming on/off.
     * @return new streaming state
     */
    override fun toggleStreaming(): Boolean {
        return if (_isStreaming.value) {
            stopStreaming()
            false
        } else {
            startStreaming()
        }
    }

    private fun openCamera(): Boolean {
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
                Log.e(TAG, "No front camera found")
                return false
            }

            // Setup dummy surface for preview (required by some devices)
            dummySurfaceTexture = SurfaceTexture(10)
            dummySurfaceTexture?.setDefaultBufferSize(640, 480)
            dummySurface = Surface(dummySurfaceTexture)

            // Setup ImageReader for capture
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2).also { reader ->
                reader.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage()
                    if (image != null) {
                        processAndSendFrame(image)
                        image.close()
                    }
                }, cameraHandler)
            }

            // Open camera
            cameraManager.openCamera(frontCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    Log.i(TAG, "Front camera opened for streaming")
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    _isStreaming.value = false
                    Log.w(TAG, "Camera disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    _isStreaming.value = false
                    Log.e(TAG, "Camera error: $error")
                }
            }, cameraHandler)

            return true

        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access exception", e)
            return false
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied", e)
            return false
        }
    }

    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        val reader = imageReader ?: return
        val previewSurface = dummySurface ?: return

        try {
            device.createCaptureSession(
                listOf(reader.surface, previewSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        _isStreaming.value = true
                        Log.i(TAG, "Capture session configured, starting frame loop")
                        startFrameLoop()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                        cleanupCamera()
                    }
                },
                cameraHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to create capture session", e)
        }
    }

    private fun startFrameLoop() {
        frameRunnable = object : Runnable {
            override fun run() {
                if (!_isStreaming.value) return

                captureFrame()

                // Schedule next frame
                cameraHandler?.postDelayed(this, FRAME_INTERVAL_MS)
            }
        }

        // Start the loop
        cameraHandler?.post(frameRunnable!!)
    }

    private fun captureFrame() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return
        val previewSurface = dummySurface ?: return

        try {
            // Start preview to activate sensor
            val previewBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewBuilder.addTarget(previewSurface)
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            previewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            session.setRepeatingRequest(previewBuilder.build(), null, cameraHandler)

            // Capture still image
            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(reader.surface)
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            session.capture(captureBuilder.build(), null, cameraHandler)

        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to capture frame", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun processAndSendFrame(image: android.media.Image) {
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            // Decode to bitmap
            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return

            // Rotate for front camera
            bitmap = rotateBitmapForFrontCamera(bitmap)

            // Scale down for token efficiency
            bitmap = scaleBitmap(bitmap, TARGET_SIZE)

            // Update preview frame
            _currentFrame.value = bitmap

            // Compress to JPEG
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
            val jpegBytes = baos.toByteArray()

            // Base64 encode
            val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

            // Send to Gemini Live API
            val sent = sessionManager.sendGoogleMediaFrame(base64, "image/jpeg")
            if (sent) {
                lastFrameTime = System.currentTimeMillis()
                Log.d(TAG, "Video frame sent (${jpegBytes.size} bytes)")
            } else {
                Log.w(TAG, "Failed to send video frame")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun rotateBitmapForFrontCamera(bitmap: Bitmap): Bitmap {
        return try {
            // Get device rotation to adjust camera orientation
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val displayRotation = windowManager.defaultDisplay.rotation

            // Front camera sensor is typically mounted at 270° relative to device natural orientation
            // We need to compensate for both sensor orientation and device rotation
            val rotationDegrees = when (displayRotation) {
                Surface.ROTATION_0 -> 270    // Portrait
                Surface.ROTATION_90 -> 0     // Landscape (right)
                Surface.ROTATION_180 -> 90   // Portrait (upside down)
                Surface.ROTATION_270 -> 180  // Landscape (left)
                else -> 270
            }

            Log.d(TAG, "Display rotation: $displayRotation, applying rotation: $rotationDegrees°")

            val matrix = Matrix().apply {
                postRotate(rotationDegrees.toFloat())
                postScale(-1f, 1f) // Mirror horizontally for front camera
            }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate bitmap", e)
            bitmap
        }
    }

    private fun scaleBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxSize && height <= maxSize) {
            return bitmap
        }

        val scale = minOf(maxSize.toFloat() / width, maxSize.toFloat() / height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

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
            Log.w(TAG, "Error cleaning up camera", e)
        }
    }

    /**
     * Release all resources. Call in Activity onDestroy.
     */
    override fun shutdown() {
        stopStreaming()
    }
}

