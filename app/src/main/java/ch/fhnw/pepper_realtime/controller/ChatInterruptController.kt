package ch.fhnw.pepper_realtime.controller

import android.util.Log
import ch.fhnw.pepper_realtime.manager.AudioPlayer
import ch.fhnw.pepper_realtime.network.RealtimeSessionManager
import ch.fhnw.pepper_realtime.ui.ChatViewModel
import org.json.JSONObject
import javax.inject.Inject
import kotlin.math.max

class ChatInterruptController @Inject constructor(
    private val viewModel: ChatViewModel,
    private val sessionManager: RealtimeSessionManager,
    private val audioPlayer: AudioPlayer,
    private val gestureController: GestureController,
    private val audioInputController: AudioInputController
) {

    companion object {
        private const val TAG = "ChatInterruptController"
    }

    fun interruptSpeech() {
        try {
            if (!sessionManager.isConnected) return

            val isGenerating = viewModel.isResponseGenerating.value == true
            val isPlaying = viewModel.isAudioPlaying.value == true

            Log.d(TAG, "🚨 Interrupt: isResponseGenerating=$isGenerating, isAudioPlaying=$isPlaying")

            if (isGenerating) {
                val cancel = JSONObject()
                cancel.put("type", "response.cancel")
                sessionManager.send(cancel.toString())

                viewModel.cancelledResponseId = viewModel.currentResponseId
                viewModel.setResponseGenerating(false)
                Log.d(TAG, "Sent response.cancel for active generation")
            }

            val lastItemId = viewModel.lastAssistantItemId

            // Only truncate if we are actively generating or playing
            if (lastItemId != null && (isGenerating || isPlaying)) {
                var playedMs = max(0, audioPlayer.getEstimatedPlaybackPositionMs())

                // Safety margin to prevent "invalid_value" error if our local clock is slightly
                // ahead of server or if we try to truncate right at the end of the stream.
                if (playedMs > 0) {
                    playedMs = max(0, playedMs - 500)
                }

                Log.d(TAG, "Sending truncate for item=$lastItemId, audio_end_ms=$playedMs")
                val truncate = JSONObject()
                truncate.put("type", "conversation.item.truncate")
                truncate.put("item_id", lastItemId)
                truncate.put("content_index", 0)
                truncate.put("audio_end_ms", playedMs)
                sessionManager.send(truncate.toString())
            }

            audioPlayer.interruptNow()
            viewModel.setAudioPlaying(false)
            gestureController.stopNow()

        } catch (e: Exception) {
            Log.e(TAG, "Error during interruptSpeech", e)
        }
    }

    fun interruptAndMute() {
        interruptSpeech()
        audioInputController.mute()
    }
}

