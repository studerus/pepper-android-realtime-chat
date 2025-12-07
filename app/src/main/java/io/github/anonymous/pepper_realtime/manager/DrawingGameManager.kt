package io.github.anonymous.pepper_realtime.manager

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import io.github.anonymous.pepper_realtime.ui.DrawingGameState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Drawing game state and logic.
 * Handles inactivity detection and sends drawing to Realtime API context.
 */
@Singleton
class DrawingGameManager @Inject constructor() {

    companion object {
        private const val TAG = "DrawingGameManager"
        private const val INACTIVITY_TIMEOUT_MS = 2000L
        private const val IMAGE_SIZE = 512
    }

    // UI State
    private val _state = MutableStateFlow(DrawingGameState())
    val state: StateFlow<DrawingGameState> = _state.asStateFlow()

    // Current drawing bitmap
    private var currentBitmap: Bitmap? = null

    // Callback for sending images to the API
    private var imageSendCallback: ((base64: String, mime: String) -> Boolean)? = null

    // Coroutine management
    private var inactivityJob: Job? = null
    private var coroutineScope: CoroutineScope? = null

    /**
     * Set the coroutine scope for background operations.
     * Should be called with viewModelScope from the ViewModel.
     */
    fun setCoroutineScope(scope: CoroutineScope) {
        coroutineScope = scope
    }

    /**
     * Set the callback for sending images to the API.
     * @param callback Function that sends base64 image with mime type, returns success
     */
    fun setImageSendCallback(callback: (base64: String, mime: String) -> Boolean) {
        imageSendCallback = callback
    }

    /**
     * Start the drawing game.
     * @param topic Optional topic for the drawing (e.g., "Draw an animal")
     * @return true if game started successfully
     */
    fun startGame(topic: String?): Boolean {
        _state.value = DrawingGameState(
            isVisible = true,
            topic = topic?.takeIf { it.isNotBlank() }
        )
        currentBitmap = null
        Log.i(TAG, "Drawing game started with topic: ${topic ?: "(free drawing)"}")
        return true
    }

    /**
     * Check if the drawing game is currently active.
     */
    fun isGameActive(): Boolean = _state.value.isVisible

    /**
     * Called when the user draws on the canvas.
     * Resets the inactivity timer and stores the current bitmap.
     * @param bitmap The current canvas bitmap
     */
    fun onDrawingChanged(bitmap: Bitmap) {
        currentBitmap = bitmap
        _state.update { it.copy(hasUnsavedChanges = true) }

        // Cancel existing timer and start new one
        inactivityJob?.cancel()
        inactivityJob = coroutineScope?.launch {
            delay(INACTIVITY_TIMEOUT_MS)
            sendDrawingToContext()
        }
    }

    /**
     * Clear the canvas and reset state.
     */
    fun clearCanvas() {
        inactivityJob?.cancel()
        currentBitmap = null
        _state.update { it.copy(hasUnsavedChanges = false) }
        Log.i(TAG, "Canvas cleared")
    }

    /**
     * Dismiss the drawing game dialog and reset state.
     */
    fun dismissGame() {
        inactivityJob?.cancel()
        inactivityJob = null
        currentBitmap = null
        _state.value = DrawingGameState()
        Log.i(TAG, "Drawing game dismissed")
    }

    /**
     * Send the current drawing to the Realtime API context.
     * Only updates context, does not request a response.
     */
    private fun sendDrawingToContext() {
        val bitmap = currentBitmap
        if (bitmap == null) {
            Log.w(TAG, "No bitmap to send")
            return
        }

        val callback = imageSendCallback
        if (callback == null) {
            Log.w(TAG, "Image send callback not set")
            return
        }

        try {
            // Scale to target size
            val scaled = Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, true)

            // Convert to Base64 PNG
            val outputStream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

            // Send to API (context only, no response requested)
            val success = callback(base64, "image/png")

            if (success) {
                _state.update {
                    it.copy(
                        hasUnsavedChanges = false,
                        lastSentTimestamp = System.currentTimeMillis()
                    )
                }
                Log.i(TAG, "Drawing sent to API context (${IMAGE_SIZE}x${IMAGE_SIZE})")
            } else {
                Log.e(TAG, "Failed to send drawing to API")
            }

            // Clean up scaled bitmap if it's different from original
            if (scaled != bitmap) {
                scaled.recycle()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error sending drawing to context", e)
        }
    }
}

