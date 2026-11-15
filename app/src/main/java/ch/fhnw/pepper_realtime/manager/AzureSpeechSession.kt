package ch.fhnw.pepper_realtime.manager

import com.microsoft.cognitiveservices.speech.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal data class SpeechSettings(val key: String, val region: String, val language: String,
                                   val silenceTimeout: Int, val confidenceThreshold: Double)

/** Native SDK boundary; the manager owns readiness, retries and callback lifetimes. */
internal interface SpeechSession : AutoCloseable {
    interface Events {
        fun connected()
        fun disconnected()
        fun sessionStarted()
        fun sessionStopped()
        fun partial(text: String)
        fun recognized(text: String, confidence: Double)
        fun failed(message: String)
    }
    fun start()
    fun stop()
}

internal class AzureSpeechSession(settings: SpeechSettings, events: SpeechSession.Events) : SpeechSession {
    private val recognizer: SpeechRecognizer
    private val connection: Connection

    init {
        val config = SpeechConfig.fromSubscription(settings.key, settings.region)
        try {
            config.speechRecognitionLanguage = settings.language
            config.setProperty("Speech_SegmentationSilenceTimeoutMs", settings.silenceTimeout.toString())
            config.requestWordLevelTimestamps()
            config.outputFormat = OutputFormat.Detailed
            recognizer = SpeechRecognizer(config)
        } finally { config.close() }
        var opened: Connection? = null
        try {
            connection = Connection.fromRecognizer(recognizer).also { opened = it }
            connection.connected.addEventListener { _, _ -> events.connected() }
            connection.disconnected.addEventListener { _, _ -> events.disconnected() }
            recognizer.sessionStarted.addEventListener { _, _ -> events.sessionStarted() }
            recognizer.sessionStopped.addEventListener { _, _ -> events.sessionStopped() }
            recognizer.recognizing.addEventListener { _, e -> events.partial(e.result.text) }
            recognizer.recognized.addEventListener { _, e ->
                if (e.result.reason == ResultReason.RecognizedSpeech && !e.result.text.isNullOrBlank()) {
                    val confidence = try {
                        JSONObject(e.result.properties.getProperty(PropertyId.SpeechServiceResponse_JsonResult))
                            .optJSONArray("NBest")?.optJSONObject(0)?.optDouble("Confidence", 1.0) ?: 1.0
                    } catch (_: Exception) { 1.0 }
                    events.recognized(e.result.text, confidence)
                }
            }
            recognizer.canceled.addEventListener { _, e -> events.failed(e.errorDetails?.takeIf { it.isNotBlank() } ?: "Speech recognition cancelled") }
            // Retain the existing SDK initialization grace period and connection warmup.
            Thread.sleep(500)
            try { connection.openConnection(true) } catch (_: Exception) { }
        } catch (error: Exception) {
            try { opened?.close() } finally { recognizer.close() }
            throw error
        }
    }

    override fun start() { recognizer.startContinuousRecognitionAsync().get(20, TimeUnit.SECONDS) }
    override fun stop() { recognizer.stopContinuousRecognitionAsync().get(5, TimeUnit.SECONDS) }
    override fun close() {
        try { connection.close() } finally { recognizer.close() }
    }
}
