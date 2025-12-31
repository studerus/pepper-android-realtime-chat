package ch.fhnw.pepper_realtime.manager

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import ch.fhnw.pepper_realtime.network.RealtimeSessionManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages audio input capture for Realtime API
 * Captures PCM16 mono audio and streams it via WebSocket
 * 
 * Sample rates:
 * - OpenAI/Azure/x.ai: 24kHz
 * - Google Gemini: 16kHz (for better performance on Pepper tablet)
 */
class RealtimeAudioInputManager(
    private val sessionManager: RealtimeSessionManager
) {
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private val _isCapturing = AtomicBoolean(false)

    /**
     * Set to true when using Google provider (uses 16kHz input - Google's native rate)
     */
    var isGoogleProvider: Boolean = false
    
    private val currentSampleRate: Int
        get() = if (isGoogleProvider) GOOGLE_SAMPLE_RATE else OPENAI_SAMPLE_RATE
    
    private val currentBufferSizeBytes: Int
        get() = if (isGoogleProvider) GOOGLE_BUFFER_SIZE_BYTES else OPENAI_BUFFER_SIZE_BYTES

    val isCapturing: Boolean
        get() = _isCapturing.get()

    /**
     * Initialize AudioRecord with Realtime API format
     */
    private fun initializeAudioRecord(): Boolean {
        try {
            val sampleRate = currentSampleRate
            val bufferSize = currentBufferSizeBytes
            
            // Calculate minimum buffer size
            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )

            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Invalid buffer size from AudioRecord.getMinBufferSize()")
                return false
            }

            // Use larger of our preferred size or minimum required
            val bufferSizeInBytes = maxOf(bufferSize, minBufferSize)

            // Create AudioRecord instance
            // VOICE_COMMUNICATION enables echo cancellation, noise suppression, and AGC
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSizeInBytes
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord = null
                return false
            }

            Log.i(TAG, "AudioRecord initialized: ${sampleRate}Hz (${if (isGoogleProvider) "Google" else "OpenAI"}), buffer=$bufferSizeInBytes bytes")
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
        val buffer = ByteArray(currentBufferSizeBytes)
        var consecutiveErrors = 0

        Log.d(TAG, "Capture loop started (${if (isGoogleProvider) "Google 16kHz" else "OpenAI 24kHz"})")

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

            // Send via session manager (use Google-specific method for 16kHz format)
            val sent = if (isGoogleProvider) {
                sessionManager.sendGoogleAudioChunk(base64Audio)
            } else {
                sessionManager.sendAudioChunk(base64Audio)
            }

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

        // Audio format requirements (same for all providers)
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2 // 16-bit = 2 bytes

        // OpenAI/Azure/x.ai: 24kHz
        private const val OPENAI_SAMPLE_RATE = 24000
        private const val OPENAI_BUFFER_SIZE_SAMPLES = 2400 // ~100ms of audio
        private const val OPENAI_BUFFER_SIZE_BYTES = OPENAI_BUFFER_SIZE_SAMPLES * BYTES_PER_SAMPLE

        // Google Gemini: 16kHz (better performance on Pepper tablet)
        private const val GOOGLE_SAMPLE_RATE = 16000
        private const val GOOGLE_BUFFER_SIZE_SAMPLES = 1600 // ~100ms of audio
        private const val GOOGLE_BUFFER_SIZE_BYTES = GOOGLE_BUFFER_SIZE_SAMPLES * BYTES_PER_SAMPLE

        private const val MAX_CONSECUTIVE_ERRORS = 5
    }
}

