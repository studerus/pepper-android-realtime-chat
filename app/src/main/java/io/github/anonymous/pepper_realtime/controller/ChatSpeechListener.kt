package io.github.anonymous.pepper_realtime.controller

import android.util.Log
import android.widget.TextView
import io.github.anonymous.pepper_realtime.R
import io.github.anonymous.pepper_realtime.manager.SpeechRecognizerManager
import io.github.anonymous.pepper_realtime.manager.TurnManager
import io.github.anonymous.pepper_realtime.ui.ChatMessage
import io.github.anonymous.pepper_realtime.ui.ChatViewModel

class ChatSpeechListener(
    private val turnManager: TurnManager?,
    private val statusTextView: TextView?,
    private val sttWarmupStartTime: Long,
    private val sessionController: ChatSessionController?,
    private val audioInputController: AudioInputController?,
    private val viewModel: ChatViewModel
) : SpeechRecognizerManager.ActivityCallbacks {

    companion object {
        private const val TAG = "ChatSpeechListener"
    }

    override fun onRecognizedText(text: String) {
        // Clear streaming bubble immediately
        viewModel.setPartialSpeechResult(null)

        // Gate STT: only accept in LISTENING state and when not muted
        if (turnManager != null && turnManager.state != TurnManager.State.LISTENING) {
            Log.i(TAG, "Ignoring STT result because state=${turnManager.state}")
            return
        }

        if (audioInputController?.isMuted == true) {
            Log.i(TAG, "Ignoring STT result because microphone is muted")
            return
        }

        val sanitizedText = text.replace(Regex("\\[Low confidence:.*?]"), "").trim()
        viewModel.addMessage(ChatMessage(sanitizedText, ChatMessage.Sender.USER))
        sessionController?.sendMessageToRealtimeAPI(text, true, false)
    }

    override fun onPartialText(partialText: String) {
        // Don't show partial text when muted
        if (audioInputController?.isMuted == true) {
            return
        }
        // Stream text to fake bubble
        viewModel.setPartialSpeechResult(partialText)
    }

    override fun onError(errorMessage: String?) {
        Log.e(TAG, "STT error: $errorMessage")
        viewModel.setStatusText(getString(R.string.error_generic, errorMessage ?: "Unknown error"))
    }

    override fun onStarted() {
        audioInputController?.setSttRunning(true)
        Log.i(TAG, "✅ STT is now actively listening - entering LISTENING state")

        // Hide warmup indicator via ViewModel
        viewModel.setWarmingUp(false)

        viewModel.setStatusText(getString(R.string.status_listening))

        // Now that recognition is ACTUALLY running, transition to LISTENING state
        turnManager?.setState(TurnManager.State.LISTENING)
    }

    override fun onStopped() {
        audioInputController?.setSttRunning(false)
        viewModel.setPartialSpeechResult(null)
    }

    override fun onReady() {
        val totalWarmupTime = System.currentTimeMillis() - sttWarmupStartTime
        Log.i(TAG, "✅ Speech Recognizer is fully warmed up and ready (total time: ${totalWarmupTime}ms)")

        // Start continuous recognition immediately (but keep warmup indicator visible)
        // The indicator will be hidden in onStarted() callback when recognition is
        // truly active
        Log.i(TAG, "STT warmup complete - starting continuous recognition (warmup indicator stays visible)...")

        try {
            audioInputController?.startContinuousRecognition()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recognition after warmup", e)
            viewModel.setWarmingUp(false)
            viewModel.setStatusText(getString(R.string.status_recognizer_not_ready))
        }
    }

    private fun getString(resId: Int): String {
        return viewModel.getApplication<android.app.Application>().getString(resId)
    }

    private fun getString(resId: Int, vararg formatArgs: Any): String {
        return viewModel.getApplication<android.app.Application>().getString(resId, *formatArgs)
    }
}

