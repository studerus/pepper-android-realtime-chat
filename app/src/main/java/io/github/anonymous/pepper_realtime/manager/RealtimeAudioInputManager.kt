package io.github.anonymous.pepper_realtime.manager

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import io.github.anonymous.pepper_realtime.network.RealtimeSessionManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages audio input capture for Realtime API
 * Captures PCM16 24kHz mono audio and streams it via WebSocket
 */
class RealtimeAudioInputManager(
    private val sessionManager: RealtimeSessionManager
) {
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private val _isCapturing = AtomicBoolean(false)

    val isCapturing: Boolean
        get() = _isCapturing.get()

    /**
     * Initialize AudioRecord with Realtime API format
     */
    private fun initializeAudioRecord(): Boolean {
        try {
            // Calculate minimum buffer size
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )

            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Invalid buffer size from AudioRecord.getMinBufferSize()")
                return false
            }

            // Use larger of our preferred size or minimum required
            val bufferSizeInBytes = maxOf(BUFFER_SIZE_BYTES, minBufferSize)

            // Create AudioRecord instance
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // Optimized for voice with noise suppression
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSizeInBytes
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord = null
                return false
            }

            Log.i(TAG, "AudioRecord initialized: ${SAMPLE_RATE}Hz, buffer=$bufferSizeInBytes bytes")
            return true

        } catch (e: SecurityException) {
            Log.e(TAG, "Missing RECORD_AUDIO permission", e)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioRecord", e)
            return false
        }
    }

    /**
     * Start capturing and streaming audio
     */
    @Synchronized
    fun start(): Boolean {
        if (_isCapturing.get()) {
            Log.w(TAG, "Audio capture already running")
            return true
        }

        if (!sessionManager.isConnected) {
            Log.e(TAG, "Cannot start audio capture - WebSocket not connected")
            return false
        }

        // Initialize AudioRecord
        if (!initializeAudioRecord()) {
            return false
        }

        // Start recording
        try {
            audioRecord?.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            cleanup()
            return false
        }

        // Start capture thread
        _isCapturing.set(true)
        captureThread = Thread({ captureLoop() }, "realtime-audio-capture").apply {
            priority = Thread.NORM_PRIORITY + 1 // Slightly elevated priority
            start()
        }

        Log.i(TAG, "Audio capture started")
        return true
    }

    /**
     * Stop capturing audio
     */
    @Synchronized
    fun stop() {
        if (!_isCapturing.get()) {
            return
        }

        Log.i(TAG, "Stopping audio capture...")
        _isCapturing.set(false)

        // Wait for capture thread to finish
        captureThread?.let { thread ->
            try {
                thread.join(1000) // Wait max 1 second
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        captureThread = null

        cleanup()
        Log.i(TAG, "Audio capture stopped")
    }

    /**
     * Audio capture loop - runs in background thread
     */
    private fun captureLoop() {
        val buffer = ByteArray(BUFFER_SIZE_BYTES)
        var consecutiveErrors = 0

        Log.d(TAG, "Capture loop started")

        while (_isCapturing.get()) {
            try {
                // Read audio data
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1

                when {
                    bytesRead > 0 -> {
                        consecutiveErrors = 0 // Reset error counter on success
                        // Send to Realtime API
                        sendAudioChunk(buffer, bytesRead)
                    }
                    bytesRead == AudioRecord.ERROR_INVALID_OPERATION -> {
                        Log.e(TAG, "AudioRecord not properly initialized")
                        break
                    }
                    bytesRead == AudioRecord.ERROR_BAD_VALUE -> {
                        Log.e(TAG, "Invalid parameters in read operation")
                        break
                    }
                    else -> {
                        consecutiveErrors++
                        Log.w(TAG, "AudioRecord read returned: $bytesRead (error count: $consecutiveErrors)")

                        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                            Log.e(TAG, "Too many consecutive errors, stopping capture")
                            break
                        }

                        // Brief pause before retry
                        try {
                            Thread.sleep(50)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in capture loop", e)
                consecutiveErrors++

                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    break
                }
            }
        }

        Log.d(TAG, "Capture loop ended")
    }

    /**
     * Send audio chunk to Realtime API via WebSocket
     */
    private fun sendAudioChunk(buffer: ByteArray, bytesRead: Int) {
        try {
            // Encode to Base64
            val base64Audio = Base64.encodeToString(buffer, 0, bytesRead, Base64.NO_WRAP)

            // Send via session manager
            val sent = sessionManager.sendAudioChunk(base64Audio)

            if (!sent) {
                Log.w(TAG, "Failed to send audio chunk ($bytesRead bytes)")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error encoding/sending audio chunk", e)
        }
    }

    /**
     * Cleanup resources
     */
    private fun cleanup() {
        audioRecord?.let { record ->
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
                record.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error during AudioRecord cleanup", e)
            }
        }
        audioRecord = null
    }

    companion object {
        private const val TAG = "RealtimeAudioInput"

        // Realtime API audio format requirements
        private const val SAMPLE_RATE = 24000 // 24kHz required by Realtime API
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2 // 16-bit = 2 bytes

        // Buffer size: ~100ms of audio (2400 samples * 2 bytes = 4800 bytes)
        private const val BUFFER_SIZE_SAMPLES = 2400
        private const val BUFFER_SIZE_BYTES = BUFFER_SIZE_SAMPLES * BYTES_PER_SAMPLE

        private const val MAX_CONSECUTIVE_ERRORS = 5
    }
}

