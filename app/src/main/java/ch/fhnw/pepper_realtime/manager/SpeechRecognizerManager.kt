package ch.fhnw.pepper_realtime.manager

import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class SpeechRecognizerManager internal constructor(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val createSession: (SpeechSettings, SpeechSession.Events) -> SpeechSession = ::AzureSpeechSession
) {
    interface ActivityCallbacks {
        fun onRecognizedText(text: String)
        fun onPartialText(partialText: String)
        fun onError(errorMessage: String?)
        fun onStarted()
        fun onStopped()
        fun onReady()
    }

    private class Run(val settings: SpeechSettings) {
        var session: SpeechSession? = null
        var token: Any? = null
        var attempt = 0L
        var requested = false
        var executing = false
        var accepted = false
        var connected = false
        var listening = false
        var notified = false
    }
    private val lock = Any()
    private var current: Run? = null
    private var closed = false
    private var callbacks: ActivityCallbacks? = null

    fun setCallbacks(callbacks: ActivityCallbacks?) { synchronized(lock) { this.callbacks = callbacks } }

    fun configure(key: String, region: String, language: String, silenceTimeoutMs: Int, confidenceThreshold: Double) {
        synchronized(lock) {
            if (closed) return
            val old = current
            val run = Run(SpeechSettings(key, region, language, silenceTimeoutMs, confidenceThreshold))
            current = run
            executor.execute {
                dispose(old?.session)
                try {
                    if (prepare(run)) synchronized(lock) {
                        if (current === run && !closed && run.attempt == 0L) callbacks?.onReady()
                    }
                } catch (error: Exception) { fail(run, null, "Initialization failed: ${error.message}") }
            }
        }
    }

    fun start() {
        synchronized(lock) {
            if (closed) return
            val run = current
            if (run == null) { callbacks?.onError("Speech recognizer not configured"); return }
            if (run.requested) return
            run.requested = true
            run.accepted = false
            run.listening = false
            run.notified = false
            val attempt = ++run.attempt
            executor.execute { startAttempt(run, attempt) }
        }
    }

    private fun startAttempt(run: Run, attempt: Long) {
        if (!isRequested(run, attempt)) return
        try {
            if (run.session == null && !prepare(run)) return
            startSession(run, attempt)
        } catch (error: Exception) {
            if (!isRequested(run, attempt)) return
            // A first-launch SDK failure gets one fresh recognizer, with the same event handling.
            val message = error.message.orEmpty()
            if (listOf("0x22", "SPXERR_INVALID_RECOGNIZER", "invalid recognizer", "not initialized").any { message.contains(it) }) {
                val old = synchronized(lock) {
                    if (!isRequested(run, attempt)) return
                    detach(run)
                }
                dispose(old)
                try {
                    if (prepare(run) && isRequested(run, attempt)) startSession(run, attempt)
                    return
                } catch (retry: Exception) {
                    fail(run, attempt, "Speech recognition failed to start: ${retry.message}")
                    return
                }
            }
            fail(run, attempt, "Speech recognition failed to start: ${error.message}")
        }
    }

    private fun startSession(run: Run, attempt: Long) {
        val session = synchronized(lock) {
            if (!isRequested(run, attempt)) return
            val activeSession = run.session ?: error("Recognizer not initialized")
            run.executing = true
            activeSession
        }
        session.start()
        synchronized(lock) {
            if (isRequested(run, attempt)) { run.executing = false; run.accepted = true; signalReady(run) }
        }
    }

    fun stop() {
        synchronized(lock) {
            val run = current ?: return
            if (closed) return
            val notify = run.requested
            run.attempt++
            reset(run, keepConnection = true)
            executor.execute {
                val session = synchronized(lock) { run.session }
                try { session?.stop() } catch (error: Exception) {
                    Log.w(TAG, "Speech stop failed", error)
                    synchronized(lock) { if (run.session === session) detach(run) }
                    dispose(session)
                }
            }
            if (notify) callbacks?.onStopped()
        }
    }

    fun shutdown() {
        synchronized(lock) {
            if (closed) return
            closed = true
            val old = current
            current = null
            callbacks = null
            executor.execute { dispose(old?.session) }
            executor.shutdown()
        }
    }

    private fun prepare(run: Run): Boolean {
        val token = Any()
        synchronized(lock) {
            if (closed || current !== run) return false
            run.token = token
        }
        val events = object : SpeechSession.Events {
            private fun update(action: () -> Unit) = synchronized(lock) {
                if (!closed && current === run && run.token === token) action()
            }
            override fun connected() = update { run.connected = true; signalReady(run) }
            override fun disconnected() = update { run.connected = false; run.listening = false; run.notified = false }
            override fun sessionStarted() = update {
                if (run.requested && (run.executing || run.accepted)) { run.listening = true; signalReady(run) }
            }
            override fun sessionStopped() = update {
                if (run.requested && run.accepted) { reset(run, keepConnection = true); callbacks?.onStopped() }
            }
            override fun partial(text: String) = update { if (run.requested && run.accepted) callbacks?.onPartialText(text) }
            override fun recognized(text: String, confidence: Double) = update {
                if (run.requested && run.accepted) {
                    val suffix = if (confidence < run.settings.confidenceThreshold) " [Low confidence: ${(confidence * 100).roundToInt()}%]" else ""
                    callbacks?.onRecognizedText(text + suffix)
                }
            }
            override fun failed(message: String) = update {
                if (run.requested && (run.executing || run.accepted)) fail(run, run.attempt, message)
            }
        }
        val session = createSession(run.settings, events)
        val keep = synchronized(lock) {
            if (!closed && current === run && run.token === token) { run.session = session; true } else false
        }
        if (!keep) dispose(session)
        return keep
    }

    private fun isRequested(run: Run, attempt: Long): Boolean = synchronized(lock) {
        !closed && current === run && run.requested && run.attempt == attempt
    }

    private fun signalReady(run: Run) {
        if (run.requested && run.accepted && run.connected && run.listening && !run.notified) {
            run.notified = true
            callbacks?.onStarted()
        }
    }

    private fun reset(run: Run, keepConnection: Boolean = false) {
        run.requested = false; run.executing = false; run.accepted = false
        if (!keepConnection) run.connected = false
        run.listening = false; run.notified = false
    }

    private fun detach(run: Run): SpeechSession? {
        val session = run.session
        run.session = null; run.token = null
        run.executing = false; run.accepted = false; run.connected = false; run.listening = false; run.notified = false
        return session
    }

    private fun fail(run: Run, attempt: Long?, message: String) {
        synchronized(lock) {
            if (closed || current !== run || (attempt != null && !isRequested(run, attempt))) return
            val session = detach(run)
            reset(run)
            // Never close the native recognizer on its own event-callback thread.
            executor.execute { dispose(session) }
            callbacks?.onStopped()
            callbacks?.onError(message)
        }
    }

    private fun dispose(session: SpeechSession?) {
        if (session == null) return
        try { session.stop() } catch (error: Exception) { Log.w(TAG, "Speech stop failed", error) }
        finally { try { session.close() } catch (error: Exception) { Log.w(TAG, "Speech cleanup failed", error) } }
    }

    companion object { private const val TAG = "SpeechRecMgr" }
}
