package io.github.anonymous.pepper_realtime.manager

import android.util.Log
import com.microsoft.cognitiveservices.speech.Connection
import com.microsoft.cognitiveservices.speech.OutputFormat
import com.microsoft.cognitiveservices.speech.PropertyId
import com.microsoft.cognitiveservices.speech.ResultReason
import com.microsoft.cognitiveservices.speech.SpeechConfig
import com.microsoft.cognitiveservices.speech.SpeechRecognitionResult
import com.microsoft.cognitiveservices.speech.SpeechRecognizer
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

@Suppress("SpellCheckingInspection")
class SpeechRecognizerManager {

    // Activity-focused callback interface
    interface ActivityCallbacks {
        fun onRecognizedText(text: String)
        fun onPartialText(partialText: String)
        fun onError(errorMessage: String?)
        fun onStarted()
        fun onStopped()
        fun onReady() // Called when recognizer is fully warmed up and ready to use
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var recognizer: SpeechRecognizer? = null
    private var activityCallbacks: ActivityCallbacks? = null
    private val recognizerLock = Any()

    @Volatile
    private var isRunning = false

    @Volatile
    private var connectionEstablished = false

    @Volatile
    private var sessionActive = false

    @Volatile
    private var readySignaled = false

    // Configuration for current session
    private var currentApiKey: String = ""
    private var currentRegion: String = ""
    private var currentLanguage: String = ""
    private var currentSilenceTimeout: Int = 0
    private var confidenceThreshold: Double = 0.7 // Default 70%

    fun setCallbacks(callbacks: ActivityCallbacks?) {
        this.activityCallbacks = callbacks
    }

    fun configure(key: String, region: String, language: String, silenceTimeoutMs: Int, confidenceThreshold: Double) {
        // Store configuration for potential re-initialization
        this.currentApiKey = key
        this.currentRegion = region
        this.currentLanguage = language
        this.currentSilenceTimeout = silenceTimeoutMs
        this.confidenceThreshold = confidenceThreshold

        initializeRecognizer()
    }

    /**
     * Initialize recognizer - simple and clean
     */
    private fun initializeRecognizer() {
        Log.i(TAG, "🔧 Initializing recognizer...")
        executor.submit {
            try {
                val cfg = SpeechConfig.fromSubscription(currentApiKey, currentRegion)
                cfg.speechRecognitionLanguage = currentLanguage
                cfg.setProperty("Speech_SegmentationSilenceTimeoutMs", currentSilenceTimeout.toString())

                // Enable detailed results for confidence scores
                cfg.requestWordLevelTimestamps()
                cfg.outputFormat = OutputFormat.Detailed

                recognizer?.let { rec ->
                    try { rec.stopContinuousRecognitionAsync().get() } catch (ignored: Exception) {}
                    try { rec.close() } catch (ignored: Exception) {}
                }
                recognizer = SpeechRecognizer(cfg)

                // Give Azure Speech SDK time to fully initialize internally
                Thread.sleep(500)

                // Register connection event listener - this tells us when truly ready
                val connection = Connection.fromRecognizer(recognizer)
                connection.connected.addEventListener { _, _ ->
                    Log.i(TAG, "🌐 Connection established - recognizer ready")
                    connectionEstablished = true
                    signalReadyIfNeeded("connection")
                }
                connection.disconnected.addEventListener { _, _ ->
                    Log.w(TAG, "⚠️ Connection lost")
                    connectionEstablished = false
                    sessionActive = false
                    readySignaled = false
                }

                recognizer?.sessionStarted?.addEventListener { _, _ ->
                    Log.i(TAG, "🎙️ sessionStarted event received")
                    sessionActive = true
                    signalReadyIfNeeded("sessionStarted")
                }
                recognizer?.sessionStopped?.addEventListener { _, _ ->
                    Log.i(TAG, "🛑 sessionStopped event received")
                    sessionActive = false
                    isRunning = false // Reset running state so it can be restarted
                    readySignaled = false
                }

                // Register recognition event listeners
                recognizer?.recognizing?.addEventListener { _, e ->
                    activityCallbacks?.let { callbacks ->
                        try {
                            callbacks.onPartialText(e.result.text)
                        } catch (ignored: Exception) {}
                    }
                }
                recognizer?.recognized?.addEventListener { _, e ->
                    try {
                        if (e.result.reason == ResultReason.RecognizedSpeech) {
                            val text = e.result.text
                            if (!text.isNullOrEmpty()) {
                                val confidence = extractConfidenceScore(e.result)
                                val finalText = addConfidenceWarningIfNeeded(text, confidence)
                                activityCallbacks?.onRecognizedText(finalText)
                            }
                        }
                    } catch (ex: Exception) {
                        activityCallbacks?.onError(ex.message)
                    }
                }

                try {
                    connection.openConnection(true)
                    Log.i(TAG, "Proactively opened recognizer connection after initialization")
                } catch (openEx: Exception) {
                    Log.w(TAG, "Unable to proactively open recognizer connection after init", openEx)
                }

                Log.i(TAG, "✅ Recognizer initialized for $currentLanguage, silence=${currentSilenceTimeout}ms")

                // Notify that recognizer is ready - warmup will happen on first use
                activityCallbacks?.onReady()

            } catch (ex: Exception) {
                Log.e(TAG, "❌ Initialize failed", ex)
                activityCallbacks?.onError("Initialization failed: ${ex.message}")
            }
        }
    }

