package ch.fhnw.pepper_realtime.manager

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresPermission
import ch.fhnw.pepper_realtime.network.RealtimeSessionManager
import java.util.concurrent.atomic.AtomicBoolean

/** Each capture attempt owns its recorder, format and cleanup, including failed reads. */
class RealtimeAudioInputManager internal constructor(
    private val sessionManager: RealtimeSessionManager,
    private val createRecorder: (Int, Int) -> AudioRecord = ::createAudioRecord,
    private val launchCapture: (Runnable) -> Thread = { work ->
        Thread(work, "realtime-audio-capture").apply { priority = Thread.NORM_PRIORITY + 1; start() }
    }
) {
    private class Capture(val recorder: AudioRecord, val google: Boolean) {
        val sampleRate = if (google) 16000 else 24000
        val bufferBytes = sampleRate / 10 * 2
        val active = AtomicBoolean(true)
        val released = AtomicBoolean(false)
        @Volatile var thread: Thread? = null
    }

    private val lock = Any()
    private var current: Capture? = null
    @Volatile var isGoogleProvider = false
    @Volatile var onCaptureError: ((String) -> Unit)? = null
    val isCapturing: Boolean get() = synchronized(lock) { current?.active?.get() == true }

    fun start(): Boolean = synchronized(lock) {
        if (current?.active?.get() == true) return true
        if (!sessionManager.isConnected) return false
        var recorder: AudioRecord? = null
        var run: Capture? = null
        try {
            val google = isGoogleProvider
            val rate = if (google) 16000 else 24000
            recorder = createRecorder(rate, rate / 10 * 2)
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize" }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord did not start recording" }
            val capture = Capture(recorder, google)
            run = capture
            current = capture
            capture.thread = launchCapture(Runnable { captureLoop(capture) })
            true
        } catch (error: Exception) {
            current = null
            run?.active?.set(false)
            if (run != null) release(run) else recorder?.let(::closeRecorder)
            Log.e(TAG, "Failed to start audio capture", error)
            false
        }
    }

    fun stop() {
        val run = synchronized(lock) {
            val capture = current ?: return
            capture.active.set(false)
            // Stop before joining: a blocking read must be unblocked first.
            release(capture)
            current = null
            if (capture.google && sessionManager.isConnected) {
                try { sessionManager.sendGoogleAudioStreamEnd() }
                catch (error: Exception) { Log.w(TAG, "Could not end audio stream", error) }
            }
            capture
        }
        val thread = run.thread
        if (thread != null && thread !== Thread.currentThread()) {
            try { thread.join(1000) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }

    private fun captureLoop(run: Capture) {
        var failure: String? = null
        try {
            val buffer = ByteArray(run.bufferBytes)
            var errors = 0
            var reportAt = SystemClock.elapsedRealtime()
            var capturedBytes = 0L
            var maxReadMs = 0L
            var maxSendMs = 0L
            while (run.active.get()) {
                try {
                    val started = SystemClock.elapsedRealtime()
                    val bytesRead = run.recorder.read(buffer, 0, buffer.size)
                    maxReadMs = maxOf(maxReadMs, SystemClock.elapsedRealtime() - started)
                    if (!run.active.get()) break
                    when {
                        bytesRead > 0 -> {
                            errors = 0
                            val sendStarted = SystemClock.elapsedRealtime()
                            synchronized(lock) {
                                if (current === run && run.active.get()) sendAudioChunk(run, buffer, bytesRead)
                            }
                            maxSendMs = maxOf(maxSendMs, SystemClock.elapsedRealtime() - sendStarted)
                            capturedBytes += bytesRead
                            val now = SystemClock.elapsedRealtime()
                            if (now - reportAt >= 5000) {
                                Log.i("AudioTiming", "capture window_ms=${now - reportAt} captured_audio_ms=${capturedBytes * 1000 / (run.sampleRate * 2)} max_read_ms=$maxReadMs max_enqueue_ms=$maxSendMs outgoing_queue_bytes=${sessionManager.pendingSendBytes}")
                                reportAt = now; capturedBytes = 0; maxReadMs = 0; maxSendMs = 0
                            }
                        }
                        bytesRead == AudioRecord.ERROR_INVALID_OPERATION || bytesRead == AudioRecord.ERROR_BAD_VALUE ||
                            bytesRead == AudioRecord.ERROR_DEAD_OBJECT -> {
                            failure = "Microphone capture failed (AudioRecord error $bytesRead)"
                            break
                        }
                        else -> {
                            if (++errors >= 5) { failure = "Microphone capture repeatedly failed (error $bytesRead)"; break }
                            Thread.sleep(50)
                        }
                    }
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    failure = "Microphone capture interrupted"
                    break
                } catch (error: Exception) {
                    if (++errors >= 5) { failure = "Microphone capture failed: ${error.message}"; break }
                    Log.w(TAG, "Audio read failed; retrying", error)
                }
            }
        } finally {
            synchronized(lock) {
                val notify = current === run && run.active.get()
                run.active.set(false)
                release(run)
                if (current === run) current = null
                if (notify) {
                    try { onCaptureError?.invoke(failure ?: "Microphone capture stopped unexpectedly") }
                    catch (error: Exception) { Log.w(TAG, "Audio failure callback failed", error) }
                }
            }
        }
    }

    private fun sendAudioChunk(run: Capture, data: ByteArray, length: Int) {
        val encoded = Base64.encodeToString(data, 0, length, Base64.NO_WRAP)
        if (run.google) sessionManager.sendGoogleAudioChunk(encoded) else sessionManager.sendAudioChunk(encoded)
    }

    private fun release(run: Capture) {
        if (run.released.compareAndSet(false, true)) closeRecorder(run.recorder)
    }

    companion object {
        private const val TAG = "RealtimeAudioInput"

        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        private fun createAudioRecord(sampleRate: Int, preferredBuffer: Int): AudioRecord {
            val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "Invalid AudioRecord buffer size: $minimum" }
            return AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, preferredBuffer))
        }

        private fun closeRecorder(recorder: AudioRecord) {
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            } catch (error: Exception) { Log.w(TAG, "Could not stop recorder", error) }
            finally {
                try { recorder.release() }
                catch (error: Exception) { Log.w(TAG, "Could not release recorder", error) }
            }
        }
    }
}
