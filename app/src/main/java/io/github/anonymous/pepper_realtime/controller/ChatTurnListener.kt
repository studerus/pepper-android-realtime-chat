package io.github.anonymous.pepper_realtime.controller

import android.util.Log
import io.github.anonymous.pepper_realtime.R
import io.github.anonymous.pepper_realtime.manager.NavigationServiceManager
import io.github.anonymous.pepper_realtime.manager.TurnManager
import io.github.anonymous.pepper_realtime.ui.ChatViewModel
import javax.inject.Inject

class ChatTurnListener @Inject constructor(
    private val viewModel: ChatViewModel,
    private val gestureController: GestureController,
    private val audioInputController: AudioInputController,
    private val robotFocusManager: RobotFocusManager,
    private val navigationServiceManager: NavigationServiceManager?,
    private val turnManager: TurnManager?
) : TurnManager.Callbacks {

    companion object {
        private const val TAG = "ChatTurnListener"
    }

    override fun onEnterListening() {
        try {
            // Check robot readiness
            val qiContext = robotFocusManager.qiContext

            if (robotFocusManager.robotController != null
                && robotFocusManager.robotController!!.isRobotHardwareAvailable() && qiContext == null
            ) {
                Log.w(TAG, "Pepper robot focus lost, aborting onEnterListening to prevent crashes")
                return
            }

            if (audioInputController.isMuted) {
                // Show muted status when returning to listening while muted
                viewModel.setStatusText(getString(R.string.status_muted_tap_to_unmute))
            } else {
                viewModel.setStatusText(getString(R.string.status_listening))
                if (!audioInputController.isSttRunning) {
                    try {
                        audioInputController.startContinuousRecognition()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start recognition in onEnterListening", e)
                        viewModel.setStatusText(getString(R.string.status_recognizer_not_ready))
                    }
                }
            }
            viewModel.setInterruptFabVisible(false)
        } catch (e: Exception) {
            Log.e(TAG, "Exception in onEnterListening", e)
        }
    }

    override fun onEnterThinking() {
        // Physically stop the mic so nothing is recognized during THINKING (only if running)
        if (audioInputController.isSttRunning) {
            audioInputController.stopContinuousRecognition()
        } else {
            Log.d(TAG, "STT already stopped, skipping redundant stop in THINKING state")
        }
        viewModel.setStatusText(getString(R.string.status_thinking))
        viewModel.setInterruptFabVisible(false)
    }

    override fun onEnterSpeaking() {
        Log.i(TAG, "State: Entering SPEAKING - starting gestures and stopping STT")
        audioInputController.stopContinuousRecognition()

        val qiContext = robotFocusManager.qiContext
        if (qiContext != null) {
            if (navigationServiceManager == null || !navigationServiceManager.areGesturesSuppressed()) {
                gestureController.start(
                    qiContext,
                    {
                        turnManager != null
                                && turnManager.state == TurnManager.State.SPEAKING
                                && robotFocusManager.qiContext != null
                                && (navigationServiceManager == null || !navigationServiceManager.areGesturesSuppressed())
                    },
                    { gestureController.getRandomExplainAnimationResId() }
                )
            }
        }

        viewModel.setInterruptFabVisible(false)
    }

    override fun onExitSpeaking() {
        Log.i(TAG, "State: Exiting SPEAKING - stopping gestures and starting STT")
        gestureController.stopNow()
        if (audioInputController.isMuted) {
            viewModel.setStatusText(getString(R.string.status_muted_tap_to_unmute))
        } else {
            viewModel.setStatusText(getString(R.string.status_listening))
        }
        viewModel.setInterruptFabVisible(false)
    }

    private fun getString(resId: Int): String {
        return viewModel.getApplication<android.app.Application>().getString(resId)
    }
}

