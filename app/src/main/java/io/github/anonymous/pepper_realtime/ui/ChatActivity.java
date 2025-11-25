package io.github.anonymous.pepper_realtime.ui;

import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.navigation.NavigationView;

import javax.inject.Inject;
import dagger.hilt.android.AndroidEntryPoint;

import androidx.lifecycle.ViewModelProvider;

import io.github.anonymous.pepper_realtime.R;
import io.github.anonymous.pepper_realtime.controller.*;
import io.github.anonymous.pepper_realtime.manager.*;
import io.github.anonymous.pepper_realtime.manager.SettingsRepository;
import io.github.anonymous.pepper_realtime.ui.settings.SettingsViewModel;
import io.github.anonymous.pepper_realtime.network.*;
import io.github.anonymous.pepper_realtime.robot.RobotController;
import io.github.anonymous.pepper_realtime.service.*;
import io.github.anonymous.pepper_realtime.tools.*;
import io.github.anonymous.pepper_realtime.data.LocationProvider;

import io.github.anonymous.pepper_realtime.tools.interfaces.ToolHost;

@AndroidEntryPoint
public class ChatActivity extends AppCompatActivity implements ToolHost {

    private static final String TAG = "ChatActivity";

    // Injected Dependencies
    @Inject
    ApiKeyManager keyManager;
    @Inject
    PermissionManager permissionManager;
    @Inject
    SessionImageManager sessionImageManager;
    @Inject
    LocationProvider locationProvider;
    @Inject
    RealtimeSessionManager sessionManager;
    @Inject
    GestureController gestureController;
    @Inject
    AudioPlayer audioPlayer;
    @Inject
    ThreadManager threadManager;
    @Inject
    ToolRegistry toolRegistry;
    @Inject
    PerceptionService perceptionService;
    @Inject
    VisionService visionService;
    @Inject
    TouchSensorManager touchSensorManager;
    @Inject
    MovementController movementController;
    @Inject
    NavigationServiceManager navigationServiceManager;
    @Inject
    SettingsRepository settingsRepository;
    @Inject
    ToolContextFactory toolContextFactory;

    // UI Components
    private TextView statusTextView;
    private LinearLayout warmupIndicatorLayout;
    private FloatingActionButton fabInterrupt;

    // Injected Controllers
    @Inject
    ChatSessionController sessionController;
    @Inject
    AudioInputController audioInputController;
    @Inject
    ChatInterruptController interruptController;
    @Inject
    RobotFocusManager robotFocusManager;
    @Inject
    TurnManager turnManager;
    @Inject
    RealtimeEventHandler eventHandler;
    @Inject
    ChatTurnListener turnListener;
    @Inject
    ChatLifecycleController lifecycleController;

    // Controllers & Managers (Initialized in onCreate)
    private MapUiManager mapUiManager;
    private ChatMessageAdapter chatAdapter;
    private SettingsManager settingsManager;
    private ChatMenuController chatMenuController;
    private DashboardManager dashboardManager;
    private ToolContext toolContext;

    private AudioVolumeController volumeController;

    // ViewModel
    private ChatViewModel viewModel;
    private SettingsViewModel settingsViewModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // supportRequestWindowFeature(Window.FEATURE_NO_TITLE); // Not needed with
        // AppCompat theme usually
        setContentView(R.layout.activity_chat);

        // Initialize UI references
        statusTextView = findViewById(R.id.statusTextView);
        warmupIndicatorLayout = findViewById(R.id.warmup_indicator_layout);
        fabInterrupt = findViewById(R.id.fab_interrupt);
        MaterialToolbar topAppBar = findViewById(R.id.topAppBar);
        setSupportActionBar(topAppBar);

        // Initialize ViewModel
        viewModel = new ViewModelProvider(this).get(ChatViewModel.class);
        settingsViewModel = new ViewModelProvider(this).get(SettingsViewModel.class);

        // Set Controller on ViewModel (Break circular dependency)
        viewModel.setSessionController(sessionController);

        // Observe ViewModel
        viewModel.getStatusText().observe(this, text -> {
            if (statusTextView != null)
                statusTextView.setText(text);
        });

