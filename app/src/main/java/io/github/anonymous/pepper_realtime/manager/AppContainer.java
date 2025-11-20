package io.github.anonymous.pepper_realtime.manager;

import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.anonymous.pepper_realtime.ui.ChatActivity;
import io.github.anonymous.pepper_realtime.R;
import io.github.anonymous.pepper_realtime.controller.AudioInputController;
import io.github.anonymous.pepper_realtime.controller.AudioVolumeController;
import io.github.anonymous.pepper_realtime.controller.ChatInterruptController;
import io.github.anonymous.pepper_realtime.controller.ChatLifecycleController;
import io.github.anonymous.pepper_realtime.ui.ChatMenuController;
import io.github.anonymous.pepper_realtime.controller.ChatRealtimeHandler;
import io.github.anonymous.pepper_realtime.controller.ChatRobotLifecycleHandler;
import io.github.anonymous.pepper_realtime.controller.ChatSessionController;
import io.github.anonymous.pepper_realtime.controller.ChatSpeechListener;
import io.github.anonymous.pepper_realtime.controller.ChatTurnListener;
import io.github.anonymous.pepper_realtime.controller.GestureController;
import io.github.anonymous.pepper_realtime.controller.MovementController;
import io.github.anonymous.pepper_realtime.controller.RobotFocusManager;
import io.github.anonymous.pepper_realtime.data.LocationProvider;
import io.github.anonymous.pepper_realtime.network.RealtimeEventHandler;
import io.github.anonymous.pepper_realtime.network.RealtimeSessionManager;
import io.github.anonymous.pepper_realtime.service.PerceptionService;
import io.github.anonymous.pepper_realtime.service.VisionService;
import io.github.anonymous.pepper_realtime.tools.ToolContext;
import io.github.anonymous.pepper_realtime.tools.ToolRegistry;
import io.github.anonymous.pepper_realtime.ui.ChatMenuController;
import io.github.anonymous.pepper_realtime.ui.ChatMessage;
import io.github.anonymous.pepper_realtime.ui.ChatMessageAdapter;
import io.github.anonymous.pepper_realtime.ui.ChatUiHelper;
import io.github.anonymous.pepper_realtime.ui.ChatViewModel;
import io.github.anonymous.pepper_realtime.ui.MapPreviewView;
import io.github.anonymous.pepper_realtime.ui.MapUiManager;

public class AppContainer {

    private static final String TAG = "AppContainer";

    // Dependencies
    public final ApiKeyManager keyManager;
    public final MapUiManager mapUiManager;
    public final ChatMessageAdapter chatAdapter;
    public final RealtimeSessionManager sessionManager;
    public final SettingsManager settingsManager;
    public final AudioInputController audioInputController;
    public final ChatMenuController chatMenuController;
    public final RobotFocusManager robotFocusManager;
    public final AudioPlayer audioPlayer;
    public final TurnManager turnManager;
    public final ChatInterruptController interruptController;
    public final NavigationServiceManager navigationServiceManager;
    public final DashboardManager dashboardManager;
    public final PerceptionService perceptionService;
    public final VisionService visionService;
    public final TouchSensorManager touchSensorManager;
    public final ThreadManager threadManager;
    public final GestureController gestureController;
    public final ToolRegistry toolRegistry;
    public final ToolContext toolContext;
    public final ChatSessionController sessionController;
    public final ChatUiHelper uiHelper;
    public final LocationProvider locationProvider;
    public final RealtimeEventHandler eventHandler;

    // Phase 3: Lifecycle & Resource Management
    public final PermissionManager permissionManager;
    public final SessionImageManager sessionImageManager;
    public final AudioVolumeController volumeController;
    public final ChatLifecycleController lifecycleController;

