package io.github.anonymous.pepper_realtime.tools;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.github.anonymous.pepper_realtime.controller.ChatSessionController;
import io.github.anonymous.pepper_realtime.controller.GestureController;
import io.github.anonymous.pepper_realtime.controller.MovementController;
import io.github.anonymous.pepper_realtime.controller.RobotFocusManager;
import io.github.anonymous.pepper_realtime.data.LocationProvider;
import io.github.anonymous.pepper_realtime.manager.ApiKeyManager;
import io.github.anonymous.pepper_realtime.manager.DashboardManager;
import io.github.anonymous.pepper_realtime.manager.TouchSensorManager;
import io.github.anonymous.pepper_realtime.manager.NavigationServiceManager;
import io.github.anonymous.pepper_realtime.service.PerceptionService;
import io.github.anonymous.pepper_realtime.tools.interfaces.ToolHost;

public class ToolContextFactory {

    private final RobotFocusManager robotFocusManager;
    private final ApiKeyManager apiKeyManager;
    private final MovementController movementController;
    private final NavigationServiceManager navigationServiceManager;
    private final PerceptionService perceptionService;
    private final TouchSensorManager touchSensorManager;
    private final GestureController gestureController;
    private final LocationProvider locationProvider;
    private final ChatSessionController sessionController;

    @Inject
    public ToolContextFactory(RobotFocusManager robotFocusManager,
            ApiKeyManager apiKeyManager,
            MovementController movementController,
            NavigationServiceManager navigationServiceManager,
            PerceptionService perceptionService,
            TouchSensorManager touchSensorManager,
            GestureController gestureController,
            LocationProvider locationProvider,
            ChatSessionController sessionController) {
        this.robotFocusManager = robotFocusManager;
        this.apiKeyManager = apiKeyManager;
        this.movementController = movementController;
        this.navigationServiceManager = navigationServiceManager;
        this.perceptionService = perceptionService;
        this.touchSensorManager = touchSensorManager;
        this.gestureController = gestureController;
        this.locationProvider = locationProvider;
        this.sessionController = sessionController;
    }

    public ToolContext create(ToolHost toolHost, DashboardManager dashboardManager) {
        return new ToolContext(
                toolHost,
                robotFocusManager,
                apiKeyManager,
                movementController,
                navigationServiceManager,
                perceptionService,
                dashboardManager,
                touchSensorManager,
                gestureController,
                locationProvider,
                sessionController);
    }
}