        viewModel.getIsWarmingUp().observe(this, isWarmingUp -> {
            if (warmupIndicatorLayout != null) {
                warmupIndicatorLayout.setVisibility(isWarmingUp ? View.VISIBLE : View.GONE);
            }
            if (isWarmingUp) {
                viewModel.setStatusText(getString(R.string.status_warming_up));
            }
        });

        viewModel.getIsMuted().observe(this, isMuted -> {
            // Change status bar background color based on mute state
            if (statusTextView != null) {
                if (isMuted) {
                    statusTextView.setBackgroundColor(getResources().getColor(R.color.muted_status_background, getTheme()));
                } else {
                    statusTextView.setBackgroundColor(getResources().getColor(R.color.normal_status_background, getTheme()));
                }
            }
        });

        viewModel.getIsInterruptFabVisible().observe(this, isVisible -> {
            if (fabInterrupt != null) {
                fabInterrupt.setVisibility(isVisible ? View.VISIBLE : View.GONE);
            }
        });

        viewModel.getMessageList().observe(this, messages -> {
            if (chatAdapter != null) {
                chatAdapter.setMessages(messages);
                if (!messages.isEmpty()) {
                    RecyclerView chatRecyclerView = findViewById(R.id.chatRecyclerView);
                    if (chatRecyclerView != null) {
                        chatRecyclerView.scrollToPosition(messages.size() - 1);
                    }
                }
            }
        });

        viewModel.getMapStatus().observe(this, status -> {
            if (mapUiManager != null) {
                mapUiManager.updateMapStatus(status);
            }
        });

        viewModel.getLocalizationStatus().observe(this, status -> {
            if (mapUiManager != null) {
                mapUiManager.updateLocalizationStatus(status);
            }
        });

        // Observe SettingsViewModel events
        settingsViewModel.getRestartSessionEvent().observe(this, restart -> {
            if (Boolean.TRUE.equals(restart)) {
                Log.i(TAG, "Core settings changed. Starting new session.");
                viewModel.startNewSession();
                settingsViewModel.consumeRestartSessionEvent();
            }
        });

        settingsViewModel.getUpdateSessionEvent().observe(this, update -> {
            if (Boolean.TRUE.equals(update)) {
                Log.i(TAG, "Tools/prompt/temperature changed. Updating session.");
                if (sessionManager != null) {
                    sessionManager.updateSession();
                }
                settingsViewModel.consumeUpdateSessionEvent();
            }
        });

        settingsViewModel.getRestartRecognizerEvent().observe(this, restart -> {
            if (Boolean.TRUE.equals(restart)) {
                Log.i(TAG, "Recognizer settings changed. Re-initializing speech recognizer.");
                runOnUiThread(() -> viewModel.setStatusText(getString(R.string.status_updating_recognizer)));
                audioInputController.stopContinuousRecognition();
                audioInputController.reinitializeSpeechRecognizerForSettings();
                audioInputController.startContinuousRecognition();
                settingsViewModel.consumeRestartRecognizerEvent();
            }
        });

        settingsViewModel.getVolumeChangeEvent().observe(this, volume -> {
            if (volume != null) {
                volumeController.setVolume(ChatActivity.this, volume);
            }
        });

        initializeControllers();

        // Setup Listeners
        setupUiListeners();
        setupPermissionCallback();

        // Register Robot Lifecycle
        robotFocusManager.register();

