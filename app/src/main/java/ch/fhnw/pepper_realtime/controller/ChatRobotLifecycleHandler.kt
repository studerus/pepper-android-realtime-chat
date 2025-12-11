package ch.fhnw.pepper_realtime.controller

import android.util.Log
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.data.PerceptionData
import ch.fhnw.pepper_realtime.manager.TouchSensorManager
import ch.fhnw.pepper_realtime.manager.TurnManager
import ch.fhnw.pepper_realtime.service.PerceptionService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ch.fhnw.pepper_realtime.network.WebSocketConnectionCallback
import ch.fhnw.pepper_realtime.ui.ChatActivity
import ch.fhnw.pepper_realtime.ui.ChatMessage
import ch.fhnw.pepper_realtime.ui.ChatViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

class ChatRobotLifecycleHandler(
    private val activity: ChatActivity,
    private val viewModel: ChatViewModel,
    private val ioDispatcher: CoroutineDispatcher,
    private val applicationScope: CoroutineScope
) : RobotFocusManager.Listener {

    companion object {
        private const val TAG = "ChatRobotLifecycleHandler"
    }

    private val robotLifecycleLock = Any()
    private var hasFocusInitialized = false
    private var hadFocusLostSinceInit = false

    override fun onRobotReady(robotContext: Any?) {
        synchronized(robotLifecycleLock) {
            Log.i(TAG, "handleRobotReady: Acquired lifecycle lock")

            activity.locationProvider.refreshLocations(activity)

            activity.runOnUiThread {
                val hasMap = File(activity.filesDir, "maps/default_map.map").exists()
                viewModel.setMapStatus(activity.getString(if (hasMap) R.string.nav_map_saved else R.string.nav_map_none))
                viewModel.setLocalizationStatus(activity.getString(R.string.nav_localization_not_running))
            }

            activity.audioInputController.cleanupSttForReinit()

            activity.toolContext?.updateQiContext(robotContext)

            // Initialize PerceptionService listener for Dashboard
            initializeDashboardListener()

            activity.perceptionService.initialize(robotContext)

            activity.visionService.initialize(robotContext)

            activity.touchSensorManager.let { tsm ->
                tsm.setListener(object : TouchSensorManager.TouchEventListener {
                    override fun onSensorTouched(sensorName: String, touchState: Any?) {
                        Log.i(TAG, "Touch sensor $sensorName touched")
                        val touchMessage = TouchSensorManager.createTouchMessage(sensorName)
                        viewModel.addMessage(ChatMessage(touchMessage, ChatMessage.Sender.USER))
                        activity.sessionController.sendMessageToRealtimeAPI(touchMessage, true, true)
                    }

                    override fun onSensorReleased(sensorName: String, touchState: Any?) {
                        // No action needed
                    }
                })
                tsm.initialize(robotContext)
            }

            activity.navigationServiceManager.setDependencies(
                activity.perceptionService,
                activity.touchSensorManager,
                activity.gestureController
            )

            // Connect WebSocket on first init OR after focus regain
            val needsReconnect = !hasFocusInitialized || hadFocusLostSinceInit

            if (needsReconnect) {
                // Clear chat and session state if this is a reconnect after focus lost
                if (hadFocusLostSinceInit) {
                    Log.i(TAG, "Reconnecting after focus lost - clearing chat history")
                    viewModel.clearMessages()
                    activity.getSessionImageManager().deleteAllImages()
                    hadFocusLostSinceInit = false
                }

                hasFocusInitialized = true
                viewModel.lastChatBubbleResponseId = null
                activity.volumeController?.setVolume(activity, activity.getVolume())

                // Only show warmup indicator for Azure Speech mode
                val isRealtimeMode = activity.isUsingRealtimeAudioInput()
                if (!isRealtimeMode) {
                    viewModel.setWarmingUp(true)
                    viewModel.setStatusText(activity.getString(R.string.status_warming_up))
                } else {
                    viewModel.setStatusText(activity.getString(R.string.status_reconnecting))
                }

                Log.i(TAG, "Starting WebSocket connection...")
                activity.sessionController.connectWebSocket(object : WebSocketConnectionCallback {
                    override fun onSuccess() {
                        if (isRealtimeMode) {
                            // Realtime API Mode - no STT warmup needed
                            Log.i(TAG, "WebSocket connected successfully (Realtime API mode)")
                            viewModel.setStatusText(activity.getString(R.string.status_listening))
                            activity.turnManager.setState(TurnManager.State.LISTENING)
                        } else {
                            // Azure Speech Mode - perform STT warmup
                            Log.i(TAG, "WebSocket connected successfully, starting STT warmup...")
                            activity.audioInputController.startWarmup()
                            applicationScope.launch(ioDispatcher) {
                                try {
                                    activity.audioInputController.setupSpeechRecognizer()
                                    // LISTENING state will be set by ChatSpeechListener.onStarted()
                                } catch (e: Exception) {
                                    Log.e(TAG, "❌ STT setup failed", e)
                                    viewModel.setWarmingUp(false)
                                    viewModel.addMessage(
                                        ChatMessage(
                                            activity.getString(R.string.warmup_failed_msg),
                                            ChatMessage.Sender.ROBOT
                                        )
                                    )
                                    viewModel.setStatusText(activity.getString(R.string.ready_sr_lazy_init))
                                    activity.turnManager.setState(TurnManager.State.LISTENING)
                                }
                            }
                        }
                    }

                    override fun onError(error: Throwable) {
                        Log.e(TAG, "WebSocket connection failed", error)
                        viewModel.setWarmingUp(false)
                        viewModel.addMessage(
                            ChatMessage(
                                activity.getString(R.string.setup_error_during, error.message),
                                ChatMessage.Sender.ROBOT
                            )
                        )
                        viewModel.setStatusText(activity.getString(R.string.error_connection_failed))
                    }
                })
            }

            Log.i(TAG, "handleRobotReady: Released lifecycle lock")
        }
    }

    override fun onRobotFocusLost() {
        synchronized(robotLifecycleLock) {
            Log.i(TAG, "handleRobotFocusLost: Acquired lifecycle lock")

            // Mark that we lost focus after initialization (important for reconnect logic)
            if (hasFocusInitialized) {
                hadFocusLostSinceInit = true
                Log.i(TAG, "Robot focus lost after initialization - will reconnect on regain")
            }

            activity.audioInputController.setSttRunning(false)
            activity.audioInputController.cleanupSttForReinit()

            activity.toolContext?.updateQiContext(null)

            // Shutdown services only if they are initialized
            if (activity.perceptionService.isInitialized) {
                activity.perceptionService.shutdown()
            }
            viewModel.resetDashboard()
            activity.touchSensorManager.shutdown()
            activity.navigationServiceManager.shutdown()

            // Pause gestures but don't shutdown the executor
            try {
                activity.gestureController.stopNow()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping GestureController", e)
            }

            activity.runOnUiThread {
                viewModel.setStatusText(activity.getString(R.string.robot_focus_lost_message))
                val hasMap = File(activity.filesDir, "maps/default_map.map").exists()
                viewModel.setMapStatus(activity.getString(if (hasMap) R.string.nav_map_saved else R.string.nav_map_none))
                viewModel.setLocalizationStatus(activity.getString(R.string.nav_localization_stopped))
            }

            Log.i(TAG, "handleRobotFocusLost: Released lifecycle lock")
        }
    }

    override fun onRobotInitializationFailed(error: String) {
        activity.runOnUiThread { viewModel.setStatusText(error) }
    }

    fun isFocusAvailable(): Boolean {
        return true // Placeholder until RobotFocusManager is exposed via ChatActivity
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private fun initializeDashboardListener() {
        activity.perceptionService.setListener(object : PerceptionService.PerceptionListener {
            override fun onHumansDetected(humans: List<PerceptionData.HumanInfo>) {
                val timestamp = timeFormat.format(Date())
                activity.runOnUiThread {
                    viewModel.updateDashboardHumans(humans, timestamp)
                }
            }

            override fun onPerceptionError(error: String) {
                Log.w(TAG, "Perception error: $error")
            }

            override fun onServiceStatusChanged(isActive: Boolean) {
                Log.i(TAG, "Human awareness service active: $isActive")
            }
        })
        Log.i(TAG, "Dashboard listener initialized")
    }
}
