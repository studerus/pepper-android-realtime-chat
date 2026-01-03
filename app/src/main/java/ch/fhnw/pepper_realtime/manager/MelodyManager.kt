package ch.fhnw.pepper_realtime.manager

import android.util.Log
import ch.fhnw.pepper_realtime.manager.audio.ToneGenerator
import ch.fhnw.pepper_realtime.ui.MelodyPlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for melody playback with visual overlay.
 * Extracted from ChatViewModel for better separation of concerns.
 */
@Singleton
class MelodyManager @Inject constructor() {

    companion object {
        private const val TAG = "MelodyManager"
    }

    private val toneGenerator = ToneGenerator()
    private var melodyJob: Job? = null
    private var onMelodyFinishedCallback: ((wasCancelled: Boolean) -> Unit)? = null
    private val melodyCallbackLock = Any()
    private var coroutineScope: CoroutineScope? = null

    private val _state = MutableStateFlow(MelodyPlayerState())
    val state: StateFlow<MelodyPlayerState> = _state.asStateFlow()

    /**
     * Set the coroutine scope for launching playback jobs.
     * Should be called with viewModelScope during ViewModel initialization.
     */
    fun setCoroutineScope(scope: CoroutineScope) {
        this.coroutineScope = scope
    }

    /**
     * Start playing a melody with visual overlay.
     * @param melody The melody string to play
     * @param onFinished Callback when playback finishes with wasCancelled parameter
     * @return true if playback started successfully
     */
    fun startMelodyPlayer(melody: String, onFinished: ((wasCancelled: Boolean) -> Unit)? = null): Boolean {
        val scope = coroutineScope ?: run {
            Log.e(TAG, "Coroutine scope not set! Call setCoroutineScope first.")
            return false
        }

        if (_state.value.isVisible) {
            Log.w(TAG, "Melody player already active")
            return false
        }

        synchronized(melodyCallbackLock) {
            onMelodyFinishedCallback = onFinished
        }

        _state.update {
            it.copy(
                isVisible = true,
                melody = melody,
                isPlaying = true,
                progress = 0f,
                currentNote = ""
            )
        }

        melodyJob = scope.launch {
            toneGenerator.playMelody(melody, object : ToneGenerator.PlaybackCallback {
                override fun onNoteChanged(note: String, noteIndex: Int, totalNotes: Int) {
                    _state.update { it.copy(currentNote = note) }
                }

                override fun onProgressChanged(progress: Float) {
                    _state.update { it.copy(progress = progress) }
                }

                override fun onPlaybackFinished(wasCancelled: Boolean) {
                    _state.update {
                        it.copy(
                            isPlaying = false,
                            isVisible = false,
                            progress = if (wasCancelled) it.progress else 1f
                        )
                    }
                    melodyJob = null
                    // Call the finish callback with cancellation status (atomic to prevent double-invoke)
                    synchronized(melodyCallbackLock) {
                        onMelodyFinishedCallback?.invoke(wasCancelled)
                        onMelodyFinishedCallback = null
                    }
                }
            })
        }

        return true
    }

    /**
     * Stop the melody player and cancel playback.
     */
    fun dismissMelodyPlayer() {
        toneGenerator.cancel()
        melodyJob?.cancel()
        melodyJob = null
        _state.update {
            it.copy(isVisible = false, isPlaying = false)
        }
        // Call the finish callback with cancelled=true (atomic to prevent double-invoke)
        synchronized(melodyCallbackLock) {
            onMelodyFinishedCallback?.invoke(true)
            onMelodyFinishedCallback = null
        }
    }
}