    fun start() {
        if (isRunning) {
            Log.i(TAG, "STT already running, skipping duplicate start call")
            return
        }

        if (executor.isShutdown) {
            Log.w(TAG, "Cannot start STT - executor already shutdown")
            return
        }

        executor.submit {
            try {
                recognizer?.let { rec ->
                    isRunning = true // Set BEFORE starting, so connection.connected listener can fire onStarted()
                    connectionEstablished = false // Reset flag
                    sessionActive = false
                    readySignaled = false
                    rec.startContinuousRecognitionAsync().get()
                    Log.i(TAG, "Continuous recognition started (waiting for connection established event)")

                    // Fallback timer: If connection event doesn't fire within 10 seconds, call onStarted() anyway
                    val fallbackThread = Thread {
                        try {
                            Thread.sleep(10000) // Wait 10 seconds
                            if (isRunning && !readySignaled) {
                                Log.w(TAG, "⚠️ Connection still pending after 10s - awaiting Azure events")
                            }
                        } catch (ignored: InterruptedException) {
                            // Thread was interrupted because connection was established
                        }
                    }
                    fallbackThread.isDaemon = true
                    fallbackThread.start()
                    // NOTE: onStarted() will be called by the connection.connected event listener
                    // when the connection to Azure Speech is truly established
                } ?: handleStartFailure(IllegalStateException("Recognizer not initialized"))
            } catch (ex: Exception) {
                handleStartFailure(ex)
            }
        }
    }

    fun stop() {
        if (executor.isShutdown) {
            Log.w(TAG, "Executor already shutdown, stopping STT directly (non-blocking)")
            try {
                recognizer?.let { rec ->
                    rec.stopContinuousRecognitionAsync() // fire-and-forget
                    isRunning = false
                    activityCallbacks?.onStopped()
                    Log.i(TAG, "Continuous recognition stop requested")
                }
            } catch (ex: Exception) {
                isRunning = false // Reset flag even on error
                Log.e(TAG, "Error stopping speech recognizer (ignored)", ex)
            }
            return
        }

        executor.submit {
            try {
                recognizer?.let { rec ->
                    rec.stopContinuousRecognitionAsync() // do not block on get()
                    isRunning = false
                    sessionActive = false
                    connectionEstablished = false
                    readySignaled = false
                    activityCallbacks?.onStopped()
                    Log.i(TAG, "Continuous recognition stop requested (async)")
                }
            } catch (ex: Exception) {
                isRunning = false // Reset flag even on error
                sessionActive = false
                connectionEstablished = false
                readySignaled = false
                Log.e(TAG, "Error stopping speech recognizer (ignored)", ex)
            }
        }
    }

