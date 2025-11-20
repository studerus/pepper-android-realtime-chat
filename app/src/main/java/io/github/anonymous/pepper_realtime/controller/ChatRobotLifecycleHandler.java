package io.github.anonymous.pepper_realtime.controller;

import android.util.Log;
import java.io.File;

import io.github.anonymous.pepper_realtime.R;
import io.github.anonymous.pepper_realtime.manager.AppContainer;
import io.github.anonymous.pepper_realtime.manager.TouchSensorManager;
import io.github.anonymous.pepper_realtime.manager.TurnManager;
import io.github.anonymous.pepper_realtime.network.WebSocketConnectionCallback;
import io.github.anonymous.pepper_realtime.ui.ChatActivity;
import io.github.anonymous.pepper_realtime.ui.ChatMessage;
import io.github.anonymous.pepper_realtime.ui.ChatViewModel;

public class ChatRobotLifecycleHandler implements RobotFocusManager.Listener {
    private static final String TAG = "ChatRobotLifecycleHandler";

    private final ChatActivity activity;
    private final AppContainer appContainer;
    private final ChatViewModel viewModel;
    private final Object robotLifecycleLock = new Object();

    private boolean hasFocusInitialized = false;
    private boolean hadFocusLostSinceInit = false;

    public ChatRobotLifecycleHandler(ChatActivity activity, AppContainer appContainer, ChatViewModel viewModel) {
        this.activity = activity;
        this.appContainer = appContainer;
        this.viewModel = viewModel;
    }

    @Override
    public void onRobotReady(Object robotContext) {
        synchronized (robotLifecycleLock) {
            Log.i(TAG, "handleRobotReady: Acquired lifecycle lock");

            if (appContainer.locationProvider != null) {
                appContainer.locationProvider.refreshLocations(activity);
            }

            activity.runOnUiThread(() -> {
                boolean hasMap = new File(activity.getFilesDir(), "maps/default_map.map").exists();
                activity.updateNavigationStatus(
                        activity.getString(hasMap ? R.string.nav_map_saved : R.string.nav_map_none),
                        activity.getString(R.string.nav_localization_not_running));
            });

            appContainer.audioInputController.cleanupSttForReinit();

            if (appContainer.toolContext != null)
                appContainer.toolContext.updateQiContext(robotContext);

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
                        activity.runOnUiThread(() -> {
                            viewModel.addMessage(new ChatMessage(touchMessage, ChatMessage.Sender.USER));
                            appContainer.sessionController.sendMessageToRealtimeAPI(touchMessage, true, true);
                        });
                    }

                    @Override
                    public void onSensorReleased(String sensorName, Object touchState) {
                    }
                });
                appContainer.touchSensorManager.initialize(robotContext);
            }

            if (appContainer.navigationServiceManager != null) {
                appContainer.navigationServiceManager.setDependencies(appContainer.perceptionService,
                        appContainer.touchSensorManager, appContainer.gestureController);
            }

            // Connect WebSocket on first init OR after focus regain
            boolean needsReconnect = !hasFocusInitialized || hadFocusLostSinceInit;

            if (needsReconnect) {
                // Clear chat and session state if this is a reconnect after focus lost
                if (hadFocusLostSinceInit) {
                    Log.i(TAG, "Reconnecting after focus lost - clearing chat history");
                    activity.runOnUiThread(viewModel::clearMessages);
                    appContainer.sessionImageManager.deleteAllImages();
                    hadFocusLostSinceInit = false;
                }

                hasFocusInitialized = true;
                viewModel.setWarmingUp(true);
                viewModel.setLastChatBubbleResponseId(null);
                appContainer.volumeController.setVolume(activity, appContainer.settingsManager.getVolume());
                activity.showWarmupIndicator();

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
                                    activity.runOnUiThread(() -> {
                                        activity.hideWarmupIndicator();
                                        viewModel.setWarmingUp(false);
                                        viewModel.setStatusText(activity.getString(R.string.status_listening));
                                        if (appContainer.turnManager != null) {
                                            appContainer.turnManager.setState(TurnManager.State.LISTENING);
                                        }
                                    });
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "❌ STT setup failed", e);
                                activity.runOnUiThread(() -> {
                                    activity.hideWarmupIndicator();
                                    viewModel.setWarmingUp(false);
                                    viewModel.addMessage(new ChatMessage(activity.getString(R.string.warmup_failed_msg),
                                            ChatMessage.Sender.ROBOT));
                                    viewModel.setStatusText(activity.getString(R.string.ready_sr_lazy_init));
                                    if (appContainer.turnManager != null)
                                        appContainer.turnManager.setState(TurnManager.State.LISTENING);
                                });
                            }
                        });
                    }

                    @Override
                    public void onError(Throwable error) {
                        Log.e(TAG, "WebSocket connection failed", error);
                        activity.hideWarmupIndicator();
                        viewModel.setWarmingUp(false);
                        activity.runOnUiThread(() -> {
                            viewModel.addMessage(
                                    new ChatMessage(activity.getString(R.string.setup_error_during, error.getMessage()),
                                            ChatMessage.Sender.ROBOT));
                            viewModel.setStatusText(activity.getString(R.string.error_connection_failed));
                        });
                    }
                });
            }

            Log.i(TAG, "handleRobotReady: Released lifecycle lock");
        }
    }

    @Override
    public void onRobotFocusLost() {
        synchronized (robotLifecycleLock) {
            Log.i(TAG, "handleRobotFocusLost: Acquired lifecycle lock");

            // Mark that we lost focus after initialization (important for reconnect logic)
            if (hasFocusInitialized) {
                hadFocusLostSinceInit = true;
                Log.i(TAG, "Robot focus lost after initialization - will reconnect on regain");
            }

            appContainer.audioInputController.setSttRunning(false);
            appContainer.audioInputController.cleanupSttForReinit();

            if (appContainer.toolContext != null)
                appContainer.toolContext.updateQiContext(null);

            // Shutdown services only if they are initialized
            if (appContainer.perceptionService != null && appContainer.perceptionService.isInitialized()) {
                appContainer.perceptionService.shutdown();
            }
            if (appContainer.dashboardManager != null) {
                appContainer.dashboardManager.shutdown();
            }
            if (appContainer.touchSensorManager != null) {
                appContainer.touchSensorManager.shutdown();
            }
            if (appContainer.navigationServiceManager != null) {
                appContainer.navigationServiceManager.shutdown();
            }

            // Pause gestures but don't shutdown the executor
            try {
                if (appContainer.gestureController != null) {
                    appContainer.gestureController.stopNow();
                }
            } catch (Exception e) {
                Log.w(TAG, "Error stopping GestureController", e);
            }

            activity.runOnUiThread(() -> {
                viewModel.setStatusText(activity.getString(R.string.robot_focus_lost_message));
                boolean hasMap = new File(activity.getFilesDir(), "maps/default_map.map").exists();
                activity.updateNavigationStatus(
                        activity.getString(hasMap ? R.string.nav_map_saved : R.string.nav_map_none),
                        activity.getString(R.string.nav_localization_stopped));
            });

            Log.i(TAG, "handleRobotFocusLost: Released lifecycle lock");
        }
    }

    @Override
    public void onRobotInitializationFailed(String error) {
        activity.runOnUiThread(() -> viewModel.setStatusText(error));
    }

    public boolean isFocusAvailable() {
        return appContainer.robotFocusManager.isFocusAvailable();
    }
}
