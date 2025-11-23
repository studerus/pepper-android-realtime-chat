package io.github.anonymous.pepper_realtime.controller;

import android.util.Log;

import io.github.anonymous.pepper_realtime.R;
import io.github.anonymous.pepper_realtime.manager.AudioPlayer;
import io.github.anonymous.pepper_realtime.manager.SessionImageManager;
import io.github.anonymous.pepper_realtime.manager.TurnManager;
import io.github.anonymous.pepper_realtime.network.WebSocketConnectionCallback;
import io.github.anonymous.pepper_realtime.service.PerceptionService;
import io.github.anonymous.pepper_realtime.service.VisionService;
import io.github.anonymous.pepper_realtime.manager.TouchSensorManager;
import io.github.anonymous.pepper_realtime.ui.ChatViewModel;

/**
 * Orchestrates lifecycle events for chat services.
 * Handles pausing/resuming of all services when app goes to
 * background/foreground.
 */
public class ChatLifecycleController {
    private static final String TAG = "ChatLifecycleController";

    private final ChatViewModel viewModel;
    private final AudioInputController audioInputController;
    private final ChatSessionController sessionController;
    private final PerceptionService perceptionService;
    private final VisionService visionService;
    private final TouchSensorManager touchSensorManager;
    private final GestureController gestureController;
    private final AudioPlayer audioPlayer;
    private final TurnManager turnManager;
    private final SessionImageManager sessionImageManager;

    private boolean wasStoppedByBackground = false;

    public ChatLifecycleController(
            ChatViewModel viewModel,
            AudioInputController audioInputController,
            ChatSessionController sessionController,
            PerceptionService perceptionService,
            VisionService visionService,
            TouchSensorManager touchSensorManager,
            GestureController gestureController,
            AudioPlayer audioPlayer,
            TurnManager turnManager,
            SessionImageManager sessionImageManager) {
        this.viewModel = viewModel;
        this.audioInputController = audioInputController;
        this.sessionController = sessionController;
        this.perceptionService = perceptionService;
        this.visionService = visionService;
        this.touchSensorManager = touchSensorManager;
        this.gestureController = gestureController;
        this.audioPlayer = audioPlayer;
        this.turnManager = turnManager;
        this.sessionImageManager = sessionImageManager;
    }

    /**
     * Handle activity stop (app going to background)
     */
    public void onStop() {
        Log.i(TAG, "Activity stopped (background) - pausing services");
        wasStoppedByBackground = true;

        pauseActiveServices();

        if (turnManager != null) {
            turnManager.setState(TurnManager.State.IDLE);
        }
        viewModel.setStatusText(getString(R.string.app_paused));
    }

    /**
     * Handle activity resume (app coming to foreground)
     */
    public void onResume(RobotFocusManager robotFocusManager) {
        if (wasStoppedByBackground && robotFocusManager.isFocusAvailable()) {
            Log.i(TAG, "Activity resumed from background - restarting services");
            resumeServicesAfterBackground();
        }
    }

    /**
     * Check if app was stopped by going to background
     */
    public boolean isBackgrounded() {
        return wasStoppedByBackground;
    }

    private void pauseActiveServices() {
        audioInputController.cleanupForRestart();

        if (sessionController != null) {
            sessionController.disconnectWebSocketGracefully();
        }

        if (perceptionService != null && perceptionService.isInitialized()) {
            perceptionService.stopMonitoring();
        }

        if (visionService != null && visionService.isInitialized()) {
            visionService.pause();
        }

        if (touchSensorManager != null) {
            touchSensorManager.pause();
        }

        if (gestureController != null) {
            gestureController.pause();
        }

        if (audioPlayer != null) {
            try {
                audioPlayer.interruptNow();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping audio playback during background", e);
            }
        }
    }

    private void resumeServicesAfterBackground() {
        wasStoppedByBackground = false;

        // Clear chat history to match fresh Realtime API session state
        viewModel.clearMessages();
        viewModel.setStatusText(getString(R.string.status_reconnecting));

        // Delete session images from previous session
        sessionImageManager.deleteAllImages();

        if (perceptionService != null && perceptionService.isInitialized()) {
            perceptionService.startMonitoring();
        }

        if (visionService != null && visionService.isInitialized()) {
            visionService.resume();
        }

        if (touchSensorManager != null) {
            touchSensorManager.resume();
        }

        if (gestureController != null) {
            gestureController.resume();
        }

        if (sessionController != null) {
            sessionController.connectWebSocket(new WebSocketConnectionCallback() {
                @Override
                public void onSuccess() {
                    Log.i(TAG, "WebSocket reconnected after resume - fresh session started");
                    // Set LISTENING state AFTER WebSocket is connected
                    if (turnManager != null) {
                        turnManager.setState(TurnManager.State.LISTENING);
                    }
                    viewModel.setStatusText(getString(R.string.status_listening));
                    // audioInputController.handleResume() removed to prevent double-start race
                    // condition
                    // TurnManager.setState(LISTENING) already triggers audio start via
                    // ChatTurnListener
                }

                @Override
                public void onError(Throwable error) {
                    Log.e(TAG, "Failed to reconnect WebSocket after resume", error);
                    viewModel.setStatusText(getString(R.string.error_connection_failed_short));
                }
            });
        } else {
            // No session controller - just set state and resume audio
            if (turnManager != null) {
                turnManager.setState(TurnManager.State.LISTENING);
            }
            audioInputController.handleResume();
        }
    }

    private String getString(int resId) {
        return viewModel.getApplication().getString(resId);
    }
}
