package io.github.studerus.pepper_android_realtime;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.aldebaran.qi.Future;
import com.aldebaran.qi.Promise;
import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.QiSDK;
import com.aldebaran.qi.sdk.RobotLifecycleCallbacks;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.appbar.MaterialToolbar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import okhttp3.Response;
import android.media.AudioManager;
import android.content.Context;
import android.view.Window;
import android.annotation.SuppressLint;
import android.widget.LinearLayout;

// New tool system imports
import io.github.studerus.pepper_android_realtime.tools.ToolRegistryNew;
import io.github.studerus.pepper_android_realtime.tools.ToolContext;
import io.github.studerus.pepper_android_realtime.ui.MapPreviewView;
import io.github.studerus.pepper_android_realtime.ui.MapState;
import io.github.studerus.pepper_android_realtime.data.LocationProvider;
import android.widget.FrameLayout;

public class ChatActivity extends AppCompatActivity implements RobotLifecycleCallbacks {

    private static final String TAG = "ChatActivity";

    private static final int MICROPHONE_PERMISSION_REQUEST_CODE = 2;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 3;

    private QiContext qiContext;
    private DrawerLayout drawerLayout;
    private TextView statusTextView;
    private TextView mapStatusTextView;
    private TextView localizationStatusTextView;
    private RecyclerView chatRecyclerView;
    private ChatMessageAdapter chatAdapter;
    private final List<ChatMessage> messageList = new ArrayList<>();
    private LinearLayout warmupIndicatorLayout;

    // Map Preview UI
    private MapPreviewView mapPreviewView;
    private FrameLayout mapPreviewContainer;
    private LocationProvider locationProvider;

    private FloatingActionButton fabInterrupt;
    private ApiKeyManager keyManager;
    private SpeechRecognizerManager sttManager;
    // Optimized threading - using specialized thread pools instead of single executors
    private final OptimizedThreadManager threadManager = OptimizedThreadManager.getInstance();

    // WebSocket and audio playback
    private RealtimeSessionManager sessionManager;
    private RealtimeEventHandler eventHandler;
    private TurnManager turnManager;
    private String lastAssistantItemId = null;
    private final List<String> sessionImagePaths = new ArrayList<>();
    private OptimizedAudioPlayer audioPlayer;
    private volatile long lastAudioDoneTs = 0L; // monotonic timestamp of last audio completion
    
    // Game dialogs - TicTacToe moved to TicTacToeGameManager
    private volatile boolean hasActiveResponse = false;
    private volatile String currentResponseId = null;
    private volatile String cancelledResponseId = null;
    private volatile String lastChatBubbleResponseId = null;
    private Promise<Void> connectionPromise;
    private volatile boolean expectingFinalAnswerAfterToolCall = false;
    private final GestureController gestureController = new GestureController();
    private volatile boolean isWarmingUp = false;
    
    // Mute state management for pause/resume functionality
    private volatile boolean isMuted = false;
    private volatile boolean sttIsRunning = false;
    private volatile boolean robotFocusAvailable = false;

    private VisionService visionService;

    private ToolRegistryNew toolRegistry;
    private ToolContext toolContext;
    private boolean hasFocusInitialized = false;

    // Settings UI
    private SettingsManager settingsManager;



    // Perception Dashboard
    private PerceptionService perceptionService;
    private DashboardManager dashboardManager;
    
    // Touch Sensor Management
    private TouchSensorManager touchSensorManager;

