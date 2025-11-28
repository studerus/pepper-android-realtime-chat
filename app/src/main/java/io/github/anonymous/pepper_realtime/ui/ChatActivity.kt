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
import androidx.compose.ui.platform.ComposeView
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import dagger.hilt.android.AndroidEntryPoint
import io.github.anonymous.pepper_realtime.ui.compose.ChatScreen
import io.github.anonymous.pepper_realtime.ui.compose.DashboardOverlay
import io.github.anonymous.pepper_realtime.ui.compose.NavigationOverlay
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

    // UI Components
    private var statusTextView: TextView? = null
    private var warmupIndicatorLayout: LinearLayout? = null
    private var fabInterrupt: FloatingActionButton? = null

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
        setContentView(R.layout.activity_chat)

        // Initialize UI references
        statusTextView = findViewById(R.id.statusTextView)
        warmupIndicatorLayout = findViewById(R.id.warmup_indicator_layout)
        fabInterrupt = findViewById(R.id.fab_interrupt)
        val topAppBar: MaterialToolbar = findViewById(R.id.topAppBar)
        setSupportActionBar(topAppBar)

        // Initialize ViewModel
        viewModel = ViewModelProvider(this)[ChatViewModel::class.java]
        settingsViewModel = ViewModelProvider(this)[SettingsViewModel::class.java]

        // Set Controller on ViewModel (Break circular dependency)
        viewModel.setSessionController(_sessionController)

        // Observe ViewModel
        viewModel.statusText.observe(this) { text ->
            statusTextView?.text = text
        }

        viewModel.isWarmingUp.observe(this) { isWarmingUp ->
            warmupIndicatorLayout?.visibility = if (isWarmingUp) View.VISIBLE else View.GONE
            if (isWarmingUp) {
                viewModel.setStatusText(getString(R.string.status_warming_up))
            }
        }

        viewModel.isMuted.observe(this) { isMuted ->
            // Change status bar background color based on mute state
            statusTextView?.setBackgroundColor(
                if (isMuted) {
                    resources.getColor(R.color.muted_status_background, theme)
                } else {
                    resources.getColor(R.color.normal_status_background, theme)
                }
            )
        }

        viewModel.isInterruptFabVisible.observe(this) { isVisible ->
            fabInterrupt?.visibility = if (isVisible) View.VISIBLE else View.GONE
        }

        // Note: messageList observation is handled by Compose's observeAsState

        viewModel.mapStatus.observe(this) { status ->
            MapUiManager.updateMapStatus(status)
        }

        viewModel.localizationStatus.observe(this) { status ->
            MapUiManager.updateLocalizationStatus(status)
        }

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

        initializeControllers()

        // Setup Listeners
        setupUiListeners()
        setupPermissionCallback()

        // Register Robot Lifecycle
        _robotFocusManager.register()

        // Request Permissions
        permissionManager.checkAndRequestPermissions(this)
    }

    private fun initializeControllers() {
        Log.i(TAG, "Initializing Controllers...")

        // Map UI Manager (Now handled by Singleton and Compose)
        // XML views removed

        // Chat Compose View
        val chatComposeView: ComposeView = findViewById(R.id.chatComposeView)
        chatComposeView.setContent {
            ChatScreen(
                messagesLiveData = viewModel.messageList,
                onImageClick = { imagePath -> showImageOverlay(imagePath) }
            )
            
            // Quiz Dialog (shown when QuizDialogManager.quizState.isVisible)
            val quizState = QuizDialogManager.quizState
            if (quizState.isVisible) {
                QuizDialog(
                    question = quizState.question,
                    options = quizState.options,
                    correctAnswer = quizState.correctAnswer,
                    onAnswered = { selectedOption ->
                        QuizDialogManager.onAnswerSelected(selectedOption)
                    },
                    onDismiss = {
                        QuizDialogManager.dismissQuiz()
                    }
                )
            }
            
            // TicTacToe Dialog (shown when TicTacToeGameManager.ticTacToeState.isVisible)
            val ticTacToeState = TicTacToeGameManager.ticTacToeState
            if (ticTacToeState.isVisible) {
                TicTacToeDialog(
                    gameState = ticTacToeState.gameState,
                    onUserMove = { position ->
                        TicTacToeGameManager.onUserMove(position)
                    },
                    onDismiss = {
                        TicTacToeGameManager.dismissGame()
                    }
                )
            }
            
            // Memory Game Dialog
            val memoryState = MemoryGameManager.gameState
            if (memoryState.isVisible) {
                MemoryGameDialog(
                    state = memoryState,
                    onCardClick = { cardId ->
                        MemoryGameManager.onCardClick(cardId)
                    },
                    onDismiss = {
                        MemoryGameManager.dismissGame()
                    }
                )
            }

            // Dashboard Overlay
            val dashboardState = DashboardManager.state
            DashboardOverlay(
                state = dashboardState,
                onClose = { DashboardManager.hideDashboard() }
            )

            // Navigation Overlay (Combined Map & Status)
            val navigationState = MapUiManager.state
            NavigationOverlay(
                state = navigationState,
                onClose = { MapUiManager.hide() }
            )
        }

        // Settings Compose View
        val settingsComposeView: ComposeView = findViewById(R.id.settingsComposeView)
        settingsComposeView.setContent {
            SettingsScreen(
                viewModel = settingsViewModel,
                apiKeyManager = keyManager,
                onSettingsChanged = { /* Settings are applied via ViewModel events */ }
            )
        }
        
        // Initialize SettingsManager for backward compatibility (will be removed later)
        this.settingsManager = SettingsManagerCompat(settingsViewModel)

        // Dashboard Manager
        DashboardManager.initialize(_perceptionService)

        // Chat Menu Controller
        val drawerLayout: DrawerLayout = findViewById(R.id.drawer_layout)
        val chatMenuController = ChatMenuController(
            this, drawerLayout,
            settingsManager!!
        )
        chatMenuController.setupSettingsMenu()
        this.chatMenuController = chatMenuController

        // Robot Focus Manager - Listener
        val lifecycleHandler = ChatRobotLifecycleHandler(this, viewModel, ioDispatcher, applicationScope)
        _robotFocusManager.setListener(lifecycleHandler)

        // Turn Manager - Listener
        _turnManager.setListener(turnListener)

        // Tool Context
        this.toolContext = toolContextFactory.create(this)

        // Set ToolContext on the handler
        val listener = eventHandler.listener
        if (listener is ChatRealtimeHandler) {
            listener.setToolContext(toolContext)
            listener.sessionController = _sessionController
        }

        // Speech Listener
        val speechListener = ChatSpeechListener(
            _turnManager, statusTextView, _audioInputController.sttWarmupStartTime,
            _sessionController, _audioInputController, viewModel
        )
        _audioInputController.setSpeechListener(speechListener)

        // Session Dependencies
        _sessionManager.setSessionDependencies(_toolRegistry, toolContext!!, _settingsRepository, keyManager)

        // Volume Controller
        this.volumeController = AudioVolumeController()
    }

    private var chatMenuController: ChatMenuController? = null

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

    private fun setupUiListeners() {
        fabInterrupt?.setOnClickListener {
            try {
                interruptController.interruptSpeech()
                _turnManager.setState(TurnManager.State.LISTENING)
            } catch (e: Exception) {
                Log.e(TAG, "Interrupt failed", e)
            }
        }

        statusTextView?.setOnClickListener {
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
                Log.e(TAG, "Status bar click handler failed", e)
            }
        }

        chatMenuController?.setListener { viewModel.startNewSession() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        return chatMenuController?.onCreateOptionsMenu(menu, menuInflater) ?: false
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        return chatMenuController?.onPrepareOptionsMenu(menu) ?: false
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_new_chat) {
            viewModel.startNewSession()
            return true
        }
        return chatMenuController?.onOptionsItemSelected(item) == true || super.onOptionsItemSelected(item)
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
                val locStatus = viewModel.localizationStatus.value ?: ""
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

    /**
     * Shows the image overlay with the given image path.
     * Called from Compose when an image is clicked.
     */
    private fun showImageOverlay(imagePath: String) {
        val imageOverlay = findViewById<View>(R.id.image_overlay)
        val overlayImage = imageOverlay?.findViewById<ImageView>(R.id.overlay_image)

        if (imageOverlay != null && overlayImage != null) {
            // Load image with sampling to avoid OOM
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(imagePath, options)
            options.inSampleSize = calculateInSampleSize(options, 2048, 2048)
            options.inJustDecodeBounds = false
            val bitmap = BitmapFactory.decodeFile(imagePath, options)
            overlayImage.setImageBitmap(bitmap)

            imageOverlay.visibility = View.VISIBLE
            imageOverlay.setOnClickListener { hideImageOverlay() }
        }
    }

    private fun hideImageOverlay() {
        val imageOverlay = findViewById<View>(R.id.image_overlay)
        imageOverlay?.visibility = View.GONE
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
