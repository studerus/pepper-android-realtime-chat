package ch.fhnw.pepper_realtime.controller

import android.util.Log
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.data.PerceptionData
import ch.fhnw.pepper_realtime.data.PersonEvent
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
                // Update map preview to sync all navigation data
                activity.updateMapPreview()
            }

            activity.audioInputController.cleanupSttForReinit()

            activity.toolContext?.updateQiContext(robotContext)

            // Initialize PerceptionService listener for Dashboard
            initializeDashboardListener()

            // Connect WebSocket client and HTTP service to PerceptionService
            activity.perceptionService.setWebSocketClient(viewModel.perceptionWebSocketClient)
            activity.perceptionService.setLocalFaceRecognitionService(viewModel.localFaceRecognitionService)
            
            // Set Pepper head IP on WebSocket client
            viewModel.perceptionWebSocketClient.setPepperHeadIp(
                viewModel.localFaceRecognitionService.getPepperHeadIp()
            )
            
            // Proactively start face recognition server on Pepper's head
            // This ensures the server is ready before the first person is detected
            applicationScope.launch(ioDispatcher) {
                Log.i(TAG, "Starting face recognition server proactively...")
                val started = viewModel.localFaceRecognitionService.ensureServerRunning()
                if (started) {
                    Log.i(TAG, "Face recognition server started successfully")
                } else {
                    Log.w(TAG, "Face recognition server could not be started (will retry on first detection)")
                }
            }

            activity.perceptionService.initialize(robotContext)
            
            // Start perception monitoring immediately so humans are detected right away
            // (not just when dashboard is opened)
            // Use restartMonitoring if it was already running to ensure fresh listener connection
            if (activity.perceptionService.isInitialized) {
                activity.perceptionService.restartMonitoring()
            } else {
                activity.perceptionService.startMonitoring()
            }

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
            
            // Register navigation status listener
            activity.navigationServiceManager.setListener(object : ch.fhnw.pepper_realtime.manager.NavigationServiceManager.NavigationServiceListener {
                override fun onNavigationPhaseChanged(phase: ch.fhnw.pepper_realtime.manager.NavigationServiceManager.NavigationPhase) {
                    // Phase changes are handled internally by NavigationServiceManager
                }
                
                override fun onNavigationStatusUpdate(mapStatus: String?, localizationStatus: String?) {
                    activity.runOnUiThread {
                        activity.updateNavigationStatus(
                            mapStatus ?: "",
                            localizationStatus ?: ""
                        )
                    }
                }
            })

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
        // Set up perception listener for dashboard updates
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
        
        // Set up event listener for rule-based automation
        activity.perceptionService.setEventListener(object : PerceptionService.EventListener {
            override fun onPersonEvent(
                event: PersonEvent,
                humanInfo: PerceptionData.HumanInfo,
                allHumans: List<PerceptionData.HumanInfo>
            ) {
                // Evaluate event against rules on the main thread
                activity.runOnUiThread {
                    viewModel.eventRuleEngine.evaluate(event, humanInfo, allHumans)
                }
            }
        })
        
        Log.i(TAG, "Dashboard and Event listeners initialized")
    }
}
