package io.github.anonymous.pepper_realtime;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.appbar.MaterialToolbar;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.media.AudioManager;
import android.content.Context;
import android.view.Window;
import android.annotation.SuppressLint;
import android.widget.LinearLayout;
import android.widget.FrameLayout;

import io.github.anonymous.pepper_realtime.controller.ChatRealtimeHandler;
import io.github.anonymous.pepper_realtime.manager.OptimizedAudioPlayer;
import io.github.anonymous.pepper_realtime.network.RealtimeEventHandler;
import io.github.anonymous.pepper_realtime.controller.GestureController;
import io.github.anonymous.pepper_realtime.controller.MovementController;
import io.github.anonymous.pepper_realtime.controller.AudioInputController;
import io.github.anonymous.pepper_realtime.controller.ChatInterruptController;
import io.github.anonymous.pepper_realtime.controller.ChatSessionController;
import io.github.anonymous.pepper_realtime.controller.ChatSpeechListener;
import io.github.anonymous.pepper_realtime.controller.ChatTurnListener;
import io.github.anonymous.pepper_realtime.controller.RobotFocusManager;
import io.github.anonymous.pepper_realtime.data.LocationProvider;
import io.github.anonymous.pepper_realtime.manager.ApiKeyManager;
import io.github.anonymous.pepper_realtime.manager.DashboardManager;
import io.github.anonymous.pepper_realtime.manager.OptimizedThreadManager;
import io.github.anonymous.pepper_realtime.manager.SettingsManager;
import io.github.anonymous.pepper_realtime.manager.TurnManager;
import io.github.anonymous.pepper_realtime.network.OptimizedHttpClientManager;
import io.github.anonymous.pepper_realtime.network.RealtimeSessionManager;
import io.github.anonymous.pepper_realtime.robot.RobotController;
import io.github.anonymous.pepper_realtime.service.FaceRecognitionService;
import io.github.anonymous.pepper_realtime.service.PerceptionService;
import io.github.anonymous.pepper_realtime.service.VisionService;
import io.github.anonymous.pepper_realtime.manager.NavigationServiceManager;
import io.github.anonymous.pepper_realtime.manager.TouchSensorManager;
import io.github.anonymous.pepper_realtime.tools.ToolContext;
import io.github.anonymous.pepper_realtime.tools.ToolRegistryNew;
import io.github.anonymous.pepper_realtime.ui.ChatMenuController;
import io.github.anonymous.pepper_realtime.ui.ChatMessage;
import io.github.anonymous.pepper_realtime.ui.ChatMessageAdapter;
import io.github.anonymous.pepper_realtime.ui.ChatUiHelper;
import io.github.anonymous.pepper_realtime.ui.MapPreviewView;
import io.github.anonymous.pepper_realtime.ui.MapUiManager;

import okhttp3.Response;
import okio.ByteString;

public class ChatActivity extends AppCompatActivity {

    private static final String TAG = "ChatActivity";

    private static final int MICROPHONE_PERMISSION_REQUEST_CODE = 2;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 3;

    // UI Components
    private DrawerLayout drawerLayout;
    private TextView statusTextView;
    private RecyclerView chatRecyclerView;
    private ChatMessageAdapter chatAdapter;
    private final List<ChatMessage> messageList = new ArrayList<>();
    private LinearLayout warmupIndicatorLayout;
    private FloatingActionButton fabInterrupt;

    // Controllers & Managers
    private RobotFocusManager robotFocusManager;
    private AudioInputController audioInputController;
    private ChatMenuController chatMenuController;
    private ChatInterruptController interruptController;
    private ChatSessionController sessionController;
    private ChatUiHelper uiHelper;
    private MapUiManager mapUiManager;
    private ApiKeyManager keyManager;
    private SettingsManager settingsManager;
    private RealtimeSessionManager sessionManager;
    private NavigationServiceManager navigationServiceManager;
    private DashboardManager dashboardManager;
    private PerceptionService perceptionService;
    private VisionService visionService;
    private TouchSensorManager touchSensorManager;
    private final OptimizedThreadManager threadManager = OptimizedThreadManager.getInstance();
    private final GestureController gestureController = new GestureController();
    private ToolRegistryNew toolRegistry;
    private ToolContext toolContext;
    private OptimizedAudioPlayer audioPlayer;
    private TurnManager turnManager;
    private RealtimeEventHandler eventHandler;

