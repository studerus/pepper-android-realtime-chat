package ch.fhnw.pepper_realtime.manager.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin

/**
 * A simple synthesizer to play melodies using sine waves.
 * Maps note names (C4, D#5) to frequencies.
 * Supports cancellation and progress callbacks.
 */
class ToneGenerator {

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val TAG = "ToneGenerator"
    }

    private val noteFrequencies = mapOf(
        "C3" to 130.81, "C#3" to 138.59, "D3" to 146.83, "D#3" to 155.56, "E3" to 164.81,
        "F3" to 174.61, "F#3" to 185.00, "G3" to 196.00, "G#3" to 207.65, "A3" to 220.00,
        "A#3" to 233.08, "B3" to 246.94,
        "C4" to 261.63, "C#4" to 277.18, "D4" to 293.66, "D#4" to 311.13, "E4" to 329.63,
        "F4" to 349.23, "F#4" to 369.99, "G4" to 392.00, "G#4" to 415.30, "A4" to 440.00,
        "A#4" to 466.16, "B4" to 493.88,
        "C5" to 523.25, "C#5" to 554.37, "D5" to 587.33, "D#5" to 622.25, "E5" to 659.25,
        "F5" to 698.46, "F#5" to 739.99, "G5" to 783.99, "G#5" to 830.61, "A5" to 880.00,
        "A#5" to 932.33, "B5" to 987.77,
        "C6" to 1046.50
    )

    private val isCancelled = AtomicBoolean(false)
    private var currentAudioTrack: AudioTrack? = null

    /**
     * Callback interface for melody playback events.
     */
    interface PlaybackCallback {
        fun onNoteChanged(note: String, noteIndex: Int, totalNotes: Int)
        fun onProgressChanged(progress: Float)
        fun onPlaybackFinished(wasCancelled: Boolean)
    }

    /**
     * Cancels the currently playing melody.
     */
    fun cancel() {
        isCancelled.set(true)
        try {
            currentAudioTrack?.stop()
            currentAudioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio track", e)
        }
        currentAudioTrack = null
    }

    /**
     * Plays a melody string with progress callbacks.
     * Format: "Note:DurationMs, Note:DurationMs"
     * Example: "C4:400, D4:400, E4:400"
     * Use "R" or "REST" for pauses.
     */
    suspend fun playMelody(melody: String, callback: PlaybackCallback? = null) {
        isCancelled.set(false)
        val notes = melody.split(",").map { it.trim() }
        val totalNotes = notes.size
        
        for ((index, noteData) in notes.withIndex()) {
            if (isCancelled.get()) {
                callback?.onPlaybackFinished(wasCancelled = true)
                return
            }
            
            val parts = noteData.split(":")
            if (parts.size != 2) continue
            
            val noteName = parts[0].uppercase()
            val duration = parts[1].toLongOrNull() ?: 400L
            
            // Report progress
            callback?.onNoteChanged(noteName, index, totalNotes)
            callback?.onProgressChanged((index + 1).toFloat() / totalNotes)
            
            if (noteName == "R" || noteName == "REST") {
                delay(duration)
            } else {
                val freq = noteFrequencies[noteName] ?: noteFrequencies[noteName.replace("#", "")] 
                if (freq != null) {
                    playTone(freq, duration)
                    // Tiny gap between notes for articulation
                    if (!isCancelled.get()) delay(50)
                } else {
                    Log.w(TAG, "Unknown note: $noteName")
                }
            }
        }
        
        callback?.onPlaybackFinished(wasCancelled = false)
    }

    private suspend fun playTone(frequency: Double, durationMs: Long) = withContext(Dispatchers.Default) {
        if (isCancelled.get()) return@withContext
        
        val numSamples = (durationMs * SAMPLE_RATE / 1000).toInt()
        val sample = ByteArray(2 * numSamples)
        val phaseIncrement = 2.0 * Math.PI * frequency / SAMPLE_RATE
        var phase = 0.0

        for (i in 0 until numSamples) {
            // Apply a simple envelope (fade in/out) to avoid clicks
            var amplitude = 0.8
            if (i < 500) { // Fade in over ~500 samples
                amplitude *= (i / 500.0)
            } else if (i > numSamples - 500) { // Fade out
                amplitude *= ((numSamples - i) / 500.0)
            }

            val sineVal = sin(phase) * amplitude
            // Convert to 16-bit PCM
            val shortVal = (sineVal * 32767).toInt().toShort()
            
            sample[2 * i] = (shortVal.toInt() and 0x00FF).toByte()
            sample[2 * i + 1] = ((shortVal.toInt() ushr 8) and 0x00FF).toByte()
            
            phase += phaseIncrement
        }

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(sample.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        currentAudioTrack = audioTrack

        try {
            if (isCancelled.get()) return@withContext
            audioTrack.write(sample, 0, sample.size)
            audioTrack.play()
            // Wait for playback to finish physically + buffer
            delay(durationMs)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing tone", e)
        } finally {
            try {
                audioTrack.release()
            } catch (_: Exception) {}
            if (currentAudioTrack == audioTrack) {
                currentAudioTrack = null
            }
        }
    }
}
