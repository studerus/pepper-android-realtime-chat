package io.github.anonymous.pepper_realtime.tools;

import android.content.Context;
import io.github.anonymous.pepper_realtime.manager.ApiKeyManager;
import io.github.anonymous.pepper_realtime.ui.ChatActivity;
import io.github.anonymous.pepper_realtime.controller.MovementController;
import io.github.anonymous.pepper_realtime.manager.NavigationServiceManager;
import io.github.anonymous.pepper_realtime.service.PerceptionService;
import io.github.anonymous.pepper_realtime.manager.DashboardManager;
import io.github.anonymous.pepper_realtime.manager.TouchSensorManager;
import io.github.anonymous.pepper_realtime.controller.GestureController;
import io.github.anonymous.pepper_realtime.data.LocationProvider;
import io.github.anonymous.pepper_realtime.controller.ChatSessionController;
import io.github.anonymous.pepper_realtime.controller.RobotFocusManager;

/**
 * Shared context providing dependencies for all tools.
 * Centralizes access to common services and components.
 */
public class ToolContext {

    private final ChatActivity activity;
    private final Context appContext;
    private final RobotFocusManager robotFocusManager;
    private Object qiContext;
    private final ApiKeyManager apiKeyManager;
    private final MovementController movementController;
    private final NavigationServiceManager navigationServiceManager;
    private final PerceptionService perceptionService;
    private final DashboardManager dashboardManager;
    private final TouchSensorManager touchSensorManager;
    private final GestureController gestureController;
    private final LocationProvider locationProvider;
    private final ChatSessionController sessionController;

    // Constructor
    public ToolContext(ChatActivity activity, RobotFocusManager robotFocusManager, ApiKeyManager apiKeyManager,
            MovementController movementController, NavigationServiceManager navigationServiceManager,
            PerceptionService perceptionService, DashboardManager dashboardManager,
            TouchSensorManager touchSensorManager, GestureController gestureController,
            LocationProvider locationProvider, ChatSessionController sessionController) {
        this.activity = activity;
        this.appContext = activity;
        this.robotFocusManager = robotFocusManager;
        this.qiContext = robotFocusManager != null ? robotFocusManager.getQiContext() : null;
        this.apiKeyManager = apiKeyManager;
        this.movementController = movementController;
        this.navigationServiceManager = navigationServiceManager;
        this.perceptionService = perceptionService;
        this.dashboardManager = dashboardManager;
        this.touchSensorManager = touchSensorManager;
        this.gestureController = gestureController;
        this.locationProvider = locationProvider;
        this.sessionController = sessionController;
    }

    // Getters
    public Context getAppContext() {
        return appContext;
    }

    public ChatActivity getActivity() {
        return activity;
    }

    public Object getQiContext() {
        return qiContext;
    }

    public ApiKeyManager getApiKeyManager() {
        return apiKeyManager;
    }

    public MovementController getMovementController() {
        return movementController;
    }

    public NavigationServiceManager getNavigationServiceManager() {
        return navigationServiceManager;
    }

    public PerceptionService getPerceptionService() {
        return perceptionService;
    }

    public DashboardManager getDashboardManager() {
        return dashboardManager;
    }

    public TouchSensorManager getTouchSensorManager() {
        return touchSensorManager;
    }

    public GestureController getGestureController() {
        return gestureController;
    }

    public LocationProvider getLocationProvider() {
        return locationProvider;
    }

    public ChatSessionController getSessionController() {
        return sessionController;
    }

    // Convenience methods
    public boolean isQiContextNotReady() {
        return qiContext == null;
    }

    public boolean hasUi() {
        return activity != null;
    }

    /**
     * Update QiContext when robot focus changes
     * 
     * @param newQiContext New QiContext (Pepper) or null (Standalone/focus lost)
     */
    public void updateQiContext(Object newQiContext) {
        this.qiContext = newQiContext;
    }

    /**
     * Send message directly to Realtime API
     * 
     * @param message         Message to send
     * @param requestResponse Whether to request a response from AI
     */
    public void sendAsyncUpdate(String message, boolean requestResponse) {
        if (sessionController != null) {
            sessionController.sendMessageToRealtimeAPI(message, requestResponse, true);
        }
    }

    /**
     * Notify about service state changes for proper service management
     * 
     * @param mode Service mode (e.g., "enterLocalizationMode",
     *             "resumeNormalOperation")
     */
    public void notifyServiceStateChange(String mode) {
        if (activity != null) {
            activity.handleServiceStateChange(mode);
        }
    }
}