    // State
    private boolean wasStoppedByBackground = false;
    private boolean hasFocusInitialized = false;
    private boolean isWarmingUp = false;
    
    private String lastAssistantItemId = null;
    private final List<String> sessionImagePaths = new ArrayList<>();
    
    private volatile boolean isResponseGenerating = false;
    private volatile boolean isAudioPlaying = false;
    private volatile String currentResponseId = null;
    private volatile String cancelledResponseId = null;
    private volatile String lastChatBubbleResponseId = null;
    private WebSocketConnectionCallback connectionCallback;
    private volatile boolean expectingFinalAnswerAfterToolCall = false;
    
    private final Map<String, ChatMessage> pendingUserTranscripts = new HashMap<>();
    private LocationProvider locationProvider;

    public interface WebSocketConnectionCallback {
        void onSuccess();
        void onError(Throwable error);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_chat);

        keyManager = new ApiKeyManager(this);

        // Initialize UI references
        drawerLayout = findViewById(R.id.drawer_layout);
        statusTextView = findViewById(R.id.statusTextView);
        chatRecyclerView = findViewById(R.id.chatRecyclerView);
        warmupIndicatorLayout = findViewById(R.id.warmup_indicator_layout);
        fabInterrupt = findViewById(R.id.fab_interrupt);

        // Initialize Map UI Manager
        TextView mapStatusTextView = findViewById(R.id.mapStatusTextView);
        TextView localizationStatusTextView = findViewById(R.id.localizationStatusTextView);
        FrameLayout mapPreviewContainer = findViewById(R.id.map_preview_container);
        MapPreviewView mapPreviewView = findViewById(R.id.map_preview_view);
        locationProvider = new LocationProvider();
        MaterialToolbar topAppBar = findViewById(R.id.topAppBar);
        setSupportActionBar(topAppBar);

        mapUiManager = new MapUiManager(this, mapStatusTextView, localizationStatusTextView, 
                                      mapPreviewContainer, mapPreviewView, drawerLayout, topAppBar);

        // Initialize RecyclerView
        chatAdapter = new ChatMessageAdapter(messageList);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        chatRecyclerView.setLayoutManager(layoutManager);
        chatRecyclerView.setAdapter(chatAdapter);

        // Initialize Session Manager
        if (sessionManager == null) {
            Log.i(TAG, "Creating NEW RealtimeSessionManager in onCreate");
            sessionManager = new RealtimeSessionManager();
            sessionManager.setSessionConfigCallback((success, error) -> {
                if (success) {
                    Log.i(TAG, "Session configured successfully - completing connection promise");
                    completeConnectionPromise();
                } else {
                    Log.e(TAG, "Session configuration failed: " + error);
                    failConnectionPromise("Session config failed: " + error);
                }
            });
            // Listener is essential!
            sessionManager.setListener(createSessionManagerListener());
        }

        // Initialize Settings Manager
        NavigationView navigationView = findViewById(R.id.navigation_view);
        settingsManager = new SettingsManager(this, navigationView);
        setupSettingsListener();

        // Initialize Controllers
        audioInputController = new AudioInputController(this, settingsManager, keyManager, 
                sessionManager, threadManager, statusTextView, fabInterrupt);
                
        chatMenuController = new ChatMenuController(this, drawerLayout, mapUiManager, 
                dashboardManager, settingsManager); 
        chatMenuController.setupSettingsMenu();

        robotFocusManager = new RobotFocusManager(this);
        robotFocusManager.setListener(createRobotLifecycleListener());

        // Initialize Core Logic
        audioPlayer = new OptimizedAudioPlayer();
        turnManager = new TurnManager(new ChatTurnListener(this, statusTextView, fabInterrupt, gestureController));
        
