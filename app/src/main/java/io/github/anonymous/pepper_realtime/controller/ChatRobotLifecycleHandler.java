package io.github.anonymous.pepper_realtime.controller;

import android.util.Log;
import java.io.File;

import io.github.anonymous.pepper_realtime.R;
import io.github.anonymous.pepper_realtime.manager.TouchSensorManager;
import io.github.anonymous.pepper_realtime.manager.TurnManager;
import io.github.anonymous.pepper_realtime.network.WebSocketConnectionCallback;
import io.github.anonymous.pepper_realtime.ui.ChatActivity;
import io.github.anonymous.pepper_realtime.ui.ChatMessage;
import io.github.anonymous.pepper_realtime.ui.ChatViewModel;

public class ChatRobotLifecycleHandler implements RobotFocusManager.Listener {
    private static final String TAG = "ChatRobotLifecycleHandler";

    private final ChatActivity activity;
    private final ChatViewModel viewModel;
    private final Object robotLifecycleLock = new Object();

    private boolean hasFocusInitialized = false;
    private boolean hadFocusLostSinceInit = false;

    public ChatRobotLifecycleHandler(ChatActivity activity, ChatViewModel viewModel) {
        this.activity = activity;
        this.viewModel = viewModel;
    }

    @Override
    public void onRobotReady(Object robotContext) {
        synchronized (robotLifecycleLock) {
            Log.i(TAG, "handleRobotReady: Acquired lifecycle lock");

            if (activity.getLocationProvider() != null) {
                activity.getLocationProvider().refreshLocations(activity);
            }

            activity.runOnUiThread(() -> {
                boolean hasMap = new File(activity.getFilesDir(), "maps/default_map.map").exists();
                viewModel.setMapStatus(activity.getString(hasMap ? R.string.nav_map_saved : R.string.nav_map_none));
                viewModel.setLocalizationStatus(activity.getString(R.string.nav_localization_not_running));
            });

            activity.getAudioInputController().cleanupSttForReinit();

            if (activity.getToolContext() != null)
                activity.getToolContext().updateQiContext(robotContext);

            if (activity.getDashboardManager() != null) {
                activity.getDashboardManager().initialize(activity.getPerceptionService());
            }

            if (activity.getPerceptionService() != null) {
                activity.getPerceptionService().initialize(robotContext);
            }

            if (activity.getVisionService() != null) {
                activity.getVisionService().initialize(robotContext);
            }

            if (activity.getTouchSensorManager() != null) {
                activity.getTouchSensorManager().setListener(new TouchSensorManager.TouchEventListener() {
                    @Override
                    public void onSensorTouched(String sensorName, Object touchState) {
                        Log.i(TAG, "Touch sensor " + sensorName + " touched");
                        String touchMessage = TouchSensorManager.createTouchMessage(sensorName);
                        viewModel.addMessage(new ChatMessage(touchMessage, ChatMessage.Sender.USER));
                        activity.getSessionController().sendMessageToRealtimeAPI(touchMessage, true, true);
                    }

                    @Override
                    public void onSensorReleased(String sensorName, Object touchState) {
                    }
                });
                activity.getTouchSensorManager().initialize(robotContext);
            }

            if (activity.getNavigationServiceManager() != null) {
                activity.getNavigationServiceManager().setDependencies(activity.getPerceptionService(),
                        activity.getTouchSensorManager(), activity.getGestureController());
            }

            // Connect WebSocket on first init OR after focus regain
            boolean needsReconnect = !hasFocusInitialized || hadFocusLostSinceInit;

            if (needsReconnect) {
                // Clear chat and session state if this is a reconnect after focus lost
                if (hadFocusLostSinceInit) {
                    Log.i(TAG, "Reconnecting after focus lost - clearing chat history");
                    viewModel.clearMessages();
                    activity.getSessionImageManager().deleteAllImages();
                    hadFocusLostSinceInit = false;
                }

                hasFocusInitialized = true;
                viewModel.setWarmingUp(true);
                viewModel.setLastChatBubbleResponseId(null);
                activity.getVolumeController().setVolume(activity, activity.getSettingsManager().getVolume());
                // activity.showWarmupIndicator(); // Handled by observing isWarmingUp

                Log.i(TAG, "Starting WebSocket connection...");
                activity.getSessionController().connectWebSocket(new WebSocketConnectionCallback() {
                    @Override
                    public void onSuccess() {
                        Log.i(TAG, "WebSocket connected successfully, starting STT warmup...");
                        activity.getAudioInputController().startWarmup();
                        activity.getThreadManager().executeAudio(() -> {
                            try {
                                activity.getAudioInputController().setupSpeechRecognizer();
                                if (activity.getSettingsManager().isUsingRealtimeAudioInput()) {
                                    // activity.hideWarmupIndicator(); // Handled by setWarmingUp
                                    viewModel.setWarmingUp(false);
                                    viewModel.setStatusText(activity.getString(R.string.status_listening));
                                    if (activity.getTurnManager() != null) {
                                        activity.getTurnManager().setState(TurnManager.State.LISTENING);
                                    }
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "❌ STT setup failed", e);
                                // activity.hideWarmupIndicator(); // Handled by setWarmingUp
                                viewModel.setWarmingUp(false);
                                viewModel.addMessage(new ChatMessage(activity.getString(R.string.warmup_failed_msg),
                                        ChatMessage.Sender.ROBOT));
                                viewModel.setStatusText(activity.getString(R.string.ready_sr_lazy_init));
                                if (activity.getTurnManager() != null)
                                    activity.getTurnManager().setState(TurnManager.State.LISTENING);
                            }
                        });
                    }

                    @Override
                    public void onError(Throwable error) {
                        Log.e(TAG, "WebSocket connection failed", error);
                        // activity.hideWarmupIndicator(); // Handled by setWarmingUp
                        viewModel.setWarmingUp(false);
                        viewModel.addMessage(
                                new ChatMessage(activity.getString(R.string.setup_error_during, error.getMessage()),
                                        ChatMessage.Sender.ROBOT));
                        viewModel.setStatusText(activity.getString(R.string.error_connection_failed));
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

            activity.getAudioInputController().setSttRunning(false);
            activity.getAudioInputController().cleanupSttForReinit();

            if (activity.getToolContext() != null)
                activity.getToolContext().updateQiContext(null);

            // Shutdown services only if they are initialized
            if (activity.getPerceptionService() != null && activity.getPerceptionService().isInitialized()) {
                activity.getPerceptionService().shutdown();
            }
            if (activity.getDashboardManager() != null) {
                activity.getDashboardManager().shutdown();
            }
            if (activity.getTouchSensorManager() != null) {
                activity.getTouchSensorManager().shutdown();
            }
            if (activity.getNavigationServiceManager() != null) {
                activity.getNavigationServiceManager().shutdown();
            }

            // Pause gestures but don't shutdown the executor
            try {
                if (activity.getGestureController() != null) {
                    activity.getGestureController().stopNow();
                }
            } catch (Exception e) {
                Log.w(TAG, "Error stopping GestureController", e);
            }

            activity.runOnUiThread(() -> {
                viewModel.setStatusText(activity.getString(R.string.robot_focus_lost_message));
                boolean hasMap = new File(activity.getFilesDir(), "maps/default_map.map").exists();
                viewModel.setMapStatus(activity.getString(hasMap ? R.string.nav_map_saved : R.string.nav_map_none));
                viewModel.setLocalizationStatus(activity.getString(R.string.nav_localization_stopped));
            });

            Log.i(TAG, "handleRobotFocusLost: Released lifecycle lock");
        }
    }

    @Override
    public void onRobotInitializationFailed(String error) {
        activity.runOnUiThread(() -> viewModel.setStatusText(error));
    }

    public boolean isFocusAvailable() {
        // RobotFocusManager is not exposed via getter in ChatActivity yet?
        // I need to check if I added getRobotFocusManager() to ChatActivity.
        // I didn't add it in the previous step. I added getRobotController().
        // But RobotFocusManager has isFocusAvailable().
        // I should add getRobotFocusManager() to ChatActivity.
        // Or expose isFocusAvailable() in ChatActivity.
        // For now I'll assume I can add it or use a workaround.
        // Actually, I'll add getRobotFocusManager() to ChatActivity in the next step.
        return true; // Placeholder until I fix ChatActivity
    }
}
