package io.github.anonymous.pepper_realtime.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.anonymous.pepper_realtime.R
import io.github.anonymous.pepper_realtime.controller.ChatRealtimeHandler
import io.github.anonymous.pepper_realtime.controller.ChatRobotLifecycleHandler
import io.github.anonymous.pepper_realtime.controller.ChatSessionController
import io.github.anonymous.pepper_realtime.controller.ChatSpeechListener
import io.github.anonymous.pepper_realtime.controller.GestureController
import io.github.anonymous.pepper_realtime.data.LocationProvider
import io.github.anonymous.pepper_realtime.di.ApplicationScope
import io.github.anonymous.pepper_realtime.di.ChatModule
import io.github.anonymous.pepper_realtime.di.IoDispatcher
import io.github.anonymous.pepper_realtime.di.NavigationModule
import io.github.anonymous.pepper_realtime.di.RobotHardwareModule
import io.github.anonymous.pepper_realtime.controller.AudioInputController
import io.github.anonymous.pepper_realtime.controller.AudioVolumeController
import io.github.anonymous.pepper_realtime.controller.ChatLifecycleController
import io.github.anonymous.pepper_realtime.controller.RobotFocusManager
import io.github.anonymous.pepper_realtime.manager.ApiKeyManager
import io.github.anonymous.pepper_realtime.manager.AudioPlayer
import io.github.anonymous.pepper_realtime.manager.DashboardManager
import io.github.anonymous.pepper_realtime.manager.MapState
import io.github.anonymous.pepper_realtime.manager.MapUiManager
import io.github.anonymous.pepper_realtime.manager.NavigationServiceManager
import io.github.anonymous.pepper_realtime.manager.PermissionManager
import io.github.anonymous.pepper_realtime.manager.SessionImageManager
import io.github.anonymous.pepper_realtime.manager.SettingsManagerCompat
import io.github.anonymous.pepper_realtime.manager.SettingsRepository
import io.github.anonymous.pepper_realtime.manager.TouchSensorManager
import io.github.anonymous.pepper_realtime.manager.TurnManager
import io.github.anonymous.pepper_realtime.network.RealtimeSessionManager
import io.github.anonymous.pepper_realtime.robot.RobotController
import io.github.anonymous.pepper_realtime.service.PerceptionService
import io.github.anonymous.pepper_realtime.service.VisionService
import io.github.anonymous.pepper_realtime.tools.ToolContext
import io.github.anonymous.pepper_realtime.tools.ToolContextFactory
import io.github.anonymous.pepper_realtime.tools.ToolRegistry
import io.github.anonymous.pepper_realtime.tools.interfaces.ToolHost
import io.github.anonymous.pepper_realtime.ui.compose.MainScreen
import io.github.anonymous.pepper_realtime.ui.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ChatActivity : AppCompatActivity(), ToolHost {

    companion object {
        private const val TAG = "ChatActivity"
    }

    // Feature Modules - group related dependencies
    @Inject internal lateinit var chatModule: ChatModule
    @Inject internal lateinit var robotModule: RobotHardwareModule
    @Inject internal lateinit var navigationModule: NavigationModule

    // Cross-cutting dependencies that remain directly injected
    @Inject internal lateinit var keyManager: ApiKeyManager
    @Inject internal lateinit var permissionManager: PermissionManager
    @Inject internal lateinit var _sessionImageManager: SessionImageManager
    @Inject internal lateinit var _settingsRepository: SettingsRepository
    @Inject internal lateinit var _toolRegistry: ToolRegistry
    @Inject internal lateinit var toolContextFactory: ToolContextFactory
    @Inject internal lateinit var lifecycleController: ChatLifecycleController
    @Inject @IoDispatcher internal lateinit var ioDispatcher: CoroutineDispatcher
    @Inject @ApplicationScope internal lateinit var applicationScope: CoroutineScope

    // Controllers & Managers (Initialized in onCreate)
    var settingsManager: SettingsManagerCompat? = null
        private set
    var toolContext: ToolContext? = null
        private set
    var volumeController: AudioVolumeController? = null
        private set

    // ViewModel
    private lateinit var viewModel: ChatViewModel
    private lateinit var settingsViewModel: SettingsViewModel

    // Public accessors via modules
    // sessionImageManager is accessed via getSessionImageManager() override from ToolHost
    val locationProvider: LocationProvider get() = navigationModule.locationProvider
    val sessionManager: RealtimeSessionManager get() = chatModule.sessionManager
    val gestureController: GestureController get() = robotModule.gestureController
    val audioPlayer: AudioPlayer get() = chatModule.audioPlayer
    val perceptionService: PerceptionService get() = robotModule.perceptionService
    val visionService: VisionService get() = robotModule.visionService
    val touchSensorManager: TouchSensorManager get() = robotModule.touchSensorManager
    val navigationServiceManager: NavigationServiceManager get() = navigationModule.navigationServiceManager
    val sessionController: ChatSessionController get() = chatModule.sessionController
    val audioInputController: AudioInputController get() = chatModule.audioInputController
    val turnManager: TurnManager get() = chatModule.turnManager
    val robotFocusManager: RobotFocusManager get() = robotModule.robotFocusManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize ViewModel
        viewModel = ViewModelProvider(this)[ChatViewModel::class.java]
        settingsViewModel = ViewModelProvider(this)[SettingsViewModel::class.java]

        // Set Controller on ViewModel (Break circular dependency)
        viewModel.setSessionController(chatModule.sessionController)
        
        // Initialize Managers
        this.settingsManager = SettingsManagerCompat(settingsViewModel)
        DashboardManager.initialize(robotModule.perceptionService)
        
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
                    onInterrupt = { 
                        try {
                            chatModule.interruptController.interruptSpeech()
                            chatModule.turnManager.setState(TurnManager.State.LISTENING)
                        } catch (e: Exception) {
                            Log.e(TAG, "Interrupt failed", e)
                        }
                    },
                    onStatusClick = { onStatusClick() }
                )
            }
        }

        // Observe ViewModel Events (Non-UI logic like restarting session)
        observeViewModelEvents()

        // Collect StateFlows for navigation status updates
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.mapStatus.collect { status ->
                        MapUiManager.updateMapStatus(status)
                    }
                }
                launch {
                    viewModel.localizationStatus.collect { status ->
                        MapUiManager.updateLocalizationStatus(status)
                    }
                }
            }
        }

        // Register Robot Lifecycle
        val lifecycleHandler = ChatRobotLifecycleHandler(this, viewModel, ioDispatcher, applicationScope)
        robotModule.robotFocusManager.setListener(lifecycleHandler)
        robotModule.robotFocusManager.register()

        // Turn Manager Listener
        chatModule.turnManager.setListener(chatModule.turnListener)

        // Tool Context
        this.toolContext = toolContextFactory.create(this)
        val listener = chatModule.eventHandler.listener
        if (listener is ChatRealtimeHandler) {
            listener.setToolContext(toolContext)
            listener.sessionController = chatModule.sessionController
        }

        val speechListener = ChatSpeechListener(
            chatModule.turnManager, null, chatModule.audioInputController.sttWarmupStartTime,
            chatModule.sessionController, chatModule.audioInputController, viewModel
        )
        chatModule.audioInputController.setSpeechListener(speechListener)

        // Session Dependencies
        chatModule.sessionManager.setSessionDependencies(_toolRegistry, toolContext!!, _settingsRepository, keyManager)

        // Volume Controller
        this.volumeController = AudioVolumeController()

        // Setup Permission Callback
        setupPermissionCallback()
        
        // Request Permissions
        permissionManager.checkAndRequestPermissions(this)
    }

    private fun onStatusClick() {
        try {
            val currentState = chatModule.turnManager.state

            if (currentState == TurnManager.State.SPEAKING) {
                if (viewModel.isResponseGenerating.value == true
                    || viewModel.isAudioPlaying.value == true
                    || chatModule.audioPlayer.isPlaying()
                ) {
                    chatModule.interruptController.interruptAndMute()
                }
            } else if (chatModule.audioInputController.isMuted) {
                unmute()
            } else if (currentState == TurnManager.State.LISTENING) {
                mute()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Status bar click failed", e)
        }
    }

    private fun observeViewModelEvents() {
        // Observe SettingsViewModel events
        settingsViewModel.restartSessionEvent.observe(this) { restart ->
            if (restart == true) {
                Log.i(TAG, "Core settings changed. Starting new session.")
                viewModel.startNewSession()
                settingsViewModel.consumeRestartSessionEvent()
            }
        }

        settingsViewModel.updateSessionEvent.observe(this) { update ->
            if (update == true) {
                Log.i(TAG, "Tools/prompt/temperature changed. Updating session.")
                chatModule.sessionManager.updateSession()
                settingsViewModel.consumeUpdateSessionEvent()
            }
        }

        settingsViewModel.restartRecognizerEvent.observe(this) { restart ->
            if (restart == true) {
                Log.i(TAG, "Recognizer settings changed. Re-initializing speech recognizer.")
                runOnUiThread { viewModel.setStatusText(getString(R.string.status_updating_recognizer)) }
                chatModule.audioInputController.stopContinuousRecognition()
                chatModule.audioInputController.reinitializeSpeechRecognizerForSettings()
                chatModule.audioInputController.startContinuousRecognition()
                settingsViewModel.consumeRestartRecognizerEvent()
            }
        }

        settingsViewModel.volumeChangeEvent.observe(this) { volume ->
            if (volume != null) {
                volumeController?.setVolume(this@ChatActivity, volume)
            }
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
        lifecycleController.onResume(robotModule.robotFocusManager)
    }

    override fun onDestroy() {
        shutdown()
        _sessionImageManager.deleteAllImages()
        super.onDestroy()
    }

    private fun shutdown() {
        chatModule.audioInputController.shutdown()

        chatModule.audioPlayer.setListener(null)
        chatModule.sessionManager.listener = null
        toolContext?.updateQiContext(null)

        robotModule.perceptionService.shutdown()
        DashboardManager.shutdown()
        robotModule.touchSensorManager.shutdown()
        navigationModule.navigationServiceManager.shutdown()

        viewModel.disconnectWebSocket()
        chatModule.audioPlayer.release()
        try {
            robotModule.gestureController.shutdown()
        } catch (_: Exception) {
        }

        robotModule.robotFocusManager.unregister()

        viewModel.setSessionController(null)
    }

    override fun updateNavigationStatus(mapStatus: String, localizationStatus: String) {
        viewModel.setMapStatus(mapStatus)
        viewModel.setLocalizationStatus(localizationStatus)
        // Also update the map preview to reflect the new status
        updateMapPreview()
    }

    override fun refreshChatMessages() {
        viewModel.refreshMessages()
    }

    override fun updateMapPreview() {
        val navManager = navigationModule.navigationServiceManager
        val locProvider = navigationModule.locationProvider

        val mapState = when {
            !navManager.isMapSavedOnDisk(this) -> MapState.NO_MAP
            !navManager.isMapLoaded() -> MapState.MAP_LOADED_NOT_LOCALIZED
            !navManager.isLocalizationReady() -> {
                val locStatus = viewModel.localizationStatus.value
                if (locStatus.contains("Failed")) {
                    MapState.LOCALIZATION_FAILED
                } else {
                    MapState.LOCALIZING
                }
            }
            else -> MapState.LOCALIZED
        }

        val mapGfx = navManager.getMapGraphInfo()

        MapUiManager.updateMapData(
            mapState,
            navManager.getMapBitmap(),
            mapGfx,
            locProvider.getSavedLocations()
        )
    }

    // Additional getters for ToolHost interface and specific uses
    fun getRobotController(): RobotController = robotModule.robotFocusManager.robotController

    fun getQiContext(): Any? = robotModule.robotFocusManager.qiContext

    override fun getSessionImageManager(): SessionImageManager = _sessionImageManager

    private fun mute() {
        chatModule.audioInputController.mute()
    }

    private fun unmute() {
        chatModule.audioInputController.unmute()
    }

    override fun muteMicrophone() {
        mute()
    }

    override fun unmuteMicrophone() {
        unmute()
    }

    override fun handleServiceStateChange(mode: String) {
        navigationModule.navigationServiceManager.handleServiceStateChange(mode)
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

    // ToolHost Implementation
    override fun getAppContext(): Context = applicationContext

    override fun getActivity(): Activity = this
}