        // Initialize Interrupt Controller
        interruptController = new ChatInterruptController(this, sessionManager, audioPlayer, 
                                                        gestureController, audioInputController);
        
        // Initialize Tool System first (needed for EventHandler)
        MovementController movementController = new MovementController();
        navigationServiceManager = new NavigationServiceManager(movementController);
        toolContext = new ToolContext(this, null, this, keyManager, movementController, locationProvider);
        toolRegistry = new ToolRegistryNew();

        // Initialize Session Controller - RealtimeEventHandler created internally if needed or passed?
        setupRealtimeEventHandlerIfNeeded();
        
        sessionController = new ChatSessionController(this, sessionManager, settingsManager, keyManager,
                audioInputController, threadManager, gestureController, interruptController, turnManager,
                chatMenuController, eventHandler);
                
        // Initialize UI Helper
        uiHelper = new ChatUiHelper(this, messageList, chatAdapter, chatRecyclerView, statusTextView, 
                                  pendingUserTranscripts, audioInputController, sessionController, turnManager);
        
        if (sessionManager != null) {
            sessionManager.setSessionDependencies(toolRegistry, toolContext, settingsManager, keyManager);
        }

        // Setup Audio Player Listener
        audioPlayer.setListener(new OptimizedAudioPlayer.Listener() {
            @Override public void onPlaybackStarted() {
                turnManager.setState(TurnManager.State.SPEAKING);
            }
            @Override public void onPlaybackFinished() {
                isAudioPlaying = false;
                // lastAudioDoneTs logic moved or kept? If we remove it, we remove interrupt guard logic based on it.
                // Let's keep it simple for now.
                if (!expectingFinalAnswerAfterToolCall && !isResponseGenerating) {
                    turnManager.setState(TurnManager.State.LISTENING);
                } else {
                    turnManager.setState(TurnManager.State.THINKING);
                }
            }
        });

        // Setup UI Listeners
        setupUiListeners();

        // Register Robot Lifecycle
        robotFocusManager.register();