    // Navigation Service Management
    private NavigationServiceManager navigationServiceManager;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_chat);

        keyManager = new ApiKeyManager(this);

        drawerLayout = findViewById(R.id.drawer_layout);
        statusTextView = findViewById(R.id.statusTextView);
        mapStatusTextView = findViewById(R.id.mapStatusTextView);
        localizationStatusTextView = findViewById(R.id.localizationStatusTextView);
        chatRecyclerView = findViewById(R.id.chatRecyclerView);
        warmupIndicatorLayout = findViewById(R.id.warmup_indicator_layout);

        // Map Preview UI
        mapPreviewContainer = findViewById(R.id.map_preview_container);
        mapPreviewView = findViewById(R.id.map_preview_view);
        locationProvider = new LocationProvider();

        MaterialToolbar topAppBar = findViewById(R.id.topAppBar);
        setSupportActionBar(topAppBar);


        chatAdapter = new ChatMessageAdapter(messageList);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        chatRecyclerView.setLayoutManager(layoutManager);
        chatRecyclerView.setAdapter(chatAdapter);

        fabInterrupt = findViewById(R.id.fab_interrupt);
        fabInterrupt.setOnClickListener(v -> {
            try {
                interruptSpeech();
                if (turnManager != null) turnManager.setState(TurnManager.State.LISTENING);
            } catch (Exception e) {
                Log.e(TAG, "Interrupt failed", e);
            }
        });

        // Tap-to-interrupt/mute/un-mute on status bar
        statusTextView.setOnClickListener(v -> {
            try {
                if (turnManager == null) {
                    Log.w(TAG, "Status bar clicked but turnManager is null");
                    return;
                }
                
                TurnManager.State currentState = turnManager.getState();
                Log.i(TAG, "Status bar clicked - Current state: " + currentState + ", isMuted: " + isMuted);
                
                if (currentState == TurnManager.State.SPEAKING) {
                    // Robot is speaking -> interrupt and mute
                    if (hasActiveResponse || (audioPlayer != null && audioPlayer.isPlaying())) {
                        Log.i(TAG, "Interrupting and muting...");
                        interruptAndMute();
                    }
                } else if (isMuted) {
                    // Currently muted -> un-mute and start listening
                    Log.i(TAG, "Un-muting...");
                    unmute();
                } else if (currentState == TurnManager.State.LISTENING) {
                    // Currently listening -> mute
                    Log.i(TAG, "Muting...");
                    mute();
                }
            } catch (Exception e) {
                Log.e(TAG, "Status bar click handler failed", e);
            }
        });

        // Ensure realtime session manager is ready
        if (sessionManager == null) {
            Log.i(TAG, "Creating NEW RealtimeSessionManager in onCreate");
            sessionManager = new RealtimeSessionManager();
            
            // Session dependencies will be set after tool system is initialized
            
            // Set session configuration callback
            sessionManager.setSessionConfigCallback((success, error) -> {
                if (success) {
                    Log.i(TAG, "Session configured successfully - completing connection promise");
                    completeConnectionPromise();
                } else {
                    Log.e(TAG, "Session configuration failed: " + error);
                }
            });
            
            sessionManager.setListener(createSessionManagerListener());
        }

        // WebSocket client initialization handled by RealtimeSessionManager

        setupSettingsMenu();

        audioPlayer = new OptimizedAudioPlayer();
        turnManager = new TurnManager(new TurnManager.Callbacks() {
            @Override public void onEnterListening() { 
                runOnUiThread(() -> { 
                    try {
                        // Abort if robot focus lost during callback
                        if (qiContext == null) {
                            Log.w(TAG, "Robot focus lost, aborting onEnterListening to prevent crashes");
                            return;
                        }
                    if (!isMuted) {
                        statusTextView.setText(getString(R.string.status_listening)); 
                            if (!sttIsRunning) {
                                try {
                        startContinuousRecognition(); 
                                } catch (Exception e) {
                                    Log.e(TAG, "Failed to start recognition in onEnterListening", e);
                                    statusTextView.setText(getString(R.string.status_recognizer_not_ready));
                                }
                            }
                    }
                    findViewById(R.id.fab_interrupt).setVisibility(View.GONE); 
                    } catch (Exception e) {
                        Log.e(TAG, "Exception in onEnterListening UI thread", e);
                    }
                }); 
            }
            @Override public void onEnterThinking() {
                // Physically stop the mic so nothing is recognized during THINKING (only if running)
                if (sttIsRunning) {
                stopContinuousRecognition();
                } else {
                    Log.d(TAG, "STT already stopped, skipping redundant stop in THINKING state");
                }
                runOnUiThread(() -> { 
                    statusTextView.setText(getString(R.string.status_thinking)); 
                    findViewById(R.id.fab_interrupt).setVisibility(View.GONE); 
                });
            }
            @Override public void onEnterSpeaking() { 
                Log.i(TAG, "State: Entering SPEAKING - starting gestures and stopping STT");
                stopContinuousRecognition(); 
                startExplainGesturesLoop(); 
                runOnUiThread(() -> findViewById(R.id.fab_interrupt).setVisibility(View.GONE));
            }
            @Override public void onExitSpeaking() { 
                Log.i(TAG, "State: Exiting SPEAKING - stopping gestures and starting STT");
                gestureController.stopNow(); 
                runOnUiThread(() -> { 
                    if (!isMuted) { 
                        statusTextView.setText(getString(R.string.status_listening)); 
                    } 
                    findViewById(R.id.fab_interrupt).setVisibility(View.GONE); 
                }); 
            }
        });
        // Initialize new tool system
        ApiKeyManager keyManager = new ApiKeyManager(this);
        MovementController movementController = new MovementController();
        
        // Initialize navigation service manager
        navigationServiceManager = new NavigationServiceManager(movementController);
        navigationServiceManager.setListener(new NavigationServiceManager.NavigationServiceListener() {
            @Override
            public void onNavigationPhaseChanged(NavigationServiceManager.NavigationPhase phase) {
                Log.i(TAG, "Navigation phase changed to: " + phase);
                // Handle phase changes if needed
                updateMapPreview();
            }
            
            @Override
            public void onNavigationStatusUpdate(String mapStatus, String localizationStatus) {
                updateNavigationStatus(mapStatus, localizationStatus);
                updateMapPreview();
            }
        });
        
        // Create tool context with all dependencies - direct ChatActivity reference for simplicity
        toolContext = new ToolContext(this, qiContext, this, keyManager, movementController, locationProvider);
        
        // Initialize tool registry  
        toolRegistry = new ToolRegistryNew();
        
        // Now set session dependencies after tool system is initialized
        if (sessionManager != null) {
            sessionManager.setSessionDependencies(toolRegistry, toolContext, settingsManager, keyManager);
            Log.i(TAG, "Session dependencies set for RealtimeSessionManager");
        }
        
        Log.i(TAG, "New tool system initialized with " + toolRegistry.getAllToolNames().size() + " tools");
        

        audioPlayer.setListener(new OptimizedAudioPlayer.Listener() {
            @Override public void onPlaybackStarted() {
                turnManager.setState(TurnManager.State.SPEAKING);
            }
            @Override public void onPlaybackFinished() {
                // Avoid reopening the mic if a follow-up response is pending or already streaming
                lastAudioDoneTs = android.os.SystemClock.uptimeMillis();
                try {
                    boolean playing = (audioPlayer != null && audioPlayer.isPlaying());
                    Log.d(TAG, "🚨 DIAGNOSTIC: onPlaybackFinished: playing=" + playing + 
                            ", hasActiveResponse=" + hasActiveResponse + 
                            ", lastAudioDoneTs=" + lastAudioDoneTs);
                } catch (Exception ignored) {}
                if (!expectingFinalAnswerAfterToolCall && !hasActiveResponse) {
                    turnManager.setState(TurnManager.State.LISTENING);
                } else {
                    // Keep THINKING until the next response's audio starts
                    turnManager.setState(TurnManager.State.THINKING);
                }
            }
        });

        // Dashboard will be initialized when robot focus is gained

        // Register for robot lifecycle callbacks with diagnostic logging
        Log.i(TAG, "🤖 DIAGNOSTIC: Registering with QiSDK for robot lifecycle callbacks...");
        try {
        QiSDK.register(this, this);
            Log.i(TAG, "🤖 DIAGNOSTIC: QiSDK.register() completed successfully - waiting for robot focus...");
            
            // Set a diagnostic timeout to detect if QiSDK never responds
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!robotFocusAvailable) {
                    Log.e(TAG, "🤖 DIAGNOSTIC: TIMEOUT - No robot focus response after 30 seconds!");
                    Log.e(TAG, "🤖 DIAGNOSTIC: This suggests QiSDK service issues or robot state problems");
                    Log.e(TAG, "🤖 DIAGNOSTIC: Check: 1) Robot is awake, 2) Robot is enabled, 3) QiSDK service is running");
                    runOnUiThread(() -> statusTextView.setText(getString(R.string.robot_initialization_timeout)));
                }
            }, 30000); // 30 second timeout
            
        } catch (Exception e) {
            Log.e(TAG, "🤖 DIAGNOSTIC: QiSDK.register() failed", e);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_PERMISSION_REQUEST_CODE);
        } else {
            // Speech recognizer will be initialized during warmup to avoid double initialization
            Log.i(TAG, "Microphone permission available - STT will be initialized during warmup");
        }

        // Request camera permission for vision analysis
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

    /**
     * Update the map status display in the navigation bar
     */
    private void updateMapStatus(String status) {
        runOnUiThread(() -> mapStatusTextView.setText(status));
    }

    /**
     * Update the localization status display in the navigation bar
     */
    private void updateLocalizationStatus(String status) {
        runOnUiThread(() -> localizationStatusTextView.setText(status));
    }

    @Override
    protected void onDestroy() {
        if (sttManager != null) {
            sttManager.shutdown();
        }
        
        // Shutdown optimized thread manager
        threadManager.shutdown();
        
        // Clean up services and prevent memory leaks
        cleanupServices();
        
        // Clean up network resources
        OptimizedHttpClientManager.getInstance().shutdown();
        
        // Clean up any session images
        deleteSessionImages();
        disconnectWebSocket(); // Disconnect WebSocket connection
        releaseAudioTrack(); // Release AudioTrack
        
        shutdownManagers();
        
        QiSDK.unregister(this, this);
        super.onDestroy();
    }
    
    /**
     * Shuts down all core managers and services to prevent resource leaks.
     * This is used during focus loss and when the activity is destroyed.
     */
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
        if (sttManager != null) {
            sttManager.shutdown();
            sttManager = null;
        }
        if (navigationServiceManager != null) {
            navigationServiceManager.shutdown();
            navigationServiceManager = null;
        }
    }
    
    private void failConnectionPromise(String message) {
        if (connectionPromise != null && !connectionPromise.getFuture().isDone()) {
            connectionPromise.setError(message);
            connectionPromise = null;
        }
    }

    private void completeConnectionPromise() {
        if (connectionPromise != null && !connectionPromise.getFuture().isDone()) {
            connectionPromise.setValue(null);
            connectionPromise = null;
        }
    }
    
    private void handleWebSocketSendError(String context) {
        Log.e(TAG, "Failed to send " + context + " - WebSocket connection broken");
        runOnUiThread(() -> {
            addMessage("Connection lost during " + context + ". Please restart the app.", ChatMessage.Sender.ROBOT);
            if (turnManager != null) turnManager.setState(TurnManager.State.IDLE);
        });
    }
    
    /**
     * Clean up services and listeners to prevent memory leaks
     */
    private void cleanupServices() {
        try {
            // Clear optimized audio player listener to prevent callback leaks
            if (audioPlayer != null) {
                audioPlayer.setListener(null);
            }
            
            // Clear session manager listener
            if (sessionManager != null) {
                sessionManager.setListener(null);
            }
            
            // Clear STT manager listener
            if (sttManager != null) {
                sttManager.setCallbacks(null);
            }
            
            // Clear vision service references
            visionService = null;
            
            // Clear settings manager listener
            if (settingsManager != null) {
                settingsManager.setListener(null);
            }
            
            // Clear tool executor reference
            if (toolContext != null) {
                toolContext.updateQiContext(null);
            }
            
            // Clear event handler
            eventHandler = null;
            

            
            Log.i(TAG, "Services cleaned up successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error during service cleanup", e);
        }
    }


    @Override
    public void onRobotFocusGained(QiContext qiContext) {
        Log.i(TAG, "🤖 DIAGNOSTIC: onRobotFocusGained() called - robot focus acquired!");
        
        this.qiContext = qiContext;
        
        // Set flag to indicate robot focus is available
        robotFocusAvailable = true;
        
        Log.i(TAG, "Robot focus gained - initializing services (qiContext available: " + (qiContext != null) + ")");

        // Refresh locations for map preview
        if (locationProvider != null) {
            locationProvider.refreshLocations(this);
        }

        // Reflect actual state on focus gain without auto-loading map/localize
        runOnUiThread(() -> {
            boolean hasMap = new java.io.File(getFilesDir(), "maps/default_map.map").exists();
            updateNavigationStatus(
                getString(hasMap ? R.string.nav_map_saved : R.string.nav_map_none),
                getString(R.string.nav_localization_not_running)
            );
        });
        
        // Robust STT cleanup before reinitialization to prevent conflicts from previous crashes
        if (sttManager != null) {
            try {
                Log.i(TAG, "Cleaning up existing STT manager before reinitialization");
                sttManager.shutdown();
                Thread.sleep(150); // Brief pause for resource cleanup
            } catch (Exception e) {
                Log.w(TAG, "Error during STT cleanup", e);
            }
            sttManager = null;
        }
        
        // Setup new tool system (no heavy operations)
        if (toolContext != null) toolContext.updateQiContext(qiContext);
        
        // Initialize dashboard and perception service with QiContext
        if (perceptionService == null) {
            perceptionService = new PerceptionService();
        }
        if (dashboardManager == null) {
            View dashboardOverlay = findViewById(R.id.dashboard_overlay);
            if (dashboardOverlay != null) {
                dashboardManager = new DashboardManager(this, dashboardOverlay);
                dashboardManager.initialize(perceptionService);
                Log.i(TAG, "Dashboard initialized successfully");
            } else {
                Log.e(TAG, "Dashboard overlay not found in layout");
            }
        }
        
        if (perceptionService != null) {
            perceptionService.initialize(qiContext);
            // Note: Monitoring will be started by DashboardManager when needed
            Log.i(TAG, "PerceptionService initialized");
        }
        
        // Initialize touch sensor manager with QiContext
        if (touchSensorManager == null) {
            touchSensorManager = new TouchSensorManager();
        }
        touchSensorManager.setListener(new TouchSensorManager.TouchEventListener() {
            @Override
            public void onSensorTouched(String sensorName, com.aldebaran.qi.sdk.object.touch.TouchState touchState) {
                Log.i(TAG, "Touch sensor " + sensorName + " touched - sending to AI and requesting response");
                
                // Create human-readable touch message
                String touchMessage = TouchSensorManager.createTouchMessage(sensorName);
                
                runOnUiThread(() -> {
                    // Add touch message to chat
                    addMessage(touchMessage, ChatMessage.Sender.USER);
                    // Send to Realtime API with response request and allow interrupt
                    sendMessageToRealtimeAPI(touchMessage, true, true);
                });
            }
            
            @Override
            public void onSensorReleased(String sensorName, com.aldebaran.qi.sdk.object.touch.TouchState touchState) {
                // Touch release - no action needed
            }
        });
            touchSensorManager.initialize(qiContext);
        Log.i(TAG, "TouchSensorManager initialized with QiContext and listener");
        
        // Set dependencies for navigation service manager
        if (navigationServiceManager != null) {
            navigationServiceManager.setDependencies(perceptionService, touchSensorManager, gestureController);
        }
        // Only perform initialization on first focus gain
        if (!hasFocusInitialized) {
            hasFocusInitialized = true;
            isWarmingUp = true;
            isMuted = false; // Reset mute state on initial startup
            lastChatBubbleResponseId = null; // Reset response tracking

            // Set initial volume from settings
            applyVolume(settingsManager.getVolume());

            // Show warmup indicator before starting async operations
            showWarmupIndicator();

            // Asynchronous initialization
            Future<Void> connectFuture = connectWebSocket();
            
            // Wait for WebSocket connection first, then start STT warmup sequentially
            Log.i(TAG, "Waiting for WebSocket connection to complete...");
            connectFuture.thenConsume(wsFuture -> {
                Log.i(TAG, "WebSocket Future completed - checking result...");
                if (wsFuture.hasError()) {
                    Log.e(TAG, "WebSocket connection failed", wsFuture.getError());
                    hideWarmupIndicator();
                    isWarmingUp = false;
                    runOnUiThread(() -> {
                        addMessage(getString(R.string.setup_error_during, wsFuture.getError().getMessage()), ChatMessage.Sender.ROBOT);
                        statusTextView.setText(getString(R.string.error_connection_failed));
                    });
                    return;
                }
                
                Log.i(TAG, "WebSocket connected successfully, starting STT warmup...");
                
                // Direct STT setup using SpeechRecognizerManager
                Log.i(TAG, "Executing STT setup on audio thread...");
                threadManager.executeAudio(() -> {
                    try {
                        setupSpeechRecognizer();
                        
                        runOnUiThread(() -> {
                            // Hide warmup indicator after setup
                hideWarmupIndicator();
                isWarmingUp = false;

                            Log.i(TAG, "STT setup complete. Entering LISTENING state.");
                            if (turnManager != null) {
                                statusTextView.setText(getString(R.string.status_listening));
                                turnManager.setState(TurnManager.State.LISTENING);
                            }
                        });
                        
                    } catch (Exception e) {
                        Log.e(TAG, "STT setup failed", e);
                        runOnUiThread(() -> {
                            hideWarmupIndicator();
                            isWarmingUp = false;
                            addMessage(getString(R.string.warmup_failed_msg), ChatMessage.Sender.ROBOT);
                            statusTextView.setText(getString(R.string.ready_sr_lazy_init));
                            
                    // Enter LISTENING state anyway - lazy init will handle STT when needed
                        if (turnManager != null) turnManager.setState(TurnManager.State.LISTENING);
                    });
                    }
                });
                
                // Remove the old warmupFuture.thenConsume block
                // This block is replaced by direct STT setup above
            });
        }
    }

    @Override
    public void onRobotFocusLost() {
        Log.w(TAG, "🤖 DIAGNOSTIC: onRobotFocusLost() called - robot focus lost!");
        Log.w(TAG, "Robot focus lost during startup!");
        
        // CRITICAL: Set focus lost flag to stop all robot operations
        robotFocusAvailable = false;
        
        // CRITICAL: Reset STT flag to prevent double start when focus is regained
        sttIsRunning = false;
        
        // Stop any ongoing STT to prevent crashes
        if (sttManager != null) {
            try {
                sttManager.stop();
                Log.i(TAG, "Stopped STT due to focus loss");
            } catch (Exception e) {
                Log.w(TAG, "Error stopping STT during focus loss", e);
            }
        }
        
        if (toolContext != null) toolContext.updateQiContext(null);
        
        // NOTE: Do NOT shutdown threadManager here - QiSDK needs it for next onRobotFocusGained callback
        // ThreadManager is only shutdown in onDestroy when app completely terminates
        
        shutdownManagers();
        
        // Clean up gesture controller to prevent broken gestures on restart
        try {
            gestureController.shutdown();
            Log.i(TAG, "GestureController shutdown completed");
        } catch (Exception e) {
            Log.w(TAG, "Error during GestureController shutdown", e);
        }
        
        // STT cleanup handled by sttManager
        this.qiContext = null;
        
        // CRITICAL: App should not crash due to focus loss - try to gracefully recover
        runOnUiThread(() -> {
            statusTextView.setText(getString(R.string.robot_focus_lost_message));
            boolean hasMap = new java.io.File(getFilesDir(), "maps/default_map.map").exists();
            updateMapStatus(getString(hasMap ? R.string.nav_map_saved : R.string.nav_map_none));
            updateLocalizationStatus(getString(R.string.nav_localization_stopped));
        });
    }

    @Override
    public void onRobotFocusRefused(String reason) {
        Log.e(TAG, "🤖 DIAGNOSTIC: onRobotFocusRefused() called - Focus denied!");
        Log.e(TAG, "🤖 DIAGNOSTIC: Focus refusal reason: " + reason);
        
        // Common reasons and troubleshooting info
        if (reason != null) {
            if (reason.contains("sleep") || reason.contains("Sleep")) {
                Log.w(TAG, "🤖 DIAGNOSTIC: Robot is in Sleep Mode - double-tap chest button to wake up");
            } else if (reason.contains("disabled") || reason.contains("Disabled")) {
                Log.w(TAG, "🤖 DIAGNOSTIC: Robot is in Disabled State - double-tap chest button to enable");
            } else {
                Log.w(TAG, "🤖 DIAGNOSTIC: Unknown focus refusal - check robot status and permissions");
            }
        }
        
            runOnUiThread(() -> statusTextView.setText(getString(R.string.robot_focus_refused_message, reason)));
    }



    private void setupSettingsMenu() {
        NavigationView navigationView = findViewById(R.id.navigation_view);
        settingsManager = new SettingsManager(this, navigationView);
        settingsManager.setListener(new SettingsManager.SettingsListener() {
            @Override
            public void onSettingsChanged() {
                // This covers model, voice changes - requires new session
                Log.i(TAG, "Core settings changed. Starting new session.");
                startNewSession();
            }

            @Override
            public void onRecognizerSettingsChanged() {
                // This covers language and silence timeout
                Log.i(TAG, "Recognizer settings changed. Re-initializing speech recognizer.");
                    runOnUiThread(() -> statusTextView.setText(getString(R.string.status_updating_recognizer)));
                    stopContinuousRecognition();
                reinitializeSpeechRecognizerForSettings();
                            startContinuousRecognition();
                }

            @Override
            public void onVolumeChanged(int volume) {
                applyVolume(volume);
            }

            @Override
            public void onToolsChanged() {
                // This covers tools, prompt, and temperature changes - only session update needed
                Log.i(TAG, "Tools/prompt/temperature changed. Updating session.");
                if (sessionManager != null) {
                    sessionManager.updateSession();
                }
            }
        });

        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerClosed(@NonNull View drawerView) {
                settingsManager.onDrawerClosed();
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.chat_toolbar_menu, menu);
        return true;
    }

    @Override
    @SuppressLint("NullableProblems")
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.action_navigation_status) {
            // Toggle map preview visibility
            if (mapPreviewContainer != null) {
                mapPreviewContainer.setVisibility(
                    mapPreviewContainer.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE
                );
            }
            showNavigationStatusPopup();
            return true;
        } else if (itemId == R.id.action_new_chat) {
            startNewSession();
            return true;
        } else if (itemId == R.id.action_dashboard) {
            if (dashboardManager != null) {
                dashboardManager.toggleDashboard();
            }
            return true;
        } else if (itemId == R.id.action_settings) {
            drawerLayout.openDrawer(GravityCompat.END);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    
    private android.widget.PopupWindow navigationStatusPopup;
    
    private void showNavigationStatusPopup() {
        if (navigationStatusPopup != null && navigationStatusPopup.isShowing()) {
            navigationStatusPopup.dismiss();
            return;
        }
        
        // Inflate popup layout
        View popupView = getLayoutInflater().inflate(R.layout.navigation_status_popup, drawerLayout, false);
        
        // Update status in popup
        TextView popupMapStatus = popupView.findViewById(R.id.popup_map_status);
        TextView popupLocalizationStatus = popupView.findViewById(R.id.popup_localization_status);
        
        // Get current status from existing TextViews
        TextView mapStatus = findViewById(R.id.mapStatusTextView);
        TextView localizationStatus = findViewById(R.id.localizationStatusTextView);
        
        if (mapStatus != null && popupMapStatus != null) {
            String mapText = mapStatus.getText().toString();
            // Extract just the status part after the emoji and "Map: "
            popupMapStatus.setText(mapText.replaceFirst(getString(R.string.popup_map_status_prefix), ""));
        }
        
        if (localizationStatus != null && popupLocalizationStatus != null) {
            String localizationText = localizationStatus.getText().toString();
            // Extract just the status part after the emoji and "Localization: "
            popupLocalizationStatus.setText(localizationText.replaceFirst(getString(R.string.popup_localization_status_prefix), ""));
        }
        
        // Create popup window
        navigationStatusPopup = new android.widget.PopupWindow(
            popupView,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        );
        
        // Show popup below the toolbar
        View anchor = findViewById(R.id.topAppBar);
        if (anchor != null) {
            navigationStatusPopup.showAsDropDown(anchor, 16, 8);
        }
        
        // Auto dismiss after 5 seconds
        popupView.postDelayed(() -> {
            if (navigationStatusPopup != null && navigationStatusPopup.isShowing()) {
                navigationStatusPopup.dismiss();
            }
        }, 5000);
    }
    
    /**
     * Updates the map preview UI with the latest locations and state.
     */
    public void updateMapPreview() {
        if (mapPreviewView == null || locationProvider == null || navigationServiceManager == null) {
            return;
        }

        runOnUiThread(() -> {
            MapState state;
            if (!navigationServiceManager.isMapSavedOnDisk(this)) {
                state = MapState.NO_MAP;
            } else if (!navigationServiceManager.isMapLoaded()) {
                state = MapState.MAP_LOADED_NOT_LOCALIZED;
            } else if (!navigationServiceManager.isLocalizationReady()) {
                // Determine if it's localizing or failed
                String locStatus = localizationStatusTextView.getText().toString();
                if (locStatus.contains("Failed")) {
                    state = MapState.LOCALIZATION_FAILED;
                } else {
                    state = MapState.LOCALIZING;
                }
            } else {
                state = MapState.LOCALIZED;
            }

            mapPreviewView.updateData(
                locationProvider.getSavedLocations(), 
                state,
                navigationServiceManager.getMapBitmap(),
                navigationServiceManager.getMapTopGraphicalRepresentation()
            );
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
                Log.i(TAG, "Media volume set to " + percentage + "% (raw: " + targetVol + "/" + maxVol + ")");
            }
        } catch (Exception e) {
            Log.w(TAG, "Setting media volume failed", e);
        }
    }

    private void startNewSession() {
        // Reset mute state for new session
        isMuted = false;
        
        // Reset response tracking for clean chat bubbles
        lastChatBubbleResponseId = null;
        
        // Update UI immediately
        runOnUiThread(() -> {
            statusTextView.setText(getString(R.string.status_starting_new_session));
            // Conversation history managed by Realtime API
            messageList.clear();
            //noinspection NotifyDataSetChanged
            chatAdapter.notifyDataSetChanged();
        });

        // Perform disconnect and reconnect asynchronously on network thread
        threadManager.executeNetwork(() -> {
            // Clean up session-scoped images on I/O thread
            threadManager.executeIO(this::deleteSessionImages);
            
            // Stop gesture controller and reset turn manager for clean state
            Log.i(TAG, "Stopping gesture controller for session restart...");
            gestureController.stopNow();
            
            // Reset turn manager to LISTENING state for new session
            if (turnManager != null) {
                turnManager.setState(TurnManager.State.LISTENING);
                Log.i(TAG, "TurnManager reset to LISTENING for new session");
            }
            
            // Clear response tracking variables for clean state
            hasActiveResponse = false;
            currentResponseId = null;
            cancelledResponseId = null;
            lastChatBubbleResponseId = null;
            expectingFinalAnswerAfterToolCall = false;
            Log.i(TAG, "Response tracking variables cleared for new session");
            
            // 1. Gracefully disconnect the old session
            Log.i(TAG, "Starting session cleanup for provider switch...");
            disconnectWebSocketGracefully();

            // Brief pause to ensure resources are released and avoid race conditions
            try {
                Thread.sleep(500); // Increased from 250ms for better cleanup
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // 2. Connect to start a new session
            Log.i(TAG, "Starting new session with updated provider...");
            Future<Void> connectFuture = connectWebSocket();

            // 3. Update UI based on reconnection result
            connectFuture.thenConsume(future -> {
                if (future.hasError()) {
                    Throwable error = future.getError();
                    // Filter out harmless race condition errors from provider switching
                    if (error instanceof com.aldebaran.qi.QiException && 
                        error.getMessage() != null && 
                        error.getMessage().contains("WebSocket closed before session was updated")) {
                        Log.w(TAG, "Harmless race condition during provider switch - session will recover");
                        // Don't show error to user, new session should be establishing
                    } else {
                        Log.e(TAG, "Failed to start new session", error);
                    runOnUiThread(() -> {
                        addMessage(getString(R.string.new_session_error), ChatMessage.Sender.ROBOT);
                        statusTextView.setText(getString(R.string.error_connection_failed_short));
                    });
                    }
                } else {
                    Log.i(TAG, "New session started successfully. Ready to listen.");
                    // The "Ready" message is handled by the "session.updated" event.
                    // We can now start listening for user input.
                    startContinuousRecognition();
                }
            });
        });
    }


    /**
     * Configure STT with activity callbacks
     */
    private void configureSpeechRecognizer() {
        sttManager.setCallbacks(new SpeechRecognizerManager.ActivityCallbacks() {
            @Override
            public void onRecognizedText(String text) {
                // Gate STT: only accept in LISTENING state and when not muted
                if (turnManager != null && turnManager.getState() != TurnManager.State.LISTENING) {
                    Log.i(TAG, "Ignoring STT result because state=" + turnManager.getState());
                    return;
                }
                
                if (isMuted) {
                    Log.i(TAG, "Ignoring STT result because microphone is muted");
                    return;
                }
                
                runOnUiThread(() -> {
                    addMessage(text.replaceAll("\\[Low confidence:.*?]", "").trim(), ChatMessage.Sender.USER);
                    sendMessageToRealtimeAPI(text, true, false);
                });
            }

            @Override
            public void onPartialText(String partialText) {
                // Don't show partial text when muted
                if (isMuted) {
                    return;
                }
                
                runOnUiThread(() -> {
                    if (statusTextView.getText().toString().startsWith("Listening")) {
                        statusTextView.setText(getString(R.string.status_listening_partial, partialText));
                    }
                });
            }

            @Override
            public void onError(String errorMessage) {
                Log.e(TAG, "STT error: " + errorMessage);
                runOnUiThread(() -> statusTextView.setText(getString(R.string.error_generic, errorMessage)));
            }

            @Override
            public void onStarted() {
                sttIsRunning = true;
                runOnUiThread(() -> statusTextView.setText(getString(R.string.status_listening)));
            }

            @Override
            public void onStopped() {
                sttIsRunning = false;
            }
        });
        
        // Configure with current settings
        String langCode = settingsManager.getLanguage();
        int silenceTimeout = settingsManager.getSilenceTimeout();
        double confidenceThreshold = settingsManager.getConfidenceThreshold();
        
        sttManager.configure(keyManager.getAzureSpeechKey(), keyManager.getAzureSpeechRegion(), langCode, silenceTimeout, confidenceThreshold);
    }
    
    private void setupSpeechRecognizer() throws Exception {
        Log.i(TAG, "Starting STT setup - ensuring manager...");
        ensureSttManager();
        
        Log.i(TAG, "Setting up other services...");
        // Setup other services
                if (visionService == null) visionService = new VisionService(this);
        setupRealtimeEventHandlerIfNeeded();
        
        Log.i(TAG, "Configuring speech recognizer...");
        // Configure STT with callbacks and current settings
        configureSpeechRecognizer();
        
        Log.i(TAG, "Starting STT warmup...");
        // Perform warmup
        sttManager.warmup();
        
        Log.i(TAG, "Speech Recognizer setup completed");
    }

    /**
     * Setup RealtimeEventHandler if not already created
     */
    private void setupRealtimeEventHandlerIfNeeded() {
        if (eventHandler == null) {
            eventHandler = new RealtimeEventHandler(new RealtimeEventHandler.Listener() {
                    @Override public void onSessionUpdated(JSONObject session) {
                        Log.i(TAG, "Session configured successfully - completing connection promise.");
                        // Try to read output_audio_format.sample_rate_hz to align AudioTrack
                        try {
                            if (session != null && session.has("output_audio_format")) {
                                JSONObject fmt = session.optJSONObject("output_audio_format");
                                if (fmt != null) {
                                    int sr = fmt.optInt("sample_rate_hz", 0);
                                    if (sr > 0 && audioPlayer != null) {
                                        Log.i(TAG, "Applying session sample rate: " + sr);
                                        audioPlayer.setSampleRate(sr);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to apply session sample rate", e);
                        }
                        if (!isWarmingUp) {
                            runOnUiThread(() -> statusTextView.setText(getString(R.string.status_ready)));
                        }
                        completeConnectionPromise();
                    }
                    @Override public void onAudioTranscriptDelta(String delta, String responseId) {
                        runOnUiThread(() -> {
                            if (Objects.equals(responseId, cancelledResponseId)) {
                                return; // drop transcript of cancelled response
                            }
                            CharSequence current = statusTextView.getText();
                            if (current == null || current.length() == 0 || !current.toString().startsWith("Speaking — tap to interrupt: ")) {
                                statusTextView.setText(getString(R.string.status_speaking_tap_to_interrupt));
                            }
                            statusTextView.append(delta);
                            boolean needNew = expectingFinalAnswerAfterToolCall
                                    || messageList.isEmpty()
                                    || messageList.get(messageList.size() - 1).getSender() != ChatMessage.Sender.ROBOT
                                    || !Objects.equals(responseId, lastChatBubbleResponseId);
                            if (needNew) {
                                addMessage(delta, ChatMessage.Sender.ROBOT);
                                expectingFinalAnswerAfterToolCall = false;
                                lastChatBubbleResponseId = responseId;
                            } else {
                                ChatMessage last = messageList.get(messageList.size() - 1);
                                last.setMessage(last.getMessage() + delta);
                                chatAdapter.notifyItemChanged(messageList.size() - 1);
                            }
                        });
                    }
                    @Override public void onAudioDelta(byte[] pcm16, String responseId) {
                        // Ignore audio from cancelled response
                        if (Objects.equals(responseId, cancelledResponseId)) {
                            return;
                        }
                        
                        if (!audioPlayer.isPlaying()) {
                            turnManager.setState(TurnManager.State.SPEAKING);
                        }
                        if (responseId != null) {
                            if (!Objects.equals(currentResponseId, responseId)) {
                                try { audioPlayer.onResponseBoundary(); } catch (Exception ignored) {}
                            currentResponseId = responseId;
                            }
                        }
                        
                hasActiveResponse = true;
                        audioPlayer.addChunk(pcm16);
                        audioPlayer.startIfNeeded();
                    }
                    @Override public void onAudioDone() {
                        audioPlayer.markResponseDone();
                hasActiveResponse = false;
                    }
                    @Override public void onResponseDone(JSONObject response) {
                        Log.i(TAG, "Full response received. Processing final output.");
                        try {
                            JSONArray outputArray = response.optJSONArray("output");

                            if (outputArray == null || outputArray.length() == 0) {
                                Log.i(TAG, "Response.done with no output. Finishing turn.");
                                // The turn will be finished by onPlaybackFinished callback. 
                                // If there's no audio, we just wait. This prevents a race condition
                                // where we try to enter LISTENING state before audio playback has even finished.
                                return;
                            }

                            List<JSONObject> functionCalls = new ArrayList<>();
                            List<JSONObject> messageItems = new ArrayList<>();

                            for (int i = 0; i < outputArray.length(); i++) {
                                JSONObject out = outputArray.getJSONObject(i);
                                String outType = out.optString("type");
                                if ("function_call".equals(outType)) {
                                    functionCalls.add(out);
                                } else if ("message".equals(outType)) {
                                    messageItems.add(out);
                                }
                            }

                            if (!functionCalls.isEmpty()) {
                                for (JSONObject fc : functionCalls) {
                                    String toolName = fc.getString("name");
                                    String callId = fc.getString("call_id");
                                    String argsString = fc.getString("arguments");
                                    JSONObject args = new JSONObject(argsString);

                                    final String fToolName = toolName;
                                    final String fArgsString = args.toString();
                                    runOnUiThread(() -> addFunctionCall(fToolName, fArgsString));

                                    String result = toolRegistry.executeTool(toolName, args, toolContext);
                                    final String fResult = result;
                                    runOnUiThread(() -> updateFunctionCallResult(fResult));
                                    sendToolResult(callId, result);
                                    expectingFinalAnswerAfterToolCall = true;
                                }
                            }

                            if (!messageItems.isEmpty()) {
                                try {
                                    JSONObject firstMsg = messageItems.get(0);
                                    JSONObject assistantMessage = new JSONObject();
                                    assistantMessage.put("role", "assistant");
                                    assistantMessage.put("content", firstMsg.optJSONArray("content"));
                                    Log.d(TAG, "Final assistant message added to local history.");
            } catch (Exception e) {
                                    Log.e(TAG, "Could not save final assistant message to history", e);
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing response.done message. Attempting to recover.", e);
                            // Recovery logic is handled by onPlaybackFinished. Removing manual state change
                            // to prevent race conditions and double STT start.
                        }
                    }
                    @Override public void onAssistantItemAdded(String itemId) {
                        try {
                            lastAssistantItemId = itemId;
                        } catch (Exception ignored) {}
                    }
                    @Override public void onResponseBoundary(String newResponseId) {
                        try {
                            Log.d(TAG, "Response boundary detected - new ID: " + newResponseId + ", previous ID: " + currentResponseId);
                            if (audioPlayer != null) {
                                audioPlayer.onResponseBoundary();
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Error during response boundary reset", e);
                        }
                    }
                    @Override public void onResponseCreated(String responseId) {
                        try {
                            Log.d(TAG, "New response created with ID: " + responseId);
                        } catch (Exception ignored) {}
                    }
                    @Override public void onError(JSONObject error) {
                        String code = error != null ? error.optString("code", "Unknown") : "Unknown";
                        String msg = error != null ? error.optString("message", "An unknown error occurred.") : "";
                        Log.e(TAG, "WebSocket Error Received - Code: " + code + ", Message: " + msg);
                        runOnUiThread(() -> {
                            addMessage(getString(R.string.server_error_prefix, msg), ChatMessage.Sender.ROBOT);
                            statusTextView.setText(getString(R.string.error_generic, code));
                        });
                        failConnectionPromise("Server returned an error during setup: " + msg);
                    }
                    @Override public void onUnknown(String type, JSONObject raw) {
                        // Unknown message types are logged but not processed
                        Log.w(TAG, "Unknown WebSocket message type: " + type);
                    }
                });
        }
    }
    
    /**
     * Re-initialize STT for settings changes - direct approach
     */
    private void reinitializeSpeechRecognizerForSettings() {
        threadManager.executeAudio(() -> {
            try {
                ensureSttManager();
                
                // Reconfigure with new settings
                configureSpeechRecognizer();
                
                String langCode = settingsManager.getLanguage();
                int silenceTimeout = settingsManager.getSilenceTimeout();
                Log.i(TAG, "Speech Recognizer re-initialized for language: " + langCode + ", silence timeout: " + silenceTimeout + "ms");

                runOnUiThread(() -> statusTextView.setText(getString(R.string.status_listening)));

            } catch (Exception ex) {
                Log.e(TAG, "STT re-init failed", ex);
                runOnUiThread(() -> statusTextView.setText(getString(R.string.error_updating_settings)));
            }
        });
    }

    private void ensureSttManager() {
                if (sttManager == null) {
                sttManager = new SpeechRecognizerManager();
            Log.i(TAG, "Created new SpeechRecognizerManager");
        }
    }

    private void startContinuousRecognition() {
        if (sttManager == null) {
            Log.w(TAG, "Speech recognizer not initialized yet, cannot start recognition.");
            runOnUiThread(() -> statusTextView.setText(getString(R.string.status_recognizer_not_ready)));
            return;
        }
        
        threadManager.executeAudio(() -> {
            sttManager.start(); // Manager handles all complexity internally
        });
    }

    private void stopContinuousRecognition() {
        if (sttManager != null) {
            threadManager.executeAudio(() -> {
                sttManager.stop(); // Manager handles all complexity internally
            });
        }
    }

    /**
     * Unified function to send text messages to Realtime API
     * @param text Message content
     * @param requestResponse Whether to request a response from the model
     * @param allowInterrupt Whether to interrupt current speech if needed
     */
    public void sendMessageToRealtimeAPI(String text, boolean requestResponse, boolean allowInterrupt) {
        // Ensure this method always runs on UI thread for thread-safe UI operations
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> sendMessageToRealtimeAPI(text, requestResponse, allowInterrupt));
            return;
        }
        
        if (sessionManager == null || !sessionManager.isConnected()) {
            Log.e(TAG, "WebSocket is not connected. Cannot send message.");
            if (requestResponse) {
            runOnUiThread(() -> addMessage(getString(R.string.error_not_connected), ChatMessage.Sender.ROBOT));
            }
            return;
        }

        try {
            // Handle interruption if requested and necessary
            if (allowInterrupt && requestResponse && turnManager != null && turnManager.getState() == TurnManager.State.SPEAKING) {
                long now = android.os.SystemClock.uptimeMillis();
                boolean isPlaying = (audioPlayer != null && audioPlayer.isPlaying());
                long sinceDone = now - lastAudioDoneTs;
                Log.d(TAG, "Interrupt guard: state=SPEAKING, isPlaying=" + isPlaying + ", sinceDoneMs=" + sinceDone);
                if (isPlaying && sinceDone > 200) {
                    Log.d(TAG, "Triggering interrupt via FAB (cancel+truncate)");
                    fabInterrupt.performClick();
            } else {
                    Log.d(TAG, "Skip interrupt (isPlaying=" + isPlaying + ", sinceDoneMs=" + sinceDone + ")");
                }
            }

            // Step 1: Send the message via the session manager
            boolean sentItem = sessionManager.sendUserTextMessage(text);
            if (!sentItem) {
                handleWebSocketSendError("message");
                return;
            }

            // Step 2: Request response if needed
            if (requestResponse) {
                if (turnManager != null) {
                    turnManager.setState(TurnManager.State.THINKING);
                }
                boolean sentResponse = sessionManager.requestResponse();
                if (!sentResponse) {
                    handleWebSocketSendError("response request");
                }
            }
                                } catch (Exception e) {
            Log.e(TAG, "Exception in sendMessageToRealtimeAPI", e);
            if (requestResponse) {
            runOnUiThread(() -> {
                addMessage(getString(R.string.error_processing_message), ChatMessage.Sender.ROBOT);
                    if (turnManager != null) turnManager.setState(TurnManager.State.IDLE);
            });
        }
    }
    }


    // handleResponseDone() moved to onResponseDone() in RealtimeEventHandler.Listener

    private void sendToolResult(String callId, String result) {
        if (sessionManager == null || !sessionManager.isConnected()) return;
        try {
            // Mark that a follow-up answer is expected
            expectingFinalAnswerAfterToolCall = true;
 
            // Send the tool result via the session manager
            boolean sentTool = sessionManager.sendToolResult(callId, result);
            if (!sentTool) {
                handleWebSocketSendError("tool result");
                return;
            }
 
            // After sending the tool result, we must ask for a new response
            boolean sentToolResponse = sessionManager.requestResponse();
            if (!sentToolResponse) {
                handleWebSocketSendError("tool response request");
                return;
            }

            // Hold state in THINKING until the next response audio begins,
            // but do NOT override SPEAKING to preserve ability to interrupt current speech
            if (turnManager != null && turnManager.getState() != TurnManager.State.SPEAKING) {
                turnManager.setState(TurnManager.State.THINKING);
            }
 
        } catch (Exception e) {
            Log.e(TAG, "Error sending tool result", e);
        }
    }

    private void disconnectWebSocket() {
        if (sessionManager != null) {
            sessionManager.close(1000, "User initiated disconnect");
        }
        // It's better not to shut down the client, as we want to reuse it.
        Log.i(TAG, "WebSocket connection closed.");
    }
    
    /**
     * Gracefully disconnect WebSocket with proper cleanup for provider switching
     * Prevents race conditions during session transitions
     */
    private void disconnectWebSocketGracefully() {
        if (sessionManager != null) {
            // Clear any pending connection promises to prevent race conditions
            connectionPromise = null;
            
            // Cancel any ongoing responses to prevent them from interfering
            if (hasActiveResponse && currentResponseId != null) {
                Log.d(TAG, "Cancelling active response before provider switch: " + currentResponseId);
                cancelledResponseId = currentResponseId;
                hasActiveResponse = false;
                currentResponseId = null;
            }
            
            // Close connection with provider switch reason
            sessionManager.close(1000, "Provider switch - starting new session");
            Log.i(TAG, "WebSocket gracefully closed for provider switch");
        } else {
            Log.d(TAG, "No active session to disconnect");
        }
    }

    // Wrapper to initiate WebSocket connection via sessionManager and wait for session.updated
    private Future<Void> connectWebSocket() {
        connectionPromise = new Promise<>();
        if (sessionManager == null) {
            Log.w(TAG, "sessionManager was null in connectWebSocket - this should not happen");
            sessionManager = new RealtimeSessionManager();
            
            // Set session dependencies (should be available in connectWebSocket)
            sessionManager.setSessionDependencies(toolRegistry, toolContext, settingsManager, keyManager);
            
            sessionManager.setListener(createSessionManagerListener());
        }
        if (sessionManager.isConnected()) {
            completeConnectionPromise();
            return connectionPromise.getFuture();
        }
        // Get current API provider and build connection parameters
        RealtimeApiProvider provider = settingsManager.getApiProvider();
        String selectedModel = settingsManager.getModel();
        String url = provider.getWebSocketUrl(keyManager.getAzureOpenAiEndpoint(), selectedModel);
        Log.i(TAG, "Connecting with selected model: " + selectedModel);
        
        java.util.HashMap<String, String> headers = new java.util.HashMap<>();
        
        if (provider.isAzureProvider()) {
            // Azure OpenAI authentication
        headers.put("api-key", keyManager.getAzureOpenAiKey());
        } else {
            // OpenAI Direct authentication
            headers.put("Authorization", provider.getAuthorizationHeader(
                keyManager.getAzureOpenAiKey(), 
                keyManager.getOpenAiApiKey()));
        }
        
        // Beta header: only for non-GA models (gpt-realtime is GA, others are beta)
        if (!"gpt-realtime".equals(selectedModel)) {
            headers.put("OpenAI-Beta", "realtime=v1");
            Log.d(TAG, "Using beta API for model: " + selectedModel);
        } else {
            Log.d(TAG, "Using GA API for model: " + selectedModel);
        }
        
        Log.d(TAG, "Connecting to " + provider.getDisplayName() + " - URL: " + url);
        sessionManager.connect(url, headers);
        return connectionPromise.getFuture();
    }

    private RealtimeSessionManager.Listener createSessionManagerListener() {
        return new RealtimeSessionManager.Listener() {
            @Override public void onOpen(Response response) {
                Log.i(TAG, "WebSocket onOpen() - configuring initial session");
                sessionManager.configureInitialSession();
            }
            @Override public void onTextMessage(String text) { if (eventHandler != null) eventHandler.handle(text); }
            @Override public void onBinaryMessage(okio.ByteString bytes) { /* ignore */ }
            @Override public void onClosing(int code, String reason) { }
            @Override public void onClosed(int code, String reason) {
                failConnectionPromise("WebSocket closed before session was updated.");
            }
            @Override public void onFailure(Throwable t, Response response) {
                Log.e(TAG, "WebSocket Failure: " + t.getMessage(), t);
                failConnectionPromise("WebSocket Failure: " + t.getMessage());
            }
        };
    }

    private void releaseAudioTrack() {
        if (audioPlayer != null) audioPlayer.release();
        try { gestureController.shutdown(); } catch (Exception ignored) {}
        Log.i(TAG, "AudioTrack released.");
    }

    private void startExplainGesturesLoop() {
        if (qiContext == null) {
            Log.w(TAG, "Cannot start gestures - qiContext is null");
            return;
        }
        if (navigationServiceManager != null && navigationServiceManager.areGesturesSuppressed()) {
            Log.w(TAG, "Gestures suppressed - not starting gesture loop (critical navigation phase)");
            return;
        }
        Log.i(TAG, "Starting gesture loop for speaking state");
        gestureController.start(qiContext,
                () -> turnManager != null && turnManager.getState() == TurnManager.State.SPEAKING && qiContext != null && 
                      (navigationServiceManager == null || !navigationServiceManager.areGesturesSuppressed()),
                this::getRandomExplainAnimationResId);
    }

    private Integer getRandomExplainAnimationResId() {
        int[] ids = new int[] {
                R.raw.explain_01,
                R.raw.explain_02,
                R.raw.explain_03,
                R.raw.explain_04,
                R.raw.explain_05,
                R.raw.explain_06,
                R.raw.explain_07,
                R.raw.explain_08,
                R.raw.explain_09,
                R.raw.explain_10,
                R.raw.explain_11
        };
        java.util.Random rnd = new java.util.Random();
        return ids[rnd.nextInt(ids.length)];
    }

    private void addMessage(String text, ChatMessage.Sender sender) {
        runOnUiThread(() -> {
            messageList.add(new ChatMessage(text, sender));
            chatAdapter.notifyItemInserted(messageList.size() - 1);
            chatRecyclerView.scrollToPosition(messageList.size() - 1);
             if (sender == ChatMessage.Sender.USER) {
                 statusTextView.setText(getString(R.string.status_thinking));
             }
        });
    }

    private void addFunctionCall(String functionName, String args) {
        ChatMessage functionCall = ChatMessage.createFunctionCall(functionName, args, ChatMessage.Sender.ROBOT);
        messageList.add(functionCall);
        chatAdapter.notifyItemInserted(messageList.size() - 1);
        chatRecyclerView.scrollToPosition(messageList.size() - 1);
    }

    private void updateFunctionCallResult(String result) {
        // Find the last function call without a result and update it
        for (int i = messageList.size() - 1; i >= 0; i--) {
            ChatMessage message = messageList.get(i);
            if (message.getType() == ChatMessage.Type.FUNCTION_CALL && 
                message.getFunctionResult() == null) {
                message.setFunctionResult(result);
                chatAdapter.notifyItemChanged(i);
                break;
            }
        }
    }


    // Session configuration methods moved to RealtimeSessionManager

    private void deleteSessionImages() {
        synchronized (sessionImagePaths) {
            for (String p : sessionImagePaths) {
                try {
                    if (p != null) {
                        java.io.File f = new java.io.File(p);
                        if (f.exists()) {
                            boolean ok = f.delete();
                            if (!ok) { Log.w(TAG, "Could not delete session image: " + p); }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error deleting session image: " + p, e);
                }
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

    private void hideWarmupIndicator() {
        runOnUiThread(() -> {
            warmupIndicatorLayout.setVisibility(View.GONE);
            // The status will be set to Listening by the TurnManager, so no need to set it here.
        });
    }
    
    
    /**
     * Interrupts any active speech by cancelling responses, truncating audio, and stopping playback
     * Core logic used by both FAB interrupt and interrupt-and-mute functionality
     */
    private void interruptSpeech() {
        try {
            if (sessionManager == null || !sessionManager.isConnected()) return;
            
            Log.d(TAG, "🚨 DIAGNOSTIC: interruptSpeech called: hasActiveResponse=" + hasActiveResponse);
            
                // 1) Cancel further generation first (send only if there is an active response)
                if (hasActiveResponse) {
                    JSONObject cancel = new JSONObject();
                    cancel.put("type", "response.cancel");
                    sessionManager.send(cancel.toString());
                    // mark the current response as cancelled to ignore trailing chunks
                    cancelledResponseId = currentResponseId;
                }

                // 2) Truncate current assistant item if we have its id
                if (lastAssistantItemId != null) {
                    int playedMs = audioPlayer != null ? Math.max(0, audioPlayer.getEstimatedPlaybackPositionMs()) : 0;
                Log.d(TAG, "🚨 DIAGNOSTIC: sending truncate for item=" + lastAssistantItemId + ", audio_end_ms=" + playedMs);
                    JSONObject truncate = new JSONObject();
                    truncate.put("type", "conversation.item.truncate");
                    truncate.put("item_id", lastAssistantItemId);
                    truncate.put("content_index", 0);
                    truncate.put("audio_end_ms", playedMs);
                    sessionManager.send(truncate.toString());
                }

                // 3) Stop local audio and gestures
                if (audioPlayer != null) audioPlayer.interruptNow();
                gestureController.stopNow();
        } catch (Exception e) {
            Log.e(TAG, "Error during interruptSpeech", e);
        }
    }

    /**
     * Interrupts any active response and mutes the microphone
     */
    private void interruptAndMute() {
        try {
            interruptSpeech();
            mute();
        } catch (Exception e) {
            Log.e(TAG, "Interrupt and mute failed", e);
        }
    }
    
    /**
     * Mutes the microphone and updates UI
     */
    private void mute() {
        isMuted = true;
        stopContinuousRecognition();
        runOnUiThread(() -> {
            statusTextView.setText(getString(R.string.status_muted_tap_to_unmute));
            findViewById(R.id.fab_interrupt).setVisibility(View.GONE);
        });
        Log.i(TAG, "Microphone muted - tap status to un-mute");
    }
    
    /**
     * Un-mutes the microphone and resumes listening
     */
    @SuppressWarnings("SpellCheckingInspection")
    private void unmute() {
        isMuted = false;
        runOnUiThread(() -> {
            statusTextView.setText(getString(R.string.status_listening));
            findViewById(R.id.fab_interrupt).setVisibility(View.GONE);
        });
        
        // CRITICAL FIX: Directly start recognition instead of relying on TurnManager state change,
        // which won't fire if the state is already LISTENING.
        try {
        startContinuousRecognition();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start recognition on unmute", e);
            runOnUiThread(() -> statusTextView.setText(getString(R.string.status_recognizer_not_ready)));
        }
        
        Log.i(TAG, "Microphone un-muted - resuming listening");
    }
    
    /**
     * Handle service state changes for proper service management
     * @param mode Service mode (e.g., "enterLocalizationMode", "resumeNormalOperation")
     */
    public void handleServiceStateChange(String mode) {
        Log.i(TAG, "Service state change received: " + mode + " - delegating to NavigationServiceManager");
        if (navigationServiceManager != null) {
            navigationServiceManager.handleServiceStateChange(mode);
        }
    }

    
    /**
     * Add image message to chat (called directly by tools)
     */
    public void addImageMessage(String imagePath) {
            runOnUiThread(() -> {
            ChatMessage msg = new ChatMessage("", imagePath, ChatMessage.Sender.ROBOT);
            messageList.add(msg);
            chatAdapter.notifyItemInserted(messageList.size() - 1);
            chatRecyclerView.scrollToPosition(messageList.size() - 1);
        });
    }
    
    /**
     * Add image to session cleanup list (called directly by tools)
     */
    public void addImageToSessionCleanup(String imagePath) {
        synchronized (sessionImagePaths) {
            sessionImagePaths.add(imagePath);
        }
    }
    
    /**
     * Update navigation status display (called directly by tools)
     */
    public void updateNavigationStatus(String mapStatus, String localizationStatus) {
        Log.i(TAG, "Updating navigation status - Map: " + mapStatus + ", Localization: " + localizationStatus);
        runOnUiThread(() -> {
            if (mapStatus != null) {
                updateMapStatus(mapStatus);
            }
            if (localizationStatus != null) {
                updateLocalizationStatus(localizationStatus);
            }
        });
    }
    
    /**
     * Get NavigationServiceManager for tool access
     */
    public NavigationServiceManager getNavigationServiceManager() {
        return navigationServiceManager;
    }
    
    // addConfidenceWarningIfNeeded() moved to SpeechRecognizerManager

}


