package io.github.anonymous.pepper_realtime.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
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

import io.github.anonymous.pepper_realtime.R;
import io.github.anonymous.pepper_realtime.controller.ChatSpeechListener;
import io.github.anonymous.pepper_realtime.controller.RobotFocusManager;
import io.github.anonymous.pepper_realtime.manager.AppContainer;
import io.github.anonymous.pepper_realtime.manager.AudioPlayer;
import io.github.anonymous.pepper_realtime.manager.SettingsManager;
import io.github.anonymous.pepper_realtime.manager.TurnManager;
import io.github.anonymous.pepper_realtime.network.RealtimeSessionManager;
import io.github.anonymous.pepper_realtime.robot.RobotController;
import io.github.anonymous.pepper_realtime.manager.NavigationServiceManager;
import io.github.anonymous.pepper_realtime.manager.TouchSensorManager;
import io.github.anonymous.pepper_realtime.controller.GestureController;
import io.github.anonymous.pepper_realtime.service.PerceptionService;
import io.github.anonymous.pepper_realtime.ui.ChatMessage;

import okhttp3.Response;
import okio.ByteString;

public class ChatActivity extends AppCompatActivity {

    private static final String TAG = "ChatActivity";

    private static final int MICROPHONE_PERMISSION_REQUEST_CODE = 2;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 3;

    // UI Components
    private TextView statusTextView;
    private LinearLayout warmupIndicatorLayout;
    private FloatingActionButton fabInterrupt;

    // App Container
    private AppContainer appContainer;

    // State
    private final List<ChatMessage> messageList = new ArrayList<>();
    private final Map<String, ChatMessage> pendingUserTranscripts = new HashMap<>();
    
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
    
    public interface WebSocketConnectionCallback {
        void onSuccess();
        void onError(Throwable error);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_chat);

        // Initialize UI references
        statusTextView = findViewById(R.id.statusTextView);
        warmupIndicatorLayout = findViewById(R.id.warmup_indicator_layout);
        fabInterrupt = findViewById(R.id.fab_interrupt);
        MaterialToolbar topAppBar = findViewById(R.id.topAppBar);
        setSupportActionBar(topAppBar);

        // Initialize AppContainer (creates all managers and controllers)
        appContainer = new AppContainer(this, messageList, pendingUserTranscripts);

        // Setup Listeners using AppContainer components
        setupSessionManagerListeners();
        setupSettingsListener();
        setupRobotLifecycleListener();
        setupAudioPlayerListener();
        setupUiListeners();

        // Register Robot Lifecycle
        appContainer.robotFocusManager.register();