        // Request Permissions
        checkPermissions();
    }
    
    private RealtimeSessionManager.Listener createSessionManagerListener() {
        return new RealtimeSessionManager.Listener() {
            @Override public void onOpen(Response response) {
                Log.i(TAG, "WebSocket onOpen() - configuring initial session");
                sessionManager.configureInitialSession();
            }
            @Override public void onTextMessage(String text) {
                if (eventHandler != null) eventHandler.handle(text);
            }
            @Override public void onBinaryMessage(ByteString bytes) {
                // Handle audio input buffer if needed, usually server sends audio via text events
            }
            @Override public void onClosing(int code, String reason) {
                Log.d(TAG, "WebSocket closing: " + code + " " + reason);
            }
            @Override public void onClosed(int code, String reason) {
                Log.d(TAG, "WebSocket closed: " + code + " " + reason);
                failConnectionPromise("Connection closed: " + reason);
            }
            @Override public void onFailure(Throwable t, Response response) {
                Log.e(TAG, "WebSocket Failure: " + t.getMessage());
                failConnectionPromise("Connection failed: " + t.getMessage());
            }
        };
    }

    private void setupSettingsListener() {
        settingsManager.setListener(new SettingsManager.SettingsListener() {
            @Override
            public void onSettingsChanged() {
                Log.i(TAG, "Core settings changed. Starting new session.");
                sessionController.startNewSession();
            }
            @Override
            public void onRecognizerSettingsChanged() {
                Log.i(TAG, "Recognizer settings changed. Re-initializing speech recognizer.");
                runOnUiThread(() -> statusTextView.setText(getString(R.string.status_updating_recognizer)));
                audioInputController.stopContinuousRecognition();
                audioInputController.reinitializeSpeechRecognizerForSettings();
                audioInputController.startContinuousRecognition();
            }
            @Override
            public void onVolumeChanged(int volume) {
                applyVolume(volume);
            }
            @Override
            public void onToolsChanged() {
                Log.i(TAG, "Tools/prompt/temperature changed. Updating session.");
                if (sessionManager != null) {
                    sessionManager.updateSession();
                }
            }
        });
    }

    private RobotFocusManager.Listener createRobotLifecycleListener() {
        return new RobotFocusManager.Listener() {
            @Override
            public void onRobotReady(Object robotContext) {
                handleRobotReady(robotContext);
            }
            @Override
            public void onRobotFocusLost() {
                handleRobotFocusLost();
            }
            @Override
            public void onRobotInitializationFailed(String error) {
                runOnUiThread(() -> statusTextView.setText(error));
            }
            @Override
            public void onRobotFocusRefused(String reason) {
                runOnUiThread(() -> statusTextView.setText(getString(R.string.robot_focus_refused_message, reason)));
            }
        };
    }

    private void setupUiListeners() {
        fabInterrupt.setOnClickListener(v -> {
            try {
                interruptController.interruptSpeech();
                if (turnManager != null) turnManager.setState(TurnManager.State.LISTENING);
            } catch (Exception e) {
                Log.e(TAG, "Interrupt failed", e);
            }
        });

        statusTextView.setOnClickListener(v -> {
            try {
                if (turnManager == null) return;
                TurnManager.State currentState = turnManager.getState();
                
                if (currentState == TurnManager.State.SPEAKING) {
                    if (isResponseGenerating || isAudioPlaying || (audioPlayer != null && audioPlayer.isPlaying())) {
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
        
        // Wire up menu controller listener
        chatMenuController.setListener(() -> sessionController.startNewSession());
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_PERMISSION_REQUEST_CODE);
        } else {
            Log.i(TAG, "Microphone permission available - STT will be initialized during warmup");
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    @SuppressLint("NullableProblems")
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MICROPHONE_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Microphone permission granted - STT will be initialized during warmup");
            } else {
                runOnUiThread(() -> statusTextView.setText(getString(R.string.error_microphone_permission_denied)));
            }
        } else if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Camera permission granted - vision analysis now available");
            } else {
                Log.w(TAG, "Camera permission denied - vision analysis will not work");
            }
        }
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
            sessionController.startNewSession();
            return true;
        }
        return chatMenuController.onOptionsItemSelected(item) || super.onOptionsItemSelected(item);
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.i(TAG, "🔄 Activity stopped (background) - pausing services");
        wasStoppedByBackground = true;
        
        audioInputController.cleanupForRestart();
        
        if (audioPlayer != null) {
            try {
                audioPlayer.interruptNow();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping audio playback during background", e);
            }
        }
        
        runOnUiThread(() -> {
            if (turnManager != null) {
                turnManager.setState(TurnManager.State.IDLE);
            }
            statusTextView.setText(getString(R.string.app_paused));
        });
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        if (wasStoppedByBackground && robotFocusManager.isFocusAvailable()) {
            Log.i(TAG, "🔄 Activity resumed from background - restarting services");
            wasStoppedByBackground = false;
            
            runOnUiThread(() -> {
                if (turnManager != null) {
                    turnManager.setState(TurnManager.State.LISTENING);
                }
                statusTextView.setText(getString(R.string.status_listening));
            });
            
            audioInputController.handleResume(wasStoppedByBackground);
        }
    }

    @Override
    protected void onDestroy() {
        audioInputController.shutdown();
        threadManager.shutdown();
        cleanupServices();
        OptimizedHttpClientManager.getInstance().shutdown();
        deleteSessionImages();
        sessionController.disconnectWebSocket();
        releaseAudioTrack();
        shutdownManagers();
        robotFocusManager.unregister();
        super.onDestroy();
    }
    
    private void shutdownManagers() {
        if (perceptionService != null) {
            perceptionService.shutdown();
            perceptionService = null;
        }
        if (dashboardManager != null) {
            dashboardManager.shutdown();
            dashboardManager = null;
        }
        if (touchSensorManager != null) {
            touchSensorManager.shutdown();
            touchSensorManager = null;
        }
        if (navigationServiceManager != null) {
            navigationServiceManager.shutdown();
            navigationServiceManager = null;
        }
    }
    
    // Package-private methods for Controllers/Helpers access
    public void failConnectionPromise(String message) {
        if (connectionCallback != null) {
            connectionCallback.onError(new Exception(message));
            connectionCallback = null;
        }
    }

    public void completeConnectionPromise() {
        if (connectionCallback != null) {
            connectionCallback.onSuccess();
            connectionCallback = null;
        }
    }
    
    private void cleanupServices() {
        try {
            if (audioPlayer != null) audioPlayer.setListener(null);
            if (sessionManager != null) sessionManager.setListener(null);
            visionService = null;
            if (settingsManager != null) settingsManager.setListener(null);
            if (toolContext != null) toolContext.updateQiContext(null);
            eventHandler = null;
            Log.i(TAG, "Services cleaned up successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error during service cleanup", e);
        }
    }

    private void handleRobotReady(Object robotContext) {
        if (locationProvider != null) {
            locationProvider.refreshLocations(this);
        }

        runOnUiThread(() -> {
            boolean hasMap = new java.io.File(getFilesDir(), "maps/default_map.map").exists();
            updateNavigationStatus(
                getString(hasMap ? R.string.nav_map_saved : R.string.nav_map_none),
                getString(R.string.nav_localization_not_running)
            );
        });
        
        audioInputController.cleanupSttForReinit();
        
        if (toolContext != null) toolContext.updateQiContext(robotContext);
        
        if (perceptionService == null) {
            perceptionService = new PerceptionService();
        }
        if (dashboardManager == null) {
            View dashboardOverlay = findViewById(R.id.dashboard_overlay);
            if (dashboardOverlay != null) {
                dashboardManager = new DashboardManager(this, dashboardOverlay);
                dashboardManager.initialize(perceptionService);
                chatMenuController = new ChatMenuController(this, drawerLayout, mapUiManager, dashboardManager, settingsManager);
                chatMenuController.setListener(() -> sessionController.startNewSession());
            }
        }
        
        if (perceptionService != null) {
            perceptionService.initialize(robotContext);
        }
        
        if (visionService == null) {
            visionService = new VisionService(this);
        }
        visionService.initialize(robotContext);
        
        if (touchSensorManager == null) {
            touchSensorManager = new TouchSensorManager();
        }
        touchSensorManager.setListener(new TouchSensorManager.TouchEventListener() {
            @Override
            public void onSensorTouched(String sensorName, Object touchState) {
                Log.i(TAG, "Touch sensor " + sensorName + " touched");
                String touchMessage = TouchSensorManager.createTouchMessage(sensorName);
                runOnUiThread(() -> {
                    addMessage(touchMessage, ChatMessage.Sender.USER);
                    sendMessageToRealtimeAPI(touchMessage, true, true);
                });
            }
            @Override
            public void onSensorReleased(String sensorName, Object touchState) {}
        });
        touchSensorManager.initialize(robotContext);
        
        if (navigationServiceManager != null) {
            navigationServiceManager.setDependencies(perceptionService, touchSensorManager, gestureController);
        }

        if (!hasFocusInitialized) {
            hasFocusInitialized = true;
            isWarmingUp = true;
            lastChatBubbleResponseId = null;
            applyVolume(settingsManager.getVolume());
            showWarmupIndicator();
            
            Log.i(TAG, "Starting WebSocket connection...");
            sessionController.connectWebSocket(new WebSocketConnectionCallback() {
                @Override
                public void onSuccess() {
                    Log.i(TAG, "WebSocket connected successfully, starting STT warmup...");
                    audioInputController.startWarmup();
                    threadManager.executeAudio(() -> {
                        try {
                            audioInputController.setupSpeechRecognizer();
                            if (settingsManager.isUsingRealtimeAudioInput()) {
                                runOnUiThread(() -> {
                                    hideWarmupIndicator();
                                    isWarmingUp = false;
                                    statusTextView.setText(getString(R.string.status_listening));
                                    if (turnManager != null) {
                                        turnManager.setState(TurnManager.State.LISTENING);
                                    }
                                });
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "❌ STT setup failed", e);
                            runOnUiThread(() -> {
                                hideWarmupIndicator();
                                isWarmingUp = false;
                                addMessage(getString(R.string.warmup_failed_msg), ChatMessage.Sender.ROBOT);
                                statusTextView.setText(getString(R.string.ready_sr_lazy_init));
                                if (turnManager != null) turnManager.setState(TurnManager.State.LISTENING);
                            });
                        }
                    });
                }
                @Override
                public void onError(Throwable error) {
                    Log.e(TAG, "WebSocket connection failed", error);
                    hideWarmupIndicator();
                    isWarmingUp = false;
                    runOnUiThread(() -> {
                        addMessage(getString(R.string.setup_error_during, error.getMessage()), ChatMessage.Sender.ROBOT);
                        statusTextView.setText(getString(R.string.error_connection_failed));
                    });
                }
            });
        }
    }

    private void handleRobotFocusLost() {
        audioInputController.setSttRunning(false);
        audioInputController.cleanupSttForReinit(); // Force stop
        
        if (toolContext != null) toolContext.updateQiContext(null);
        shutdownManagers();
        try {
            gestureController.shutdown();
        } catch (Exception e) {
            Log.w(TAG, "Error during GestureController shutdown", e);
        }
        
        runOnUiThread(() -> {
            statusTextView.setText(getString(R.string.robot_focus_lost_message));
            boolean hasMap = new java.io.File(getFilesDir(), "maps/default_map.map").exists();
            updateNavigationStatus(getString(hasMap ? R.string.nav_map_saved : R.string.nav_map_none),
                                 getString(R.string.nav_localization_stopped));
        });
    }
    
    private void applyVolume(int percentage) {
        try {
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                double volume = percentage / 100.0;
                int targetVol = Math.max(0, Math.min(maxVol, (int) Math.round(volume * maxVol)));
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0);
            }
        } catch (Exception e) {
            Log.w(TAG, "Setting media volume failed", e);
        }
    }
    
    private void setupRealtimeEventHandlerIfNeeded() {
        if (eventHandler == null) {
            eventHandler = new RealtimeEventHandler(
                new ChatRealtimeHandler(this, audioPlayer, turnManager, statusTextView, threadManager, toolRegistry, toolContext)
            );
            audioInputController.setSpeechListener(new ChatSpeechListener(this, turnManager, statusTextView, audioInputController.getSttWarmupStartTime()));
        }
    }

    public void startContinuousRecognition() {
        audioInputController.startContinuousRecognition();
    }

    public void stopContinuousRecognition() {
        audioInputController.stopContinuousRecognition();
    }

    public void sendMessageToRealtimeAPI(String text, boolean requestResponse, boolean allowInterrupt) {
        if (sessionManager == null || !sessionManager.isConnected()) {
            Log.e(TAG, "WebSocket is not connected.");
            if (requestResponse) {
                runOnUiThread(() -> addMessage(getString(R.string.error_not_connected), ChatMessage.Sender.ROBOT));
            }
            return;
        }

        if (allowInterrupt && requestResponse && turnManager != null && turnManager.getState() == TurnManager.State.SPEAKING) {
            runOnUiThread(() -> {
                // Simple explicit interrupt via controller if playing
                if (isAudioPlaying || audioPlayer.isPlaying()) {
                    interruptController.interruptSpeech();
                }
            });
        }

        if (requestResponse && turnManager != null) {
            runOnUiThread(() -> turnManager.setState(TurnManager.State.THINKING));
        }

        threadManager.executeNetwork(() -> {
            try {
                boolean sentItem = sessionManager.sendUserTextMessage(text);
                if (!sentItem) {
                    // We still use handleWebSocketSendError for consistent UI feedback, but defined locally or in helper?
                    // Let's re-add a local helper for simple error messages or delegate to UI helper
                    Log.e(TAG, "Failed to send message - WebSocket connection broken");
                    runOnUiThread(() -> {
                        addMessage("Connection lost during message. Please restart the app.", ChatMessage.Sender.ROBOT);
                        if (turnManager != null) turnManager.setState(TurnManager.State.IDLE);
                    });
                    return;
                }

                if (requestResponse) {
                    if (isResponseGenerating) {
                        interruptController.interruptSpeech();
                        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                    }
                    else if (isAudioPlaying && allowInterrupt) {
                        if (audioPlayer != null) {
                            audioPlayer.interruptNow();
                            isAudioPlaying = false;
                        }
                    }
                    
                    isResponseGenerating = true;
                    boolean sentResponse = sessionManager.requestResponse();
                    if (!sentResponse) {
                        isResponseGenerating = false;
                        Log.e(TAG, "Failed to send response request");
                        runOnUiThread(() -> addMessage("Connection lost during response request.", ChatMessage.Sender.ROBOT));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception in sendMessageToRealtimeAPI", e);
                if (requestResponse) {
                    isResponseGenerating = false;
                    runOnUiThread(() -> {
                        addMessage(getString(R.string.error_processing_message), ChatMessage.Sender.ROBOT);
                        if (turnManager != null) turnManager.setState(TurnManager.State.IDLE);
                    });
                }
            }
        });
    }

    public void sendToolResult(String callId, String result) {
        if (sessionManager == null || !sessionManager.isConnected()) return;
        try {
            expectingFinalAnswerAfterToolCall = true;
            boolean sentTool = sessionManager.sendToolResult(callId, result);
            if (!sentTool) {
                Log.e(TAG, "Failed to send tool result");
                return;
            }
            isResponseGenerating = true;
            boolean sentToolResponse = sessionManager.requestResponse();
            if (!sentToolResponse) {
                isResponseGenerating = false;
                Log.e(TAG, "Failed to send tool response request");
                return;
            }
            if (turnManager != null && turnManager.getState() != TurnManager.State.SPEAKING) {
                turnManager.setState(TurnManager.State.THINKING);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending tool result", e);
            isResponseGenerating = false;
        }
    }

    private void releaseAudioTrack() {
        if (audioPlayer != null) audioPlayer.release();
        try { gestureController.shutdown(); } catch (Exception ignored) {}
    }

    public void startExplainGesturesLoop() {
        if (robotFocusManager.getQiContext() == null) return;
        if (navigationServiceManager != null && navigationServiceManager.areGesturesSuppressed()) return;
        
        gestureController.start(robotFocusManager.getQiContext(),
                () -> turnManager != null && turnManager.getState() == TurnManager.State.SPEAKING && robotFocusManager.getQiContext() != null && 
                      (navigationServiceManager == null || !navigationServiceManager.areGesturesSuppressed()),
                this::getRandomExplainAnimationResId);
    }

    private Integer getRandomExplainAnimationResId() {
        int[] ids = new int[] {
                R.raw.explain_01, R.raw.explain_02, R.raw.explain_03, R.raw.explain_04,
                R.raw.explain_05, R.raw.explain_06, R.raw.explain_07, R.raw.explain_08,
                R.raw.explain_09, R.raw.explain_10, R.raw.explain_11
        };
        return ids[new java.util.Random().nextInt(ids.length)];
    }

    public void addMessage(String text, ChatMessage.Sender sender) {
        uiHelper.addMessage(text, sender);
    }

    public void addFunctionCall(String functionName, String args) {
        uiHelper.addFunctionCall(functionName, args);
    }

    public void updateFunctionCallResult(String result) {
        uiHelper.updateFunctionCallResult(result);
    }
    
    public void deleteSessionImages() {
        synchronized (sessionImagePaths) {
            for (String p : sessionImagePaths) {
                try {
                    if (p != null) new java.io.File(p).delete();
                } catch (Exception ignored) {}
            }
            sessionImagePaths.clear();
        }
    }
    
    private void showWarmupIndicator() {
        runOnUiThread(() -> {
            warmupIndicatorLayout.setVisibility(View.VISIBLE);
            statusTextView.setText(getString(R.string.status_warming_up));
        });
    }

    public void hideWarmupIndicator() {
        runOnUiThread(() -> warmupIndicatorLayout.setVisibility(View.GONE));
    }

    private void mute() {
        audioInputController.mute();
    }
    
    private void unmute() {
        audioInputController.unmute();
    }
    
    public void handleServiceStateChange(String mode) {
        if (navigationServiceManager != null) {
            navigationServiceManager.handleServiceStateChange(mode);
        }
    }

    public void addImageMessage(String imagePath) {
        uiHelper.addImageMessage(imagePath);
    }
    
    public void addImageToSessionCleanup(String imagePath) {
        synchronized (sessionImagePaths) {
            sessionImagePaths.add(imagePath);
        }
    }
    
    public void updateNavigationStatus(String mapStatus, String localizationStatus) {
        mapUiManager.updateMapStatus(mapStatus);
        mapUiManager.updateLocalizationStatus(localizationStatus);
        mapUiManager.updateMapPreview(navigationServiceManager, locationProvider);
    }

    public void updateMapPreview() {
        mapUiManager.updateMapPreview(navigationServiceManager, locationProvider);
    }
    
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

    public Object getQiContext() { return robotFocusManager.getQiContext(); }
    public boolean isMuted() { return audioInputController.isMuted(); }
    public boolean isSttRunning() { return audioInputController.isSttRunning(); }
    public void setSttRunning(boolean running) { audioInputController.setSttRunning(running); }
    public boolean isWarmingUp() { return isWarmingUp; }
    public void setWarmingUp(boolean warmingUp) { this.isWarmingUp = warmingUp; }
    
    public boolean isResponseGenerating() { return isResponseGenerating; }
    public void setResponseGenerating(boolean generating) { this.isResponseGenerating = generating; }
    
    public boolean isAudioPlaying() { return isAudioPlaying; }
    public void setAudioPlaying(boolean playing) { this.isAudioPlaying = playing; }
    
    public String getCurrentResponseId() { return currentResponseId; }
    public void setCurrentResponseId(String id) { this.currentResponseId = id; }
    
    public void setCancelledResponseId(String id) { this.cancelledResponseId = id; }
    public String getCancelledResponseId() { return cancelledResponseId; }
    
    public String getLastChatBubbleResponseId() { return lastChatBubbleResponseId; }
    public void setLastChatBubbleResponseId(String id) { this.lastChatBubbleResponseId = id; }
    
    public boolean isExpectingFinalAnswerAfterToolCall() { return expectingFinalAnswerAfterToolCall; }
    public void setExpectingFinalAnswerAfterToolCall(boolean expecting) { this.expectingFinalAnswerAfterToolCall = expecting; }
    
    public void setLastAssistantItemId(String id) { this.lastAssistantItemId = id; }
    public String getLastAssistantItemId() { return lastAssistantItemId; }
    
    public boolean isMessageListEmpty() { return messageList.isEmpty(); }
    public boolean isLastMessageFromRobot() { return !messageList.isEmpty() && messageList.get(messageList.size() - 1).getSender() == ChatMessage.Sender.ROBOT; }
    
    public void appendToLastMessage(String text) {
        if (!messageList.isEmpty()) {
            ChatMessage last = messageList.get(messageList.size() - 1);
            last.setMessage(last.getMessage() + text);
            chatAdapter.notifyItemChanged(messageList.size() - 1);
        }
    }

    public void handleUserSpeechStopped(String itemId) {
        uiHelper.handleUserSpeechStopped(itemId);
    }
    
    public void handleUserTranscriptCompleted(String itemId, String transcript) {
        uiHelper.handleUserTranscriptCompleted(itemId, transcript);
    }
    
    public void handleUserTranscriptFailed(String itemId, JSONObject error) {
        uiHelper.handleUserTranscriptFailed(itemId, error);
    }
    
    public void setStatusText(String text) {
        uiHelper.setStatusText(text);
    }
    
    public void clearMessages() {
        uiHelper.clearMessages();
    }
    
    public void setConnectionCallback(WebSocketConnectionCallback callback) {
        this.connectionCallback = callback;
    }
}
