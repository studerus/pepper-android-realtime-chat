package io.github.anonymous.pepper_realtime.ui;

import android.Manifest;
import io.github.anonymous.pepper_realtime.manager.PermissionManager;
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
import io.github.anonymous.pepper_realtime.controller.RobotFocusManager;
import io.github.anonymous.pepper_realtime.manager.AppContainer;
import io.github.anonymous.pepper_realtime.manager.AudioPlayer;
import io.github.anonymous.pepper_realtime.manager.SettingsManager;
import io.github.anonymous.pepper_realtime.manager.TurnManager;
import io.github.anonymous.pepper_realtime.network.RealtimeSessionManager;
import io.github.anonymous.pepper_realtime.network.WebSocketConnectionCallback;
import io.github.anonymous.pepper_realtime.robot.RobotController;
import io.github.anonymous.pepper_realtime.manager.NavigationServiceManager;
import io.github.anonymous.pepper_realtime.manager.TouchSensorManager;
import io.github.anonymous.pepper_realtime.controller.GestureController;
import io.github.anonymous.pepper_realtime.service.PerceptionService;
import io.github.anonymous.pepper_realtime.ui.ChatMessage;
import androidx.lifecycle.ViewModelProvider;

import okhttp3.Response;
import okio.ByteString;

public class ChatActivity extends AppCompatActivity {

    private static final String TAG = "ChatActivity";

    // UI Components
    private TextView statusTextView;
    private LinearLayout warmupIndicatorLayout;
    private FloatingActionButton fabInterrupt;

    // App Container
    private AppContainer appContainer;

    // ViewModel
    private ChatViewModel viewModel;

    // State
    private final Map<String, ChatMessage> pendingUserTranscripts = new HashMap<>();

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

        // Initialize ViewModel
        viewModel = new ViewModelProvider(this).get(ChatViewModel.class);

        // Observe ViewModel
        viewModel.getStatusText().observe(this, text -> {
            if (statusTextView != null)
                statusTextView.setText(text);
        });

        viewModel.getIsWarmingUp().observe(this, isWarmingUp -> {
            if (isWarmingUp)
                showWarmupIndicator();
            else
                hideWarmupIndicator();
        });

        viewModel.getMessageList().observe(this, messages -> {
            if (appContainer != null && appContainer.chatAdapter != null) {
                appContainer.chatAdapter.setMessages(messages);
            }
        });

        // Initialize AppContainer (creates all managers and controllers)
        appContainer = new AppContainer(this, viewModel, pendingUserTranscripts);

        // Setup Listeners using AppContainer components
        setupSettingsListener();
        setupUiListeners();
        setupPermissionCallback();

        // Register Robot Lifecycle
        appContainer.robotFocusManager.register();

        // Request Permissions
        appContainer.permissionManager.checkAndRequestPermissions(this);
    }

    private void setupPermissionCallback() {
        appContainer.permissionManager.setCallback(new PermissionManager.PermissionCallback() {
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
                runOnUiThread(() -> viewModel.setStatusText(getString(R.string.status_updating_recognizer)));
                appContainer.audioInputController.stopContinuousRecognition();
                appContainer.audioInputController.reinitializeSpeechRecognizerForSettings();
                appContainer.audioInputController.startContinuousRecognition();
            }

            @Override
            public void onVolumeChanged(int volume) {
                appContainer.volumeController.setVolume(ChatActivity.this, volume);
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

    private void setupUiListeners() {
        fabInterrupt.setOnClickListener(v -> {
            try {
                appContainer.interruptController.interruptSpeech();
                if (appContainer.turnManager != null)
                    appContainer.turnManager.setState(TurnManager.State.LISTENING);
            } catch (Exception e) {
                Log.e(TAG, "Interrupt failed", e);
            }
        });

        statusTextView.setOnClickListener(v -> {
            try {
                if (appContainer.turnManager == null)
                    return;
                TurnManager.State currentState = appContainer.turnManager.getState();

                if (currentState == TurnManager.State.SPEAKING) {
                    if (Boolean.TRUE.equals(viewModel.getIsResponseGenerating().getValue())
                            || Boolean.TRUE.equals(viewModel.getIsAudioPlaying().getValue())
                            || (appContainer.audioPlayer != null && appContainer.audioPlayer.isPlaying())) {
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
        appContainer.lifecycleController.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        appContainer.lifecycleController.onResume(appContainer.robotFocusManager);
    }

    @Override
    protected void onDestroy() {
        appContainer.shutdown();
        appContainer.sessionImageManager.deleteAllImages();
        super.onDestroy();
    }

    public void updateNavigationStatus(String mapStatus, String localizationStatus) {
        appContainer.mapUiManager.updateMapStatus(mapStatus);
        appContainer.mapUiManager.updateLocalizationStatus(localizationStatus);
        appContainer.mapUiManager.updateMapPreview(appContainer.navigationServiceManager,
                appContainer.locationProvider);
    }

    public void updateMapPreview() {
        appContainer.mapUiManager.updateMapPreview(appContainer.navigationServiceManager,
                appContainer.locationProvider);
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

    public Object getQiContext() {
        return appContainer.robotFocusManager.getQiContext();
    }

    // Delegation methods for controllers/tools
    public void startContinuousRecognition() {
        appContainer.audioInputController.startContinuousRecognition();
    }

    public void stopContinuousRecognition() {
        appContainer.audioInputController.stopContinuousRecognition();
    }

    public void startExplainGesturesLoop() {
        if (appContainer.robotFocusManager.getQiContext() == null)
            return;
        if (appContainer.navigationServiceManager != null
                && appContainer.navigationServiceManager.areGesturesSuppressed())
            return;

        appContainer.gestureController.start(appContainer.robotFocusManager.getQiContext(),
                () -> appContainer.turnManager != null
                        && appContainer.turnManager.getState() == TurnManager.State.SPEAKING
                        && appContainer.robotFocusManager.getQiContext() != null &&
                        (appContainer.navigationServiceManager == null
                                || !appContainer.navigationServiceManager.areGesturesSuppressed()),
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

    public void showWarmupIndicator() {
        runOnUiThread(() -> {
            warmupIndicatorLayout.setVisibility(View.VISIBLE);
            viewModel.setStatusText(getString(R.string.status_warming_up));
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

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        appContainer.permissionManager.handlePermissionResult(requestCode, grantResults);
    }
}