    public AppContainer(ChatActivity activity,
            ChatViewModel viewModel,
            Map<String, ChatMessage> pendingUserTranscripts) {

        Log.i(TAG, "Initializing AppContainer...");

        this.keyManager = new ApiKeyManager(activity);

        // Initialize Map UI Manager
        TextView mapStatusTextView = activity.findViewById(R.id.mapStatusTextView);
        TextView localizationStatusTextView = activity.findViewById(R.id.localizationStatusTextView);
        FrameLayout mapPreviewContainer = activity.findViewById(R.id.map_preview_container);
        MapPreviewView mapPreviewView = activity.findViewById(R.id.map_preview_view);
        this.locationProvider = new LocationProvider();
        MaterialToolbar topAppBar = activity.findViewById(R.id.topAppBar);
        DrawerLayout drawerLayout = activity.findViewById(R.id.drawer_layout);

        this.mapUiManager = new MapUiManager(activity, mapStatusTextView, localizationStatusTextView,
                mapPreviewContainer, mapPreviewView, drawerLayout, topAppBar);

        // Initialize RecyclerView Adapter
        this.chatAdapter = new ChatMessageAdapter(viewModel.getMessageList().getValue());
        RecyclerView chatRecyclerView = activity.findViewById(R.id.chatRecyclerView);
        LinearLayoutManager layoutManager = new LinearLayoutManager(activity);
        chatRecyclerView.setLayoutManager(layoutManager);
        chatRecyclerView.setAdapter(chatAdapter);

        // Initialize Session Manager
        this.sessionManager = new RealtimeSessionManager();
        // Note: Listener and config callback must be set by Activity

        // Initialize Settings Manager
        NavigationView navigationView = activity.findViewById(R.id.navigation_view);
        this.settingsManager = new SettingsManager(activity, navigationView);
        // Note: Listener must be set by Activity

        // Initialize ThreadManager & GestureController
        this.threadManager = ThreadManager.getInstance();
        this.gestureController = new GestureController();

        // Initialize Controllers
        TextView statusTextView = activity.findViewById(R.id.statusTextView);
        FloatingActionButton fabInterrupt = activity.findViewById(R.id.fab_interrupt);

        // Dashboard & Perception
        this.perceptionService = new PerceptionService();
        View dashboardOverlay = activity.findViewById(R.id.dashboard_overlay);
        if (dashboardOverlay != null) {
            this.dashboardManager = new DashboardManager(activity, dashboardOverlay);
            this.dashboardManager.initialize(perceptionService);
        } else {
            this.dashboardManager = null;
        }

        this.chatMenuController = new ChatMenuController(activity, drawerLayout, mapUiManager,
                dashboardManager, settingsManager);
        this.chatMenuController.setupSettingsMenu();

        // Initialize Robot Lifecycle Handler
        ChatRobotLifecycleHandler lifecycleHandler = new ChatRobotLifecycleHandler(activity, this, viewModel);
        this.robotFocusManager = new RobotFocusManager(activity);
        this.robotFocusManager.setListener(lifecycleHandler);

        // Initialize Services first (needed by other components)
        this.visionService = new VisionService(activity);
        this.touchSensorManager = new TouchSensorManager();

        // Initialize Core Logic
        this.audioPlayer = new AudioPlayer();

        // Initialize Audio Input early (needed by TurnManager)
        this.audioInputController = new AudioInputController(activity, settingsManager, keyManager, sessionManager,
                threadManager, statusTextView, fabInterrupt);

        this.turnManager = new TurnManager(new ChatTurnListener(activity,
                activity.findViewById(R.id.statusTextView),
                activity.findViewById(R.id.fab_interrupt),
                gestureController,
                audioInputController));

        // Initialize Interrupt Controller
        this.interruptController = new ChatInterruptController(viewModel, sessionManager, audioPlayer,
                gestureController, audioInputController);

        // Initialize Tool System
        MovementController movementController = new MovementController();
        this.navigationServiceManager = new NavigationServiceManager(movementController);

        // Initialize Realtime Event Handler early (needed by ChatSessionController)
        this.toolRegistry = new ToolRegistry();
        // Note: toolContext needs sessionController, so we create it after
        ChatRealtimeHandler realtimeHandler = new ChatRealtimeHandler(activity, viewModel, audioPlayer, turnManager,
                threadManager, toolRegistry,
                null); // toolContext will be set later
        this.eventHandler = new RealtimeEventHandler(realtimeHandler);

        // Initialize Session Controller (needs eventHandler)
        this.sessionController = new ChatSessionController(activity, viewModel, sessionManager, settingsManager,
                keyManager, audioInputController, threadManager, gestureController, turnManager, interruptController,
                audioPlayer, eventHandler, this);

        // Now create toolContext with sessionController
        this.toolContext = new ToolContext(activity, robotFocusManager, keyManager, movementController,
                navigationServiceManager, perceptionService, dashboardManager, touchSensorManager,
                gestureController, locationProvider, sessionController, this);

        // Update realtimeHandler with toolContext
        realtimeHandler.setToolContext(toolContext);

        // Setup Speech Listener (Circular dependency handled by setting it after
        // creation)
        ChatSpeechListener speechListener = new ChatSpeechListener(activity, turnManager, statusTextView,
                audioInputController.getSttWarmupStartTime(), sessionController, audioInputController, viewModel);
        audioInputController.setSpeechListener(speechListener);

        // Resolve circular dependency
        realtimeHandler.setSessionController(sessionController);

        // Initialize Chat UI Helper
        this.uiHelper = new ChatUiHelper(activity, viewModel, chatAdapter, chatRecyclerView,
                pendingUserTranscripts);

        // Set session dependencies
        this.sessionManager.setSessionDependencies(toolRegistry, toolContext, settingsManager, keyManager);

        // Phase 3: Initialize lifecycle & resource managers
        this.permissionManager = new PermissionManager();
        this.sessionImageManager = new SessionImageManager();
        this.volumeController = new AudioVolumeController();
        this.lifecycleController = new ChatLifecycleController(
                activity,
                viewModel,
                audioInputController,
                sessionController,
                perceptionService,
                visionService,
                touchSensorManager,
                gestureController,
                audioPlayer,
                turnManager,
                sessionImageManager);

        Log.i(TAG, "AppContainer initialized.");
    }

    public void shutdown() {
        audioInputController.shutdown();
        threadManager.shutdown();

        if (audioPlayer != null)
            audioPlayer.setListener(null);
        if (sessionManager != null)
            sessionManager.setListener(null);
        if (settingsManager != null)
            settingsManager.setListener(null);
        if (toolContext != null)
            toolContext.updateQiContext(null);

        if (perceptionService != null) {
            perceptionService.shutdown();
        }
        if (dashboardManager != null) {
            dashboardManager.shutdown();
        }
        if (touchSensorManager != null) {
            touchSensorManager.shutdown();
        }
        if (navigationServiceManager != null) {
            navigationServiceManager.shutdown();
        }
        // chatMenuController cleanup not needed
        sessionController.disconnectWebSocket();
        if (audioPlayer != null)
            audioPlayer.release();
        try {
            gestureController.shutdown();
        } catch (Exception ignored) {
        }

        robotFocusManager.unregister();
    }
}