        // Request Permissions
        permissionManager.checkAndRequestPermissions(this);
    }

    private void initializeControllers() {
        Log.i(TAG, "Initializing Controllers...");

        // Bind Views to AudioInputController
        // AudioInputController views are now handled by ViewModel observers

        // Map UI Manager
        TextView mapStatusTextView = findViewById(R.id.mapStatusTextView);
        TextView localizationStatusTextView = findViewById(R.id.localizationStatusTextView);
        FrameLayout mapPreviewContainer = findViewById(R.id.map_preview_container);
        io.github.anonymous.pepper_realtime.ui.MapPreviewView mapPreviewView = findViewById(R.id.map_preview_view);
        DrawerLayout drawerLayout = findViewById(R.id.drawer_layout);
        MaterialToolbar topAppBar = findViewById(R.id.topAppBar);

        this.mapUiManager = new MapUiManager(this, mapStatusTextView, localizationStatusTextView,
                mapPreviewContainer, mapPreviewView, drawerLayout, topAppBar);

        // Chat Adapter
        this.chatAdapter = new ChatMessageAdapter(viewModel.getMessageList().getValue());
        RecyclerView chatRecyclerView = findViewById(R.id.chatRecyclerView);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        chatRecyclerView.setLayoutManager(layoutManager);
        chatRecyclerView.setAdapter(chatAdapter);

        // Settings Manager
        NavigationView navigationView = findViewById(R.id.navigation_view);
        this.settingsManager = new SettingsManager(this, navigationView, settingsViewModel);

        // Dashboard Manager
        View dashboardOverlay = findViewById(R.id.dashboard_overlay);
        if (dashboardOverlay != null) {
            this.dashboardManager = new DashboardManager(this, dashboardOverlay);
            this.dashboardManager.initialize(perceptionService);
        }

        // Chat Menu Controller
        this.chatMenuController = new ChatMenuController(this, drawerLayout, mapUiManager,
                dashboardManager, settingsManager);
        this.chatMenuController.setupSettingsMenu();

        // Robot Focus Manager - Listener
        ChatRobotLifecycleHandler lifecycleHandler = new ChatRobotLifecycleHandler(this, viewModel);
        this.robotFocusManager.setListener(lifecycleHandler);

        // Turn Manager - Listener
        turnManager.setListener(turnListener);

        // Realtime Event Handler - ToolContext
        // We need to access the underlying handler to set tool context
        // This is a bit hacky, ideally we'd inject ToolContext too, but it has circular
        // deps

        // Tool Context
        this.toolContext = toolContextFactory.create(this, dashboardManager);

        // Set ToolContext on the handler
        if (eventHandler.getListener() instanceof ChatRealtimeHandler) {
            ((ChatRealtimeHandler) eventHandler.getListener()).setToolContext(toolContext);
            ((ChatRealtimeHandler) eventHandler.getListener()).setSessionController(sessionController);
        }

        // Speech Listener
        ChatSpeechListener speechListener = new ChatSpeechListener(turnManager, statusTextView,
                audioInputController.getSttWarmupStartTime(), sessionController, audioInputController, viewModel);
        audioInputController.setSpeechListener(speechListener);

        // UI Helper
        // UI Helper - Removed as logic moved to ViewModel

        // Session Dependencies
        this.sessionManager.setSessionDependencies(toolRegistry, toolContext, settingsRepository, keyManager);

        // Volume Controller
        this.volumeController = new AudioVolumeController();
    }

    private void setupPermissionCallback() {
        permissionManager.setCallback(new PermissionManager.PermissionCallback() {
            @Override
            public void onMicrophoneGranted() {
                Log.i(TAG, "Microphone permission granted");
            }

            @Override
            public void onMicrophoneDenied() {
                runOnUiThread(() -> viewModel.setStatusText(getString(R.string.error_microphone_permission_denied)));
            }

            @Override
            public void onCameraGranted() {
                Log.i(TAG, "Camera permission granted");
            }

            @Override
            public void onCameraDenied() {
                Log.w(TAG, "Camera permission denied");
            }
        });
    }

    private void setupUiListeners() {
        fabInterrupt.setOnClickListener(v -> {
            try {
                interruptController.interruptSpeech();
                if (turnManager != null)
                    turnManager.setState(TurnManager.State.LISTENING);
            } catch (Exception e) {
                Log.e(TAG, "Interrupt failed", e);
            }
        });

        statusTextView.setOnClickListener(v -> {
            try {
                if (turnManager == null)
                    return;
                TurnManager.State currentState = turnManager.getState();

                if (currentState == TurnManager.State.SPEAKING) {
                    if (Boolean.TRUE.equals(viewModel.getIsResponseGenerating().getValue())
                            || Boolean.TRUE.equals(viewModel.getIsAudioPlaying().getValue())
                            || (audioPlayer != null && audioPlayer.isPlaying())) {
                        interruptController.interruptAndMute();
                    }
                } else if (audioInputController.isMuted()) {
                    unmute();
                } else if (currentState == TurnManager.State.LISTENING) {
                    mute();
                }
            } catch (Exception e) {
                Log.e(TAG, "Status bar click handler failed", e);
            }
        });

        chatMenuController.setListener(() -> viewModel.startNewSession());
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        invalidateOptionsMenu();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return chatMenuController.onCreateOptionsMenu(menu, getMenuInflater());
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        return chatMenuController.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_new_chat) {
            viewModel.startNewSession();
            return true;
        }
        return chatMenuController.onOptionsItemSelected(item) || super.onOptionsItemSelected(item);
    }

    @Override
    protected void onStop() {
        super.onStop();
        lifecycleController.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        lifecycleController.onResume(robotFocusManager);
    }

    @Override
    protected void onDestroy() {
        shutdown();
        sessionImageManager.deleteAllImages();
        super.onDestroy();
    }

    private void shutdown() {
        if (audioInputController != null)
            audioInputController.shutdown();
        if (threadManager != null)
            threadManager.shutdown();

        if (audioPlayer != null)
            audioPlayer.setListener(null);
        if (sessionManager != null)
            sessionManager.setListener(null);
        if (toolContext != null)
            toolContext.updateQiContext(null);

        if (perceptionService != null)
            perceptionService.shutdown();
        if (dashboardManager != null)
            dashboardManager.shutdown();
        if (touchSensorManager != null)
            touchSensorManager.shutdown();
        if (navigationServiceManager != null)
            navigationServiceManager.shutdown();

        if (sessionController != null)
            viewModel.disconnectWebSocket();
        if (audioPlayer != null)
            audioPlayer.release();
        try {
            if (gestureController != null)
                gestureController.shutdown();
        } catch (Exception ignored) {
        }

        if (robotFocusManager != null)
            robotFocusManager.unregister();

        if (viewModel != null)
            viewModel.setSessionController(null);
    }

    public void updateNavigationStatus(String mapStatus, String localizationStatus) {
        viewModel.setMapStatus(mapStatus);
        viewModel.setLocalizationStatus(localizationStatus);
        // Also update the map preview to reflect the new status
        updateMapPreview();
    }

    @Override
    public void refreshChatMessages() {
        viewModel.refreshMessages();
    }

    public void updateMapPreview() {
        mapUiManager.updateMapPreview(navigationServiceManager, locationProvider);
    }

    // Getters for Controllers/Services
    public NavigationServiceManager getNavigationServiceManager() {
        return navigationServiceManager;
    }

    public PerceptionService getPerceptionService() {
        return perceptionService;
    }

    public GestureController getGestureController() {
        return gestureController;
    }

    public SettingsManager getSettingsManager() {
        return settingsManager;
    }

    public RealtimeSessionManager getSessionManager() {
        return sessionManager;
    }

    public RobotController getRobotController() {
        return robotFocusManager.getRobotController();
    }

    public Object getQiContext() {
        return robotFocusManager.getQiContext();
    }

    public LocationProvider getLocationProvider() {
        return locationProvider;
    }

    public AudioInputController getAudioInputController() {
        return audioInputController;
    }

    public ToolContext getToolContext() {
        return toolContext;
    }

    public DashboardManager getDashboardManager() {
        return dashboardManager;
    }

    public TouchSensorManager getTouchSensorManager() {
        return touchSensorManager;
    }

    public ChatSessionController getSessionController() {
        return sessionController;
    }

    public SessionImageManager getSessionImageManager() {
        return sessionImageManager;
    }

    public AudioVolumeController getVolumeController() {
        return volumeController;
    }

    public ThreadManager getThreadManager() {
        return threadManager;
    }

    public TurnManager getTurnManager() {
        return turnManager;
    }

    public VisionService getVisionService() {
        return visionService;
    }

    private void mute() {
        audioInputController.mute();
    }

    private void unmute() {
        audioInputController.unmute();
    }

    @Override
    public void muteMicrophone() {
        mute();
    }

    @Override
    public void unmuteMicrophone() {
        unmute();
    }

    public void handleServiceStateChange(String mode) {
        if (navigationServiceManager != null) {
            navigationServiceManager.handleServiceStateChange(mode);
        }
    }

    public void addImageMessage(String imagePath) {
        viewModel.addImageMessage(imagePath);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        permissionManager.handlePermissionResult(requestCode, grantResults);
    }

    // ToolHost Implementation
    @Override
    public android.content.Context getAppContext() {
        return getApplicationContext();
    }

    @Override
    public android.app.Activity getActivity() {
        return this;
    }
}
