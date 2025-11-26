package io.github.anonymous.pepper_realtime.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import dagger.hilt.android.AndroidEntryPoint
import io.github.anonymous.pepper_realtime.R
import io.github.anonymous.pepper_realtime.controller.*
import io.github.anonymous.pepper_realtime.data.LocationProvider
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
import io.github.anonymous.pepper_realtime.ui.settings.SettingsViewModel
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
    @Inject internal lateinit var _threadManager: ThreadManager
    @Inject internal lateinit var _toolRegistry: ToolRegistry
    @Inject internal lateinit var _perceptionService: PerceptionService
    @Inject internal lateinit var _visionService: VisionService
    @Inject internal lateinit var _touchSensorManager: TouchSensorManager
    @Inject internal lateinit var movementController: MovementController
    @Inject internal lateinit var _navigationServiceManager: NavigationServiceManager
    @Inject internal lateinit var _settingsRepository: SettingsRepository
    @Inject internal lateinit var toolContextFactory: ToolContextFactory

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
    private var mapUiManager: MapUiManager? = null
    private var chatAdapter: ChatMessageAdapter? = null
    var settingsManager: SettingsManager? = null
        private set
    var dashboardManager: DashboardManager? = null
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
    val threadManager: ThreadManager get() = _threadManager
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

        viewModel.messageList.observe(this) { messages ->
            chatAdapter?.setMessages(messages)
            if (messages.isNotEmpty()) {
                val chatRecyclerView: RecyclerView? = findViewById(R.id.chatRecyclerView)
                chatRecyclerView?.scrollToPosition(messages.size - 1)
            }
        }

        viewModel.mapStatus.observe(this) { status ->
            mapUiManager?.updateMapStatus(status)
        }

        viewModel.localizationStatus.observe(this) { status ->
            mapUiManager?.updateLocalizationStatus(status)
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

        // Map UI Manager
        val mapStatusTextView: TextView = findViewById(R.id.mapStatusTextView)
        val localizationStatusTextView: TextView = findViewById(R.id.localizationStatusTextView)
        val mapPreviewContainer: FrameLayout = findViewById(R.id.map_preview_container)
        val mapPreviewView: MapPreviewView = findViewById(R.id.map_preview_view)
        val drawerLayout: DrawerLayout = findViewById(R.id.drawer_layout)
        val topAppBar: MaterialToolbar = findViewById(R.id.topAppBar)

        this.mapUiManager = MapUiManager(
            this, mapStatusTextView, localizationStatusTextView,
            mapPreviewContainer, mapPreviewView, drawerLayout, topAppBar
        )

        // Chat Adapter
        this.chatAdapter = ChatMessageAdapter(viewModel.messageList.value ?: emptyList())
        val chatRecyclerView: RecyclerView = findViewById(R.id.chatRecyclerView)
        val layoutManager = LinearLayoutManager(this)
        chatRecyclerView.layoutManager = layoutManager
        chatRecyclerView.adapter = chatAdapter

        // Settings Manager
        val navigationView: NavigationView = findViewById(R.id.navigation_view)
        this.settingsManager = SettingsManager(this, navigationView, settingsViewModel)

        // Dashboard Manager
        val dashboardOverlay: View? = findViewById(R.id.dashboard_overlay)
        if (dashboardOverlay != null) {
            this.dashboardManager = DashboardManager(this, dashboardOverlay)
            this.dashboardManager?.initialize(_perceptionService)
        }

        // Chat Menu Controller
        val chatMenuController = ChatMenuController(
            this, drawerLayout, mapUiManager!!,
            dashboardManager, settingsManager!!
        )
        chatMenuController.setupSettingsMenu()
        this.chatMenuController = chatMenuController

        // Robot Focus Manager - Listener
        val lifecycleHandler = ChatRobotLifecycleHandler(this, viewModel)
        _robotFocusManager.setListener(lifecycleHandler)

        // Turn Manager - Listener
        _turnManager.setListener(turnListener)

        // Tool Context
        this.toolContext = toolContextFactory.create(this, dashboardManager)

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
        // Note: ThreadManager is an Application-scoped singleton managed by Hilt.
        // It should NOT be shutdown when Activity is destroyed, only when the app process ends.

        _audioPlayer.setListener(null)
        _sessionManager.listener = null
        toolContext?.updateQiContext(null)

        _perceptionService.shutdown()
        dashboardManager?.shutdown()
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
        mapUiManager?.updateMapPreview(_navigationServiceManager, _locationProvider)
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
