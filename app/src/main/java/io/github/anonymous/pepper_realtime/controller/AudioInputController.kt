package io.github.anonymous.pepper_realtime.controller

import android.util.Log
import dagger.hilt.android.scopes.ActivityScoped
import io.github.anonymous.pepper_realtime.R
import io.github.anonymous.pepper_realtime.manager.ApiKeyManager
import io.github.anonymous.pepper_realtime.manager.RealtimeAudioInputManager
import io.github.anonymous.pepper_realtime.manager.SettingsRepository
import io.github.anonymous.pepper_realtime.manager.SpeechRecognizerManager
import io.github.anonymous.pepper_realtime.manager.ThreadManager
import io.github.anonymous.pepper_realtime.network.RealtimeSessionManager
import io.github.anonymous.pepper_realtime.ui.ChatMessage
import io.github.anonymous.pepper_realtime.ui.ChatViewModel
import javax.inject.Inject

@ActivityScoped
class AudioInputController @Inject constructor(
    private val viewModel: ChatViewModel,
    private val settingsRepository: SettingsRepository,
    private val keyManager: ApiKeyManager,
    private val sessionManager: RealtimeSessionManager,
    private val threadManager: ThreadManager
) {

    companion object {
        private const val TAG = "AudioInputController"
    }

    // State
    private var sttManager: SpeechRecognizerManager? = null
    private var realtimeAudioInput: RealtimeAudioInputManager? = null
    var isMuted: Boolean = false
        private set
    var isSttRunning: Boolean = false
        private set
    var sttWarmupStartTime: Long = 0L
        private set

    // Listener for STT events
    private var speechListener: ChatSpeechListener? = null

    fun setSpeechListener(listener: ChatSpeechListener?) {
        this.speechListener = listener
    }

    fun setSttRunning(running: Boolean) {
        this.isSttRunning = running
    }

    fun mute() {
        isMuted = true
        viewModel.setMuted(true)
        stopContinuousRecognition()

        viewModel.setStatusText(getString(R.string.status_muted_tap_to_unmute))
        Log.i(TAG, "Microphone muted - tap status to un-mute")
    }

    fun resetMuteState() {
        isMuted = false
        viewModel.setMuted(false)
        Log.i(TAG, "Mute state reset (audio capture not started automatically)")
    }

    fun unmute() {
        isMuted = false
        viewModel.setMuted(false)
        viewModel.setStatusText(getString(R.string.status_listening))

        try {
            startContinuousRecognition()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recognition on unmute", e)
            viewModel.setStatusText(getString(R.string.status_recognizer_not_ready))
        }
        Log.i(TAG, "Microphone un-muted - resuming listening")
    }

    fun startContinuousRecognition() {
        if (settingsRepository.isUsingRealtimeAudioInput) {
            // MUTUAL EXCLUSION: Stop Azure STT if it's running before starting Realtime API
            if (sttManager != null) {
                threadManager.executeAudio { cleanupSttForReinit() }
                Log.i(TAG, "Stopped and cleaned up Azure STT to prevent interference with Realtime API audio")
            }

            if (realtimeAudioInput == null) {
                realtimeAudioInput = RealtimeAudioInputManager(sessionManager)
                Log.i(TAG, "Created RealtimeAudioInputManager")
            }
            threadManager.executeAudio {
                val started = realtimeAudioInput!!.start()
                if (started) {
                    Log.i(TAG, "Realtime API audio capture started (server VAD enabled)")
                } else {
                    Log.e(TAG, "Failed to start Realtime API audio capture")
                    viewModel.setStatusText(getString(R.string.error_audio_capture_failed))
                    viewModel.addMessage(
                        ChatMessage(
                            getString(R.string.error_audio_capture_permissions),
                            ChatMessage.Sender.ROBOT
                        )
                    )
                }
            }
            return
        }

        // MUTUAL EXCLUSION: Stop Realtime API audio if it's running before starting Azure STT
        if (realtimeAudioInput?.isCapturing == true) {
            threadManager.executeAudio { realtimeAudioInput?.stop() }
            Log.i(TAG, "Stopped Realtime API audio to prevent interference with Azure STT")
        }

        if (sttManager == null) {
            Log.w(TAG, "Speech recognizer not initialized yet, cannot start recognition.")
            viewModel.setStatusText(getString(R.string.status_recognizer_not_ready))
            return
        }
        threadManager.executeAudio { sttManager?.start() }
    }

    fun stopContinuousRecognition() {
        if (settingsRepository.isUsingRealtimeAudioInput) {
            if (realtimeAudioInput?.isCapturing == true) {
                threadManager.executeAudio {
                    realtimeAudioInput?.stop()
                    Log.i(TAG, "Realtime API audio capture stopped")
                }
            }
            return
        }
        sttManager?.let { threadManager.executeAudio { it.stop() } }
    }

    fun shutdown() {
        sttManager?.shutdown()
        sttManager = null
        realtimeAudioInput?.stop()
        realtimeAudioInput = null
    }

    fun cleanupForRestart() {
        if (realtimeAudioInput?.isCapturing == true) {
            realtimeAudioInput?.stop()
            Log.i(TAG, "Stopped Realtime audio capture")
        }
        if (sttManager != null && isSttRunning) {
            threadManager.executeAudio { sttManager?.stop() }
            Log.i(TAG, "Stopped Azure Speech recognizer")
        }
    }

    fun handleResume() {
        val currentMode = settingsRepository.audioInputMode
        if (SettingsRepository.MODE_AZURE_SPEECH == currentMode && sttManager != null) {
            try {
                sttManager?.start()
                Log.i(TAG, "Restarted Azure STT after resume")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart STT after resume", e)
            }
        } else if (SettingsRepository.MODE_REALTIME_API == currentMode && realtimeAudioInput != null) {
            try {
                realtimeAudioInput?.start()
                Log.i(TAG, "Restarted Realtime audio input after resume")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart Realtime audio after resume", e)
            }
        }
    }

    @Throws(Exception::class)
    fun setupSpeechRecognizer() {
        Log.i(TAG, "Starting STT setup...")

        if (!settingsRepository.isUsingRealtimeAudioInput) {
            Log.i(TAG, "Azure Speech mode active - setting up STT...")
            ensureSttManager()
            Log.i(TAG, "Configuring speech recognizer (this will also perform warmup)...")
            configureSpeechRecognizer()
            Log.i(TAG, "Azure Speech Recognizer setup initiated (warmup in progress, will notify via onReady() callback)")
        } else {
            Log.i(TAG, "Realtime API audio mode active - skipping Azure Speech warmup")
        }
    }

    fun reinitializeSpeechRecognizerForSettings() {
        if (settingsRepository.isUsingRealtimeAudioInput) {
            Log.i(TAG, "Realtime API audio mode - no STT re-initialization needed")
            return
        }
        threadManager.executeAudio {
            try {
                ensureSttManager()
                configureSpeechRecognizer()
                val langCode = settingsRepository.language
                val silenceTimeout = settingsRepository.silenceTimeout
                Log.i(TAG, "Azure Speech Recognizer re-initialized for language: $langCode, silence timeout: ${silenceTimeout}ms")
                viewModel.setStatusText(getString(R.string.status_listening))
            } catch (ex: Exception) {
                Log.e(TAG, "Azure Speech re-init failed", ex)
                viewModel.setStatusText(getString(R.string.error_updating_settings))
            }
        }
    }

    fun cleanupSttForReinit() {
        sttManager?.let {
            try {
                Log.i(TAG, "Cleaning up existing STT manager before reinitialization")
                it.shutdown()
                Thread.sleep(150)
            } catch (e: Exception) {
                Log.w(TAG, "Error during STT cleanup", e)
            }
            sttManager = null
        }
    }

    fun startWarmup() {
        sttWarmupStartTime = System.currentTimeMillis()
    }

    private fun ensureSttManager() {
        if (sttManager == null) {
            sttManager = SpeechRecognizerManager()
            Log.i(TAG, "Created new SpeechRecognizerManager")
        }
    }

    private fun configureSpeechRecognizer() {
        speechListener?.let { sttManager?.setCallbacks(it) }
        val langCode = settingsRepository.language
        val silenceTimeout = settingsRepository.silenceTimeout
        val confidenceThreshold = settingsRepository.confidenceThreshold
        sttManager?.configure(
            keyManager.azureSpeechKey,
            keyManager.azureSpeechRegion,
            langCode,
            silenceTimeout,
            confidenceThreshold.toDouble()
        )
    }

    private fun getString(resId: Int): String {
        return viewModel.getApplication<android.app.Application>().getString(resId)
    }
}

