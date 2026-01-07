package ch.fhnw.pepper_realtime.controller

import android.util.Log
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.manager.NavigationServiceManager
import ch.fhnw.pepper_realtime.manager.TurnManager
import ch.fhnw.pepper_realtime.ui.ChatViewModel
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

            if (robotFocusManager.robotController.isRobotHardwareAvailable() && qiContext == null) {
                Log.w(TAG, "Pepper robot focus lost, aborting onEnterListening to prevent crashes")
                return
            }

            // Check user's mute intent (persists across state changes)
            val userWantsMicOn = viewModel.userWantsMicOn.value
            
            if (!userWantsMicOn) {
                // User pre-muted - ensure mic stays off and sync state
                if (!audioInputController.isMuted) {
                    audioInputController.mute()
                }
                viewModel.setStatusText(getString(R.string.status_muted_tap_to_unmute))
                Log.i(TAG, "Entering LISTENING but user wants mic off - staying muted")
            } else if (audioInputController.isMuted) {
                // User wants mic on but it's still muted - unmute
                audioInputController.unmute()
                viewModel.setStatusText(getString(R.string.status_listening))
                Log.i(TAG, "Entering LISTENING - unmuting per user intent")
            } else {
                // Normal case: mic should be on
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
        Log.i(TAG, "State: Exiting SPEAKING - stopping gestures")
        gestureController.stopNow()
        
        // Don't set status text here - let onEnterListening or onEnterThinking handle it
        // Setting "listening" here would override the correct "thinking" status when
        // transitioning SPEAKING -> THINKING
        viewModel.setInterruptFabVisible(false)
    }

    private fun getString(resId: Int): String {
        return viewModel.getApplication<android.app.Application>().getString(resId)
    }
}