    private fun handleStartFailure(ex: Exception) {
        if (isFirstLaunchSpeechError(ex)) {
            Log.i(TAG, "Detected first-launch speech error, attempting lazy re-initialization")
            try {
                // Synchronous re-initialization (without warmup, as this is a fallback)
                val cfg = SpeechConfig.fromSubscription(currentApiKey, currentRegion)
                cfg.speechRecognitionLanguage = currentLanguage
                cfg.setProperty("Speech_SegmentationSilenceTimeoutMs", currentSilenceTimeout.toString())
                cfg.requestWordLevelTimestamps()
                cfg.outputFormat = OutputFormat.Detailed

                recognizer?.let { rec ->
                    try { rec.stopContinuousRecognitionAsync().get() } catch (ignored: Exception) {}
                    try { rec.close() } catch (ignored: Exception) {}
                }
                recognizer = SpeechRecognizer(cfg)
                Thread.sleep(1000)

                // CRITICAL: Register event listeners for the new recognizer instance
                val connection = Connection.fromRecognizer(recognizer)
                connection.connected.addEventListener { _, _ ->
                    Log.i(TAG, "🌐 Recognizer connection established (lazy init)")
                    connectionEstablished = true
                    signalReadyIfNeeded("lazy-connection")
                }
                connection.disconnected.addEventListener { _, _ ->
                    Log.w(TAG, "⚠️ Recognizer connection lost (lazy init)")
                    connectionEstablished = false
                    sessionActive = false
                    readySignaled = false
                }

                recognizer?.sessionStarted?.addEventListener { _, _ ->
                    Log.i(TAG, "🎙️ sessionStarted (lazy init)")
                    sessionActive = true
                    signalReadyIfNeeded("lazy-session")
                }
                recognizer?.sessionStopped?.addEventListener { _, _ ->
                    Log.i(TAG, "🛑 sessionStopped (lazy init)")
                    sessionActive = false
                    readySignaled = false
                }

                recognizer?.recognizing?.addEventListener { _, e ->
                    activityCallbacks?.let { callbacks ->
                        try {
                            callbacks.onPartialText(e.result.text)
                        } catch (ignored: Exception) {}
                    }
                }
                recognizer?.recognized?.addEventListener { _, e ->
                    try {
                        if (e.result.reason == ResultReason.RecognizedSpeech) {
                            val text = e.result.text
                            if (!text.isNullOrEmpty()) {
                                val confidence = extractConfidenceScore(e.result)
                                val finalText = addConfidenceWarningIfNeeded(text, confidence)
                                activityCallbacks?.onRecognizedText(finalText)
                            }
                        }
                    } catch (ex2: Exception) {
                        activityCallbacks?.onError(ex2.message)
                    }
                }

                recognizer?.let { rec ->
                    isRunning = true
                    connectionEstablished = false
                    sessionActive = false
                    readySignaled = false
                    rec.startContinuousRecognitionAsync().get()
                    Log.i(TAG, "Lazy speech initialization successful (waiting for connection/session)")
                    val fallbackThread = Thread {
                        try {
                            Thread.sleep(10000)
                            if (isRunning && !readySignaled) {
                                Log.w(TAG, "⚠️ Lazy init: connection still pending after 10s - awaiting Azure events")
                            }
                        } catch (ignored: InterruptedException) {}
                    }
                    fallbackThread.isDaemon = true
                    fallbackThread.start()
                    // onStarted() will be called by session/connection event
                    return
                }
            } catch (retryEx: Exception) {
                Log.e(TAG, "Retry initialization failed", retryEx)
            }
        }

        activityCallbacks?.onError("Speech recognition failed to start: ${ex.message}")
    }

    private fun signalReadyIfNeeded(source: String) {
        if (!isRunning || readySignaled) {
            return
        }
        if (!connectionEstablished || !sessionActive) {
            Log.i(TAG, "⏳ Waiting for full readiness ($source) connection=$connectionEstablished, sessionActive=$sessionActive")
            return
        }

        activityCallbacks?.let { callbacks ->
            readySignaled = true
            Log.i(TAG, "🟢 Recognizer ready ($source)")
            callbacks.onStarted()
        }
    }

    /**
     * Add confidence warning to text if below threshold
     */
    private fun addConfidenceWarningIfNeeded(text: String, confidence: Double): String {
        return if (confidence < confidenceThreshold) {
            "$text [Low confidence: ${(confidence * 100).roundToInt()}%]"
        } else {
            text
        }
    }

    /**
     * Check if error indicates first-launch speech initialization failure
     */
    private fun isFirstLaunchSpeechError(ex: Exception): Boolean {
        val msg = ex.message ?: return false
        return msg.contains("0x22") ||
                msg.contains("SPXERR_INVALID_RECOGNIZER") ||
                msg.contains("invalid recognizer") ||
                msg.contains("not initialized")
    }

    fun shutdown() {
        try {
            recognizer?.let { rec ->
                try { rec.stopContinuousRecognitionAsync() } catch (ignored: Exception) {}
                try { rec.close() } catch (ignored: Exception) {}
            }
            recognizer = null
        } catch (ignored: Exception) {}
        executor.shutdown()
    }

    /**
     * Extract confidence score from Azure Speech recognition result
     * @param result The SpeechRecognitionResult
     * @return Confidence score (0.0-1.0), or 1.0 if extraction fails
     */
    private fun extractConfidenceScore(result: SpeechRecognitionResult): Double {
        try {
            // Try to get detailed results JSON via official PropertyId
            val detailedJson = result.properties.getProperty(PropertyId.SpeechServiceResponse_JsonResult)
            if (!detailedJson.isNullOrEmpty()) {
                val jsonResult = JSONObject(detailedJson)

                // Get the NBest array (N-best recognition results)
                val nBestArray = jsonResult.optJSONArray("NBest")
                if (nBestArray != null && nBestArray.length() > 0) {
                    // Get the first (best) result
                    val bestResult = nBestArray.getJSONObject(0)
                    val confidence = bestResult.optDouble("Confidence", 1.0)

                    Log.d(TAG, "Extracted confidence score: $confidence for text: ${result.text}")
                    return confidence
                }
                // If structure unexpected, log for diagnostics
                Log.w(TAG, "JsonResult present but NBest missing or empty: $detailedJson")
            }

            // No usable JSON payload found; fall through to default

        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract confidence score: ${e.message}")
        }

        // Default to high confidence if extraction fails
        return 1.0
    }

    companion object {
        private const val TAG = "SpeechRecMgr"
    }
}

