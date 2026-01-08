package ch.fhnw.pepper_realtime.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.controller.ChatRealtimeHandler
import ch.fhnw.pepper_realtime.controller.ChatRobotLifecycleHandler
import ch.fhnw.pepper_realtime.controller.ChatSessionController
import ch.fhnw.pepper_realtime.controller.ChatSpeechListener
import ch.fhnw.pepper_realtime.controller.GestureController
import ch.fhnw.pepper_realtime.data.LocationProvider
import ch.fhnw.pepper_realtime.di.ApplicationScope
import ch.fhnw.pepper_realtime.di.ChatDependencies
import ch.fhnw.pepper_realtime.di.IoDispatcher
import ch.fhnw.pepper_realtime.di.NavigationDependencies
import ch.fhnw.pepper_realtime.di.RobotHardwareDependencies
import ch.fhnw.pepper_realtime.controller.AudioInputController
import ch.fhnw.pepper_realtime.controller.AudioVolumeController
import ch.fhnw.pepper_realtime.controller.ChatLifecycleController
import ch.fhnw.pepper_realtime.controller.RobotFocusManager
import ch.fhnw.pepper_realtime.controller.VideoInputController
import ch.fhnw.pepper_realtime.manager.ApiKeyManager
import ch.fhnw.pepper_realtime.manager.AudioPlayer
import ch.fhnw.pepper_realtime.manager.NavigationServiceManager
import ch.fhnw.pepper_realtime.manager.PermissionManager
import ch.fhnw.pepper_realtime.manager.SessionImageManager
import ch.fhnw.pepper_realtime.manager.SettingsRepository
import ch.fhnw.pepper_realtime.manager.TouchSensorManager
import ch.fhnw.pepper_realtime.manager.TurnManager
import ch.fhnw.pepper_realtime.network.RealtimeSessionManager
import ch.fhnw.pepper_realtime.robot.RobotController
import ch.fhnw.pepper_realtime.service.PerceptionService
import ch.fhnw.pepper_realtime.service.VisionService
import ch.fhnw.pepper_realtime.tools.ToolContext
import ch.fhnw.pepper_realtime.tools.ToolContextFactory
import ch.fhnw.pepper_realtime.tools.ToolRegistry
import ch.fhnw.pepper_realtime.tools.interfaces.ToolHost
import ch.fhnw.pepper_realtime.ui.compose.MainScreen
import ch.fhnw.pepper_realtime.ui.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ChatActivity : AppCompatActivity(), ToolHost {

    companion object {
        private const val TAG = "ChatActivity"
    }

    // Feature Dependencies - group related dependencies
    @Inject internal lateinit var chatDeps: ChatDependencies
    @Inject internal lateinit var robotDeps: RobotHardwareDependencies
    @Inject internal lateinit var navigationDeps: NavigationDependencies

    // Cross-cutting dependencies that remain directly injected
    @Inject internal lateinit var keyManager: ApiKeyManager
    @Inject internal lateinit var permissionManager: PermissionManager
    @Inject internal lateinit var _sessionImageManager: SessionImageManager
    @Inject internal lateinit var _settingsRepository: SettingsRepository
    @Inject internal lateinit var _toolRegistry: ToolRegistry
    @Inject internal lateinit var toolContextFactory: ToolContextFactory
    @Inject internal lateinit var lifecycleController: ChatLifecycleController
    @Inject internal lateinit var videoInputController: VideoInputController
    @Inject @IoDispatcher internal lateinit var ioDispatcher: CoroutineDispatcher
    @Inject @ApplicationScope internal lateinit var applicationScope: CoroutineScope

    // Controllers & Managers (Initialized in onCreate)
    var toolContext: ToolContext? = null
        private set
    var volumeController: AudioVolumeController? = null
        private set

    // ViewModels
    val viewModel: ChatViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    // Public accessors via modules
    // sessionImageManager is accessed via getSessionImageManager() override from ToolHost
    val locationProvider: LocationProvider get() = navigationDeps.locationProvider
    
    // Settings accessors (replacing SettingsManagerCompat)
    fun getVolume(): Int = _settingsRepository.volume
    fun isUsingRealtimeAudioInput(): Boolean = _settingsRepository.isUsingRealtimeAudioInput
    fun getModel(): String = _settingsRepository.model
    val sessionManager: RealtimeSessionManager get() = chatDeps.sessionManager
    val gestureController: GestureController get() = robotDeps.gestureController
    val audioPlayer: AudioPlayer get() = chatDeps.audioPlayer
    val perceptionService: PerceptionService get() = robotDeps.perceptionService
    val visionService: VisionService get() = robotDeps.visionService
    val touchSensorManager: TouchSensorManager get() = robotDeps.touchSensorManager
    val navigationServiceManager: NavigationServiceManager get() = navigationDeps.navigationServiceManager
    val sessionController: ChatSessionController get() = chatDeps.sessionController
    val audioInputController: AudioInputController get() = chatDeps.audioInputController
    val turnManager: TurnManager get() = chatDeps.turnManager
    val robotFocusManager: RobotFocusManager get() = robotDeps.robotFocusManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Keep Screen On
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 2. Enable Immersive Sticky Mode (Hide System Bars)
        // Prevent layout resize when system bars or privacy indicators appear
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemUI()

        // Set Controller on ViewModel (Break circular dependency)
        viewModel.setSessionController(chatDeps.sessionController)
        viewModel.setupDrawingGameCallback()
        
        // Set robot state provider for event rules
        viewModel.setRobotStateProvider(object : ch.fhnw.pepper_realtime.service.EventRuleEngine.RobotStateProvider {
            override fun getCurrentState(): String = turnManager.state.name
        })
        
        // Setup Compose Content (MainScreen)
        val composeView = ComposeView(this)
        setContentView(composeView)
        
        composeView.setContent {
            MaterialTheme {
                MainScreen(
                    viewModel = viewModel,
                    settingsViewModel = settingsViewModel,
                    keyManager = keyManager,
                    toolRegistry = _toolRegistry,
                    onNewChat = { viewModel.startNewSession() },
                    onExit = { finish() },
                    onInterrupt = { 
                        try {
                            chatDeps.interruptController.interruptSpeech()
                            chatDeps.turnManager.setState(TurnManager.State.LISTENING)
                        } catch (e: Exception) {
                            Log.e(TAG, "Interrupt failed", e)
                        }
                    },
                    onStatusClick = { onStatusCapsuleClick() },
                    onMicToggle = { onMicrophoneToggle() },
                    onVideoToggle = { onVideoToggle() }
                )
            }
        }

        // Collect StateFlows for ViewModel events
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Settings events
                launch {
                    settingsViewModel.restartSessionEvent.collect { restart ->
                        if (restart) {
                            Log.i(TAG, "Core settings changed. Starting new session.")
                            viewModel.startNewSession()
                            settingsViewModel.consumeRestartSessionEvent()
                        }
                    }
                }
                launch {
                    settingsViewModel.updateSessionEvent.collect { update ->
                        if (update) {
                            Log.i(TAG, "Tools/prompt/temperature changed. Updating session.")
                            chatDeps.sessionManager.updateSession()
                            settingsViewModel.consumeUpdateSessionEvent()
                        }
                    }
                }
                launch {
                    settingsViewModel.restartRecognizerEvent.collect { restart ->
                        if (restart) {
                            Log.i(TAG, "Recognizer settings changed. Re-initializing speech recognizer.")
                            viewModel.setStatusText(getString(R.string.status_updating_recognizer))
                            chatDeps.audioInputController.stopContinuousRecognition()
                            chatDeps.audioInputController.reinitializeSpeechRecognizerForSettings()
                            chatDeps.audioInputController.startContinuousRecognition()
                            settingsViewModel.consumeRestartRecognizerEvent()
                        }
                    }
                }
                launch {
                    settingsViewModel.volumeChangeEvent.collect { volume ->
                        volume?.let { volumeController?.setVolume(this@ChatActivity, it) }
                    }
                }
                // Collect video stream frames for preview
                launch {
                    videoInputController.currentFrame.collect { frame ->
                        viewModel.setVideoPreviewFrame(frame)
                    }
                }
                launch {
                    videoInputController.isStreaming.collect { isStreaming ->
                        viewModel.setVideoStreamActive(isStreaming)
                    }
                }
                // Note: Perception monitoring runs continuously via WebSocket with auto-reconnect.
                // No need to start/stop when dashboard opens - it's always active.
            }
        }

        // Register Robot Lifecycle
        val lifecycleHandler = ChatRobotLifecycleHandler(this, viewModel, ioDispatcher, applicationScope)
        robotDeps.robotFocusManager.setListener(lifecycleHandler)
        robotDeps.robotFocusManager.register()

        // Turn Manager Listener
        chatDeps.turnManager.setListener(chatDeps.turnListener)

        // Tool Context
        this.toolContext = toolContextFactory.create(this)
        val listener = chatDeps.eventHandler.listener
        if (listener is ChatRealtimeHandler) {
            listener.setToolContext(toolContext)
            listener.sessionController = chatDeps.sessionController
        }

        val speechListener = ChatSpeechListener(
            chatDeps.turnManager, null, chatDeps.audioInputController.sttWarmupStartTime,
            chatDeps.sessionController, chatDeps.audioInputController, viewModel
        )
        chatDeps.audioInputController.setSpeechListener(speechListener)

        // Session Dependencies
        chatDeps.sessionManager.setSessionDependencies(_toolRegistry, toolContext!!, _settingsRepository, keyManager)

        // Volume Controller
        this.volumeController = AudioVolumeController()

        // Setup Permission Callback
        setupPermissionCallback()
        
        // Request Permissions
        permissionManager.checkAndRequestPermissions(this)
    }

    /**
     * Handle tap on status capsule - only interrupts robot speech, no mute.
     */
    private fun onStatusCapsuleClick() {
        try {
            val currentState = chatDeps.turnManager.state

            // Only interrupt during SPEAKING - no mute action
            if (currentState == TurnManager.State.SPEAKING) {
                if (viewModel.isResponseGenerating.value == true
                    || viewModel.isAudioPlaying.value == true
                    || chatDeps.audioPlayer.isPlaying()
                ) {
                    chatDeps.interruptController.interruptSpeech() // NOT interruptAndMute()
                    Log.i(TAG, "Speech interrupted via status capsule")
                }
            }
            // No action in other states - capsule just shows status
        } catch (e: Exception) {
            Log.e(TAG, "Status capsule click failed", e)
        }
    }

    /**
     * Handle tap on video button - toggles video streaming.
     */
    private fun onVideoToggle() {
        try {
            val newState = videoInputController.toggleStreaming()
            viewModel.setVideoStreamActive(newState)
            Log.i(TAG, "Video streaming toggled: active=$newState")
        } catch (e: Exception) {
            Log.e(TAG, "Video toggle failed", e)
        }
    }

    /**
     * Handle tap on microphone button - toggles user's mute intent.
     * This intent persists across robot state changes.
     */
    private fun onMicrophoneToggle() {
        try {
            val newIntent = !viewModel.userWantsMicOn.value
            viewModel.setUserWantsMicOn(newIntent)
            Log.i(TAG, "Mic intent toggled: userWantsMicOn=$newIntent")

            val currentState = chatDeps.turnManager.state

            // If currently LISTENING, apply immediately
            if (currentState == TurnManager.State.LISTENING) {
                if (newIntent) {
                unmute()
                } else {
                mute()
                }
            }
            // Otherwise, intent is stored and applied on state transition to LISTENING
        } catch (e: Exception) {
            Log.e(TAG, "Microphone toggle failed", e)
        }
    }

    private fun setupPermissionCallback() {
        permissionManager.setCallback(object : PermissionManager.PermissionCallback {
            override fun onMicrophoneGranted() {
                Log.i(TAG, "Microphone permission granted")
            }

            override fun onMicrophoneDenied() {
                runOnUiThread { viewModel.setStatusText(getString(R.string.error_microphone_permission_denied)) }
            }

            override fun onCameraGranted() {
                Log.i(TAG, "Camera permission granted")
            }

            override fun onCameraDenied() {
                Log.w(TAG, "Camera permission denied")
            }
        })
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    override fun onStop() {
        super.onStop()
        lifecycleController.onStop()
    }

    override fun onResume() {
        super.onResume()
        lifecycleController.onResume(robotDeps.robotFocusManager)
    }

    override fun onDestroy() {
        shutdown()
        _sessionImageManager.deleteAllImages()
        super.onDestroy()
    }

    private fun shutdown() {
        chatDeps.audioInputController.shutdown()
        videoInputController.shutdown()

        chatDeps.audioPlayer.setListener(null)
        chatDeps.sessionManager.listener = null
        toolContext?.updateQiContext(null)

        robotDeps.perceptionService.shutdown()
        viewModel.resetDashboard()
        robotDeps.touchSensorManager.shutdown()
        navigationDeps.navigationServiceManager.shutdown()

        viewModel.disconnectWebSocket()
        chatDeps.audioPlayer.release()
        try {
            robotDeps.gestureController.shutdown()
        } catch (_: Exception) {
        }

        robotDeps.robotFocusManager.unregister()

        viewModel.setSessionController(null)
    }

    override fun updateNavigationStatus(mapStatus: String, localizationStatus: String) {
        // Update localization status, then refresh all map data
        viewModel.setLocalizationStatus(localizationStatus)
        updateMapPreview()
    }

    override fun refreshChatMessages() {
        viewModel.refreshMessages()
    }

    override fun updateMapPreview() {
        val navManager = navigationDeps.navigationServiceManager
        val locProvider = navigationDeps.locationProvider

        val hasMapOnDisk = navManager.isMapSavedOnDisk(this)
        val isMapInMemory = navManager.isMapLoaded()
        val isLocalized = navManager.isLocalizationReady()

        // Update localization status based on current state
        // Preserve transitional states (Localizing, Navigating) - only update if definitive
        val currentLocStatus = viewModel.navigationState.value.localizationStatus
        val isTransitionalState = currentLocStatus.contains("Localizing") || 
                                  currentLocStatus.contains("Navigating") ||
                                  currentLocStatus.contains("Loading")
        
        val locStatusText = when {
            !hasMapOnDisk -> "No map available"
            !isMapInMemory -> "Not running"
            isLocalized -> "Localized"
            isTransitionalState -> currentLocStatus
            else -> "Not running"
        }
        viewModel.setLocalizationStatus(locStatusText)

        viewModel.updateMapData(
            hasMapOnDisk,
            navManager.getMapBitmap(),
            navManager.getMapGraphInfo(),
            locProvider.getSavedLocations()
        )
    }

    // Additional getters for ToolHost interface and specific uses
    fun getRobotController(): RobotController = robotDeps.robotFocusManager.robotController

    fun getQiContext(): Any? = robotDeps.robotFocusManager.qiContext

    override fun getSessionImageManager(): SessionImageManager = _sessionImageManager

    private fun mute() {
        chatDeps.audioInputController.mute()
    }

    private fun unmute() {
        chatDeps.audioInputController.unmute()
    }

    override fun muteMicrophone() {
        mute()
    }

    override fun unmuteMicrophone() {
        unmute()
    }

    override fun handleServiceStateChange(mode: String) {
        navigationDeps.navigationServiceManager.handleServiceStateChange(mode)
    }

    override fun addImageMessage(imagePath: String) {
        viewModel.addImageMessage(imagePath)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionManager.handlePermissionResult(requestCode, grantResults)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun hideSystemUI() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    // ToolHost Implementation
    override fun getAppContext(): Context = applicationContext

    override fun getActivity(): Activity = this
}