        // Request Permissions
        checkPermissions();
    }
    
    private void setupSessionManagerListeners() {
        appContainer.sessionManager.setSessionConfigCallback((success, error) -> {
            if (success) {
                Log.i(TAG, "Session configured successfully - completing connection promise");
                completeConnectionPromise();
            } else {
                Log.e(TAG, "Session configuration failed: " + error);
                failConnectionPromise("Session config failed: " + error);
            }
        });
        appContainer.sessionManager.setListener(createSessionManagerListener());
    }

    private RealtimeSessionManager.Listener createSessionManagerListener() {
        return new RealtimeSessionManager.Listener() {
            @Override public void onOpen(Response response) {
                Log.i(TAG, "WebSocket onOpen() - configuring initial session");
                appContainer.sessionManager.configureInitialSession();
            }
            @Override public void onTextMessage(String text) {
                if (appContainer.eventHandler != null) appContainer.eventHandler.handle(text);
            }
            @Override public void onBinaryMessage(ByteString bytes) {
                // Handle audio input buffer if needed
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
        appContainer.settingsManager.setListener(new SettingsManager.SettingsListener() {
            @Override
            public void onSettingsChanged() {
                Log.i(TAG, "Core settings changed. Starting new session.");
                appContainer.sessionController.startNewSession();
            }
            @Override
            public void onRecognizerSettingsChanged() {
                Log.i(TAG, "Recognizer settings changed. Re-initializing speech recognizer.");
                runOnUiThread(() -> statusTextView.setText(getString(R.string.status_updating_recognizer)));
                appContainer.audioInputController.stopContinuousRecognition();
                appContainer.audioInputController.reinitializeSpeechRecognizerForSettings();
                appContainer.audioInputController.startContinuousRecognition();
            }
            @Override
            public void onVolumeChanged(int volume) {
                applyVolume(volume);
            }
            @Override
            public void onToolsChanged() {
                Log.i(TAG, "Tools/prompt/temperature changed. Updating session.");
                if (appContainer.sessionManager != null) {
                    appContainer.sessionManager.updateSession();
                }
            }
        });
    }

    private void setupRobotLifecycleListener() {
        appContainer.robotFocusManager.setListener(new RobotFocusManager.Listener() {
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
        });
    }
    
    private void setupAudioPlayerListener() {
        appContainer.audioPlayer.setListener(new AudioPlayer.Listener() {
            @Override public void onPlaybackStarted() {
                appContainer.turnManager.setState(TurnManager.State.SPEAKING);
            }
            @Override public void onPlaybackFinished() {
                isAudioPlaying = false;
                if (!expectingFinalAnswerAfterToolCall && !isResponseGenerating) {
                    appContainer.turnManager.setState(TurnManager.State.LISTENING);
                } else {
                    appContainer.turnManager.setState(TurnManager.State.THINKING);
                }
            }
        });
    }

    private void setupUiListeners() {
        fabInterrupt.setOnClickListener(v -> {
            try {
                appContainer.interruptController.interruptSpeech();
                if (appContainer.turnManager != null) appContainer.turnManager.setState(TurnManager.State.LISTENING);
            } catch (Exception e) {
                Log.e(TAG, "Interrupt failed", e);
            }
        });

        statusTextView.setOnClickListener(v -> {
            try {
                if (appContainer.turnManager == null) return;
                TurnManager.State currentState = appContainer.turnManager.getState();
                
                if (currentState == TurnManager.State.SPEAKING) {
                    if (isResponseGenerating || isAudioPlaying || (appContainer.audioPlayer != null && appContainer.audioPlayer.isPlaying())) {
                        appContainer.interruptController.interruptAndMute();
                    }
                } else if (appContainer.audioInputController.isMuted()) {
                    unmute();
                } else if (currentState == TurnManager.State.LISTENING) {
                    mute();
                }
            } catch (Exception e) {
                Log.e(TAG, "Status bar click handler failed", e);
            }
        });
        
        // Wire up menu controller listener
        appContainer.chatMenuController.setListener(() -> appContainer.sessionController.startNewSession());
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
        return appContainer.chatMenuController.onCreateOptionsMenu(menu, getMenuInflater());
    }
    
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        return appContainer.chatMenuController.onPrepareOptionsMenu(menu);
    }
    
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_new_chat) {
            appContainer.sessionController.startNewSession();
            return true;
        }
        return appContainer.chatMenuController.onOptionsItemSelected(item) || super.onOptionsItemSelected(item);
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.i(TAG, "🔄 Activity stopped (background) - pausing services");
        wasStoppedByBackground = true;
        
        appContainer.audioInputController.cleanupForRestart();
        
        if (appContainer.audioPlayer != null) {
            try {
                appContainer.audioPlayer.interruptNow();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping audio playback during background", e);
            }
        }
        
        runOnUiThread(() -> {
            if (appContainer.turnManager != null) {
                appContainer.turnManager.setState(TurnManager.State.IDLE);
            }
            statusTextView.setText(getString(R.string.app_paused));
        });
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        if (wasStoppedByBackground && appContainer.robotFocusManager.isFocusAvailable()) {
            Log.i(TAG, "🔄 Activity resumed from background - restarting services");
            wasStoppedByBackground = false;
            
            runOnUiThread(() -> {
                if (appContainer.turnManager != null) {
                    appContainer.turnManager.setState(TurnManager.State.LISTENING);
                }
                statusTextView.setText(getString(R.string.status_listening));
            });
            
            appContainer.audioInputController.handleResume();
        }
    }

    @Override
    protected void onDestroy() {
        appContainer.shutdown();
        deleteSessionImages();
        super.onDestroy();
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
    
    private void handleRobotReady(Object robotContext) {
        if (appContainer.locationProvider != null) {
            appContainer.locationProvider.refreshLocations(this);
        }

        runOnUiThread(() -> {
            boolean hasMap = new java.io.File(getFilesDir(), "maps/default_map.map").exists();
            updateNavigationStatus(
                getString(hasMap ? R.string.nav_map_saved : R.string.nav_map_none),
                getString(R.string.nav_localization_not_running)
            );
        });
        
        appContainer.audioInputController.cleanupSttForReinit();
        
        if (appContainer.toolContext != null) appContainer.toolContext.updateQiContext(robotContext);
        
        if (appContainer.dashboardManager != null) {
            appContainer.dashboardManager.initialize(appContainer.perceptionService);
        }
        
        if (appContainer.perceptionService != null) {
            appContainer.perceptionService.initialize(robotContext);
        }
        
        if (appContainer.visionService != null) {
            appContainer.visionService.initialize(robotContext);
        }
        
        if (appContainer.touchSensorManager != null) {
            appContainer.touchSensorManager.setListener(new TouchSensorManager.TouchEventListener() {
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
            appContainer.touchSensorManager.initialize(robotContext);
        }
        
        if (appContainer.navigationServiceManager != null) {
            appContainer.navigationServiceManager.setDependencies(appContainer.perceptionService, appContainer.touchSensorManager, appContainer.gestureController);
        }

        if (!hasFocusInitialized) {
            hasFocusInitialized = true;
            isWarmingUp = true;
            lastChatBubbleResponseId = null;
            applyVolume(appContainer.settingsManager.getVolume());
            showWarmupIndicator();
            
            Log.i(TAG, "Starting WebSocket connection...");
            appContainer.sessionController.connectWebSocket(new WebSocketConnectionCallback() {
                @Override
                public void onSuccess() {
                    Log.i(TAG, "WebSocket connected successfully, starting STT warmup...");
                    appContainer.audioInputController.startWarmup();
                    appContainer.threadManager.executeAudio(() -> {
                        try {
                            appContainer.audioInputController.setupSpeechRecognizer();
                            if (appContainer.settingsManager.isUsingRealtimeAudioInput()) {
                                runOnUiThread(() -> {
                                    hideWarmupIndicator();
                                    isWarmingUp = false;
                                    statusTextView.setText(getString(R.string.status_listening));
                                    if (appContainer.turnManager != null) {
                                        appContainer.turnManager.setState(TurnManager.State.LISTENING);
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
                                if (appContainer.turnManager != null) appContainer.turnManager.setState(TurnManager.State.LISTENING);
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
        appContainer.audioInputController.setSttRunning(false);
        appContainer.audioInputController.cleanupSttForReinit(); // Force stop
        
        if (appContainer.toolContext != null) appContainer.toolContext.updateQiContext(null);
        // We shouldn't shutdown whole appContainer here, just stop services?
        // Original code called shutdownManagers() which shut down perception, dashboard, touch, nav.
        if (appContainer.perceptionService != null) appContainer.perceptionService.shutdown();
        if (appContainer.dashboardManager != null) appContainer.dashboardManager.shutdown();
        if (appContainer.touchSensorManager != null) appContainer.touchSensorManager.shutdown();
        if (appContainer.navigationServiceManager != null) appContainer.navigationServiceManager.shutdown();

        try {
            appContainer.gestureController.shutdown();
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
    
    public void startContinuousRecognition() {
        appContainer.audioInputController.startContinuousRecognition();
    }

    public void stopContinuousRecognition() {
        appContainer.audioInputController.stopContinuousRecognition();
    }

    public void sendMessageToRealtimeAPI(String text, boolean requestResponse, boolean allowInterrupt) {
        if (appContainer.sessionManager == null || !appContainer.sessionManager.isConnected()) {
            Log.e(TAG, "WebSocket is not connected.");
            if (requestResponse) {
                runOnUiThread(() -> addMessage(getString(R.string.error_not_connected), ChatMessage.Sender.ROBOT));
            }
            return;
        }

        if (allowInterrupt && requestResponse && appContainer.turnManager != null && appContainer.turnManager.getState() == TurnManager.State.SPEAKING) {
            runOnUiThread(() -> {
                // Simple explicit interrupt via controller if playing
                if (isAudioPlaying || (appContainer.audioPlayer != null && appContainer.audioPlayer.isPlaying())) {
                    appContainer.interruptController.interruptSpeech();
                }
            });
        }

        if (requestResponse && appContainer.turnManager != null) {
            runOnUiThread(() -> appContainer.turnManager.setState(TurnManager.State.THINKING));
        }

        appContainer.threadManager.executeNetwork(() -> {
            try {
                boolean sentItem = appContainer.sessionManager.sendUserTextMessage(text);
                if (!sentItem) {
                    Log.e(TAG, "Failed to send message - WebSocket connection broken");
                    runOnUiThread(() -> {
                        addMessage(getString(R.string.error_connection_lost_message), ChatMessage.Sender.ROBOT);
                        if (appContainer.turnManager != null) appContainer.turnManager.setState(TurnManager.State.IDLE);
                    });
                    return;
                }

                if (requestResponse) {
                    if (isResponseGenerating) {
                        appContainer.interruptController.interruptSpeech();
                        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                    }
                    else if (isAudioPlaying && allowInterrupt) {
                        if (appContainer.audioPlayer != null) {
                            appContainer.audioPlayer.interruptNow();
                            isAudioPlaying = false;
                        }
                    }
                    
                    isResponseGenerating = true;
                    boolean sentResponse = appContainer.sessionManager.requestResponse();
                    if (!sentResponse) {
                        isResponseGenerating = false;
                        Log.e(TAG, "Failed to send response request");
                        runOnUiThread(() -> addMessage(getString(R.string.error_connection_lost_response), ChatMessage.Sender.ROBOT));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Exception in sendMessageToRealtimeAPI", e);
                if (requestResponse) {
                    isResponseGenerating = false;
                    runOnUiThread(() -> {
                        addMessage(getString(R.string.error_processing_message), ChatMessage.Sender.ROBOT);
                        if (appContainer.turnManager != null) appContainer.turnManager.setState(TurnManager.State.IDLE);
                    });
                }
            }
        });
    }

    public void sendToolResult(String callId, String result) {
        if (appContainer.sessionManager == null || !appContainer.sessionManager.isConnected()) return;
        try {
            expectingFinalAnswerAfterToolCall = true;
            boolean sentTool = appContainer.sessionManager.sendToolResult(callId, result);
            if (!sentTool) {
                Log.e(TAG, "Failed to send tool result");
                return;
            }
            isResponseGenerating = true;
            boolean sentToolResponse = appContainer.sessionManager.requestResponse();
            if (!sentToolResponse) {
                isResponseGenerating = false;
                Log.e(TAG, "Failed to send tool response request");
                return;
            }
            if (appContainer.turnManager != null && appContainer.turnManager.getState() != TurnManager.State.SPEAKING) {
                appContainer.turnManager.setState(TurnManager.State.THINKING);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending tool result", e);
            isResponseGenerating = false;
        }
    }

    public void startExplainGesturesLoop() {
        if (appContainer.robotFocusManager.getQiContext() == null) return;
        if (appContainer.navigationServiceManager != null && appContainer.navigationServiceManager.areGesturesSuppressed()) return;
        
        appContainer.gestureController.start(appContainer.robotFocusManager.getQiContext(),
                () -> appContainer.turnManager != null && appContainer.turnManager.getState() == TurnManager.State.SPEAKING && appContainer.robotFocusManager.getQiContext() != null && 
                      (appContainer.navigationServiceManager == null || !appContainer.navigationServiceManager.areGesturesSuppressed()),
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
        appContainer.uiHelper.addMessage(text, sender);
    }

    public void addFunctionCall(String functionName, String args) {
        appContainer.uiHelper.addFunctionCall(functionName, args);
    }

    public void updateFunctionCallResult(String result) {
        appContainer.uiHelper.updateFunctionCallResult(result);
    }
    
    public void deleteSessionImages() {
        synchronized (sessionImagePaths) {
            for (String p : sessionImagePaths) {
                try {
                    if (p != null) {
                        boolean deleted = new java.io.File(p).delete();
                        if (!deleted) {
                            Log.w(TAG, "Failed to delete session image: " + p);
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

    public void hideWarmupIndicator() {
        runOnUiThread(() -> warmupIndicatorLayout.setVisibility(View.GONE));
    }

    private void mute() {
        appContainer.audioInputController.mute();
    }
    
    private void unmute() {
        appContainer.audioInputController.unmute();
    }
    
    public void handleServiceStateChange(String mode) {
        if (appContainer.navigationServiceManager != null) {
            appContainer.navigationServiceManager.handleServiceStateChange(mode);
        }
    }

    public void addImageMessage(String imagePath) {
        appContainer.uiHelper.addImageMessage(imagePath);
    }
    
    public void addImageToSessionCleanup(String imagePath) {
        synchronized (sessionImagePaths) {
            sessionImagePaths.add(imagePath);
        }
    }
    
    public void updateNavigationStatus(String mapStatus, String localizationStatus) {
        appContainer.mapUiManager.updateMapStatus(mapStatus);
        appContainer.mapUiManager.updateLocalizationStatus(localizationStatus);
        appContainer.mapUiManager.updateMapPreview(appContainer.navigationServiceManager, appContainer.locationProvider);
    }

    public void updateMapPreview() {
        appContainer.mapUiManager.updateMapPreview(appContainer.navigationServiceManager, appContainer.locationProvider);
    }
    
    public NavigationServiceManager getNavigationServiceManager() {
        return appContainer.navigationServiceManager;
    }
    
    public PerceptionService getPerceptionService() {
        return appContainer.perceptionService;
    }
    
    public GestureController getGestureController() {
        return appContainer.gestureController;
    }
    
    public SettingsManager getSettingsManager() {
        return appContainer.settingsManager;
    }

    public RealtimeSessionManager getSessionManager() {
        return appContainer.sessionManager;
    }

    public RobotController getRobotController() {
        return appContainer.robotFocusManager.getRobotController();
    }

    public Object getQiContext() { return appContainer.robotFocusManager.getQiContext(); }
    public boolean isMuted() { return appContainer.audioInputController.isMuted(); }
    public boolean isSttRunning() { return appContainer.audioInputController.isSttRunning(); }
    public void setSttRunning(boolean running) { appContainer.audioInputController.setSttRunning(running); }
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
            appContainer.chatAdapter.notifyItemChanged(messageList.size() - 1);
        }
    }

    public void handleUserSpeechStopped(String itemId) {
        appContainer.uiHelper.handleUserSpeechStopped(itemId);
    }
    
    public void handleUserTranscriptCompleted(String itemId, String transcript) {
        appContainer.uiHelper.handleUserTranscriptCompleted(itemId, transcript);
    }
    
    public void handleUserTranscriptFailed(String itemId, JSONObject error) {
        appContainer.uiHelper.handleUserTranscriptFailed(itemId, error);
    }
    
    public void setStatusText(String text) {
        appContainer.uiHelper.setStatusText(text);
    }
    
    public void clearMessages() {
        appContainer.uiHelper.clearMessages();
    }
    
    public void setConnectionCallback(WebSocketConnectionCallback callback) {
        this.connectionCallback = callback;
    }
}

