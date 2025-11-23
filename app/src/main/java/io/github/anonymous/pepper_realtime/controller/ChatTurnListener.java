package io.github.anonymous.pepper_realtime.controller;

import android.util.Log;

import io.github.anonymous.pepper_realtime.ui.ChatViewModel;
import io.github.anonymous.pepper_realtime.controller.GestureController;
import io.github.anonymous.pepper_realtime.R;
import io.github.anonymous.pepper_realtime.manager.TurnManager;
import io.github.anonymous.pepper_realtime.robot.RobotController;
import io.github.anonymous.pepper_realtime.controller.RobotFocusManager;
import io.github.anonymous.pepper_realtime.manager.NavigationServiceManager;

public class ChatTurnListener implements TurnManager.Callbacks {
    private static final String TAG = "ChatTurnListener";

    private final ChatViewModel viewModel;
    private final GestureController gestureController;
    private final AudioInputController audioInputController;
    private final RobotFocusManager robotFocusManager;
    private final NavigationServiceManager navigationServiceManager;
    private final TurnManager turnManager;

    @javax.inject.Inject
    public ChatTurnListener(ChatViewModel viewModel,
            GestureController gestureController,
            AudioInputController audioInputController,
            RobotFocusManager robotFocusManager,
            NavigationServiceManager navigationServiceManager,
            TurnManager turnManager) {
        this.viewModel = viewModel;
        this.gestureController = gestureController;
        this.audioInputController = audioInputController;
        this.robotFocusManager = robotFocusManager;
        this.navigationServiceManager = navigationServiceManager;
        this.turnManager = turnManager;
    }

    @Override
    public void onEnterListening() {
        try {
            // Check robot readiness
            Object qiContext = robotFocusManager.getQiContext();

            if (robotFocusManager.getRobotController() != null
                    && robotFocusManager.getRobotController().isRobotHardwareAvailable() && qiContext == null) {
                Log.w(TAG, "Pepper robot focus lost, aborting onEnterListening to prevent crashes");
                return;
            }

            if (!audioInputController.isMuted()) {
                viewModel.setStatusText(getString(R.string.status_listening));
                if (!audioInputController.isSttRunning()) {
                    try {
                        audioInputController.startContinuousRecognition();
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to start recognition in onEnterListening", e);
                        viewModel.setStatusText(getString(R.string.status_recognizer_not_ready));
                    }
                }
            }
            viewModel.setInterruptFabVisible(false);
        } catch (Exception e) {
            Log.e(TAG, "Exception in onEnterListening", e);
        }
    }

    @Override
    public void onEnterThinking() {
        // Physically stop the mic so nothing is recognized during THINKING (only if
        // running)
        if (audioInputController.isSttRunning()) {
            audioInputController.stopContinuousRecognition();
        } else {
            Log.d(TAG, "STT already stopped, skipping redundant stop in THINKING state");
        }
        viewModel.setStatusText(getString(R.string.status_thinking));
        viewModel.setInterruptFabVisible(false);
    }

    @Override
    public void onEnterSpeaking() {
        Log.i(TAG, "State: Entering SPEAKING - starting gestures and stopping STT");
        audioInputController.stopContinuousRecognition();

        if (robotFocusManager.getQiContext() != null) {
            if (navigationServiceManager == null || !navigationServiceManager.areGesturesSuppressed()) {
                gestureController.start(robotFocusManager.getQiContext(),
                        () -> turnManager != null
                                && turnManager.getState() == TurnManager.State.SPEAKING
                                && robotFocusManager.getQiContext() != null &&
                                (navigationServiceManager == null
                                        || !navigationServiceManager.areGesturesSuppressed()),
                        gestureController::getRandomExplainAnimationResId);
            }
        }

        viewModel.setInterruptFabVisible(false);
    }

    @Override
    public void onExitSpeaking() {
        Log.i(TAG, "State: Exiting SPEAKING - stopping gestures and starting STT");
        gestureController.stopNow();
        if (!audioInputController.isMuted()) {
            viewModel.setStatusText(getString(R.string.status_listening));
        }
        viewModel.setInterruptFabVisible(false);
    }

    private String getString(int resId) {
        return viewModel.getApplication().getString(resId);
    }
}
