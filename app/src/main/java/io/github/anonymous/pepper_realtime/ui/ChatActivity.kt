package io.github.anonymous.pepper_realtime.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.ViewModelProvider
import dagger.hilt.android.AndroidEntryPoint
import io.github.anonymous.pepper_realtime.ui.compose.MainScreen
import io.github.anonymous.pepper_realtime.ui.compose.games.MemoryGameDialog
import io.github.anonymous.pepper_realtime.ui.compose.games.QuizDialog
import io.github.anonymous.pepper_realtime.ui.compose.games.TicTacToeDialog
import io.github.anonymous.pepper_realtime.ui.compose.settings.SettingsScreen
import io.github.anonymous.pepper_realtime.tools.games.MemoryGameManager
import io.github.anonymous.pepper_realtime.tools.games.TicTacToeGameManager
import io.github.anonymous.pepper_realtime.R
import io.github.anonymous.pepper_realtime.controller.*
import io.github.anonymous.pepper_realtime.data.LocationProvider
import io.github.anonymous.pepper_realtime.data.MapGraphInfo
import io.github.anonymous.pepper_realtime.manager.*
import io.github.anonymous.pepper_realtime.network.RealtimeEventHandler
import io.github.anonymous.pepper_realtime.network.RealtimeSessionManager
import io.github.anonymous.pepper_realtime.robot.RobotController
import io.github.anonymous.pepper_realtime.service.PerceptionService
import io.github.anonymous.pepper_realtime.service.VisionService
import io.github.anonymous.pepper_realtime.tools.ToolContext
import io.github.anonymous.pepper_realtime.tools.ToolContextFactory
import io.github.anonymous.pepper_realtime.tools.ToolRegistry
import io.github.anonymous.pepper_realtime.tools.interfaces.ToolHost
import io.github.anonymous.pepper_realtime.di.ApplicationScope
import io.github.anonymous.pepper_realtime.di.IoDispatcher
import io.github.anonymous.pepper_realtime.ui.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ChatActivity : AppCompatActivity(), ToolHost {

    companion object {
        private const val TAG = "ChatActivity"
    }

    // Injected Dependencies - use internal visibility to avoid getter conflicts
    @Inject internal lateinit var keyManager: ApiKeyManager
    @Inject internal lateinit var permissionManager: PermissionManager
    @Inject internal lateinit var _sessionImageManager: SessionImageManager
    @Inject internal lateinit var _locationProvider: LocationProvider
    @Inject internal lateinit var _sessionManager: RealtimeSessionManager
    @Inject internal lateinit var _gestureController: GestureController
    @Inject internal lateinit var _audioPlayer: AudioPlayer
    @Inject internal lateinit var _toolRegistry: ToolRegistry
    @Inject internal lateinit var _perceptionService: PerceptionService
    @Inject internal lateinit var _visionService: VisionService
    @Inject internal lateinit var _touchSensorManager: TouchSensorManager
    @Inject internal lateinit var movementController: MovementController
    @Inject internal lateinit var _navigationServiceManager: NavigationServiceManager
    @Inject internal lateinit var _settingsRepository: SettingsRepository
    @Inject internal lateinit var toolContextFactory: ToolContextFactory
    @Inject @IoDispatcher internal lateinit var ioDispatcher: CoroutineDispatcher
    @Inject @ApplicationScope internal lateinit var applicationScope: CoroutineScope

    // Injected Controllers - use internal visibility to avoid getter conflicts
    @Inject internal lateinit var _sessionController: ChatSessionController
    @Inject internal lateinit var _audioInputController: AudioInputController
    @Inject internal lateinit var interruptController: ChatInterruptController
    @Inject internal lateinit var _robotFocusManager: RobotFocusManager
    @Inject internal lateinit var _turnManager: TurnManager
    @Inject internal lateinit var eventHandler: RealtimeEventHandler
    @Inject internal lateinit var turnListener: ChatTurnListener
    @Inject internal lateinit var lifecycleController: ChatLifecycleController

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

    // Public accessors for other classes
    // sessionImageManager is accessed via getSessionImageManager() override from ToolHost
    val locationProvider: LocationProvider get() = _locationProvider
    val sessionManager: RealtimeSessionManager get() = _sessionManager
    val gestureController: GestureController get() = _gestureController
    val audioPlayer: AudioPlayer get() = _audioPlayer
    val perceptionService: PerceptionService get() = _perceptionService
    val visionService: VisionService get() = _visionService
    val touchSensorManager: TouchSensorManager get() = _touchSensorManager
    val navigationServiceManager: NavigationServiceManager get() = _navigationServiceManager
    val sessionController: ChatSessionController get() = _sessionController
    val audioInputController: AudioInputController get() = _audioInputController
    val turnManager: TurnManager get() = _turnManager
    val robotFocusManager: RobotFocusManager get() = _robotFocusManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize ViewModel
        viewModel = ViewModelProvider(this)[ChatViewModel::class.java]
        settingsViewModel = ViewModelProvider(this)[SettingsViewModel::class.java]

        // Set Controller on ViewModel (Break circular dependency)
        viewModel.setSessionController(_sessionController)
        
        // Initialize Managers
        this.settingsManager = SettingsManagerCompat(settingsViewModel)
        DashboardManager.initialize(_perceptionService)
        
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
                            interruptController.interruptSpeech()
                            _turnManager.setState(TurnManager.State.LISTENING)
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
        _robotFocusManager.setListener(lifecycleHandler)
        _robotFocusManager.register()

        // Turn Manager Listener
        _turnManager.setListener(turnListener)

        // Tool Context
        this.toolContext = toolContextFactory.create(this)
        val listener = eventHandler.listener
        if (listener is ChatRealtimeHandler) {
            listener.setToolContext(toolContext)
            listener.sessionController = _sessionController
        }

        val speechListener = ChatSpeechListener(
            _turnManager, null, _audioInputController.sttWarmupStartTime,
            _sessionController, _audioInputController, viewModel
        )
        _audioInputController.setSpeechListener(speechListener)

        // Session Dependencies
        _sessionManager.setSessionDependencies(_toolRegistry, toolContext!!, _settingsRepository, keyManager)

        // Volume Controller
        this.volumeController = AudioVolumeController()

        // Setup Permission Callback
        setupPermissionCallback()
        
        // Request Permissions
        permissionManager.checkAndRequestPermissions(this)
    }

    private fun onStatusClick() {
        try {
            val currentState = _turnManager.state

            if (currentState == TurnManager.State.SPEAKING) {
                if (viewModel.isResponseGenerating.value == true
                    || viewModel.isAudioPlaying.value == true
                    || _audioPlayer.isPlaying()
                ) {
                    interruptController.interruptAndMute()
                }
            } else if (_audioInputController.isMuted) {
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
                _sessionManager.updateSession()
                settingsViewModel.consumeUpdateSessionEvent()
            }
        }

        settingsViewModel.restartRecognizerEvent.observe(this) { restart ->
            if (restart == true) {
                Log.i(TAG, "Recognizer settings changed. Re-initializing speech recognizer.")
                runOnUiThread { viewModel.setStatusText(getString(R.string.status_updating_recognizer)) }
                _audioInputController.stopContinuousRecognition()
                _audioInputController.reinitializeSpeechRecognizerForSettings()
                _audioInputController.startContinuousRecognition()
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
        lifecycleController.onResume(_robotFocusManager)
    }

    override fun onDestroy() {
        shutdown()
        _sessionImageManager.deleteAllImages()
        super.onDestroy()
    }

    private fun shutdown() {
        _audioInputController.shutdown()

        _audioPlayer.setListener(null)
        _sessionManager.listener = null
        toolContext?.updateQiContext(null)

        _perceptionService.shutdown()
        DashboardManager.shutdown()
        _touchSensorManager.shutdown()
        _navigationServiceManager.shutdown()

        viewModel.disconnectWebSocket()
        _audioPlayer.release()
        try {
            _gestureController.shutdown()
        } catch (_: Exception) {
        }

        _robotFocusManager.unregister()

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
        val navManager = _navigationServiceManager
        val locProvider = _locationProvider

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
    fun getRobotController(): RobotController = _robotFocusManager.robotController

    fun getQiContext(): Any? = _robotFocusManager.qiContext

    override fun getSessionImageManager(): SessionImageManager = _sessionImageManager

    private fun mute() {
        _audioInputController.mute()
    }

    private fun unmute() {
        _audioInputController.unmute()
    }

    override fun muteMicrophone() {
        mute()
    }

    override fun unmuteMicrophone() {
        unmute()
    }

    override fun handleServiceStateChange(mode: String) {
        _navigationServiceManager.handleServiceStateChange(mode)
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
