package ch.fhnw.pepper_realtime.controller

import android.util.Log
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.manager.AudioPlayer
import ch.fhnw.pepper_realtime.manager.SessionImageManager
import ch.fhnw.pepper_realtime.manager.TouchSensorManager
import ch.fhnw.pepper_realtime.manager.TurnManager
import ch.fhnw.pepper_realtime.network.WebSocketConnectionCallback
import ch.fhnw.pepper_realtime.service.PerceptionService
import ch.fhnw.pepper_realtime.service.VisionService
import ch.fhnw.pepper_realtime.ui.ChatViewModel
import javax.inject.Inject

/**
 * Orchestrates lifecycle events for chat services.
 * Handles pausing/resuming of all services when app goes to background/foreground.
 */
class ChatLifecycleController @Inject constructor(
    private val viewModel: ChatViewModel,
    private val audioInputController: AudioInputController,
    private val sessionController: ChatSessionController?,
    private val perceptionService: PerceptionService?,
    private val visionService: VisionService?,
    private val touchSensorManager: TouchSensorManager?,
    private val gestureController: GestureController?,
    private val audioPlayer: AudioPlayer?,
    private val turnManager: TurnManager?,
    private val sessionImageManager: SessionImageManager
) {

    companion object {
        private const val TAG = "ChatLifecycleController"
    }

    private var wasStoppedByBackground = false

    /**
     * Handle activity stop (app going to background)
     */
    fun onStop() {
        Log.i(TAG, "Activity stopped (background) - pausing services")
        wasStoppedByBackground = true

        pauseActiveServices()

        turnManager?.setState(TurnManager.State.IDLE)
        viewModel.setStatusText(getString(R.string.app_paused))
    }

    /**
     * Handle activity resume (app coming to foreground)
     */
    fun onResume(robotFocusManager: RobotFocusManager) {
        if (wasStoppedByBackground && robotFocusManager.isFocusAvailable) {
            Log.i(TAG, "Activity resumed from background - restarting services")
            resumeServicesAfterBackground()
        }
    }

    private fun pauseActiveServices() {
        audioInputController.cleanupForRestart()

        sessionController?.disconnectWebSocketGracefully()

        if (perceptionService?.isInitialized == true) {
            perceptionService.stopMonitoring()
        }

        if (visionService?.isInitialized == true) {
            visionService.pause()
        }

        touchSensorManager?.pause()
        gestureController?.pause()

        audioPlayer?.let {
            try {
                it.interruptNow()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping audio playback during background", e)
            }
        }
    }

    private fun resumeServicesAfterBackground() {
        wasStoppedByBackground = false

        // Clear chat history to match fresh Realtime API session state
        viewModel.clearMessages()
        viewModel.setStatusText(getString(R.string.status_reconnecting))

        // Delete session images from previous session
        sessionImageManager.deleteAllImages()

        // Note: PerceptionService uses WebSocket with auto-reconnect, no manual restart needed

        if (visionService?.isInitialized == true) {
            visionService.resume()
        }

        touchSensorManager?.resume()
        gestureController?.resume()

        if (sessionController != null) {
            sessionController.connectWebSocket(object : WebSocketConnectionCallback {
                override fun onSuccess() {
                    Log.i(TAG, "WebSocket reconnected after resume - fresh session started")
                    // Set LISTENING state AFTER WebSocket is connected
                    turnManager?.setState(TurnManager.State.LISTENING)
                    viewModel.setStatusText(getString(R.string.status_listening))
                    // audioInputController.handleResume() removed to prevent double-start race condition
                    // TurnManager.setState(LISTENING) already triggers audio start via ChatTurnListener
                }

                override fun onError(error: Throwable) {
                    Log.e(TAG, "Failed to reconnect WebSocket after resume", error)
                    viewModel.setStatusText(getString(R.string.error_connection_failed_short))
                }
            })
        } else {
            // No session controller - just set state and resume audio
            turnManager?.setState(TurnManager.State.LISTENING)
            audioInputController.handleResume()
        }
    }

    private fun getString(resId: Int): String {
        return viewModel.getApplication<android.app.Application>().getString(resId)
    }
}

