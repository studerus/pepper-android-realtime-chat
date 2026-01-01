package ch.fhnw.pepper_realtime.controller

import android.graphics.Bitmap
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for continuous video streaming to the Gemini Live API.
 *
 * Captures frames at 1 FPS and sends them via realtimeInput.video format.
 * Only active when Google provider is selected and user enables video streaming.
 *
 * Implementations:
 * - Standalone: Uses Android Camera2 API for front camera capture
 * - Pepper: Uses Pepper's camera (to be implemented)
 */
interface VideoInputController {

    /** Whether video streaming is currently active */
    val isStreaming: StateFlow<Boolean>

    /** Current preview frame for UI display */
    val currentFrame: StateFlow<Bitmap?>

    /**
     * Start continuous video streaming at 1 FPS.
     * @return true if streaming started successfully
     */
    fun startStreaming(): Boolean

    /**
     * Stop video streaming and release camera resources.
     */
    fun stopStreaming()

    /**
     * Toggle video streaming on/off.
     * @return new streaming state
     */
    fun toggleStreaming(): Boolean

    /**
     * Release all resources. Call in Activity onDestroy.
     */
    fun shutdown()
}

