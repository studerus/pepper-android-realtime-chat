package io.github.anonymous.pepper_realtime.tools;

import android.content.Context;
import io.github.anonymous.pepper_realtime.ApiKeyManager;
import io.github.anonymous.pepper_realtime.ChatActivity;
import io.github.anonymous.pepper_realtime.MovementController;
import io.github.anonymous.pepper_realtime.NavigationServiceManager;
import io.github.anonymous.pepper_realtime.data.LocationProvider;

/**
 * Shared context providing dependencies for all tools.
 * Centralizes access to common services and components.
 */
public class ToolContext {
    
    private final Context appContext;
    private Object qiContext; // QiContext for Pepper, null for Standalone
    private final ChatActivity activity;
    private final ApiKeyManager apiKeyManager;
    private final MovementController movementController;
    private final LocationProvider locationProvider;
    
    // Constructor
    public ToolContext(Context appContext, Object qiContext, ChatActivity activity,
                      ApiKeyManager apiKeyManager, MovementController movementController,
                      LocationProvider locationProvider) {
        this.appContext = appContext;
        this.qiContext = qiContext;
        this.activity = activity;
        this.apiKeyManager = apiKeyManager;
        this.movementController = movementController;
        this.locationProvider = locationProvider;
    }
    
    // Getters
    public Context getAppContext() { return appContext; }
    public Object getQiContext() { return qiContext; }
    public ChatActivity getActivity() { return activity; }
    public ApiKeyManager getApiKeyManager() { return apiKeyManager; }
    @SuppressWarnings("unused")
    public MovementController getMovementController() { return movementController; }
    public LocationProvider getLocationProvider() { return locationProvider; }
    
    /**
     * Get NavigationServiceManager from activity - preferred for navigation operations
     * This coordinates services during navigation unlike direct MovementController access
     */
    public NavigationServiceManager getNavigationServiceManager() {
        return activity != null ? activity.getNavigationServiceManager() : null;
    }
    
    /**
     * Get PerceptionService from activity - provides access to human detection and awareness
     */
    public io.github.anonymous.pepper_realtime.PerceptionService getPerceptionService() {
        return activity != null ? activity.getPerceptionService() : null;
    }
    
    // Convenience methods
    public boolean isQiContextNotReady() { return qiContext == null; }
    public boolean hasUi() { return activity != null; }
    
    /**
     * Update QiContext when robot focus changes
     * @param newQiContext New QiContext (Pepper) or null (Standalone/focus lost)
     */
    public void updateQiContext(Object newQiContext) {
        this.qiContext = newQiContext;
    }
    
    /**
     * Send message directly to Realtime API
     * @param message Message to send
     * @param requestResponse Whether to request a response from AI
     */
    public void sendAsyncUpdate(String message, boolean requestResponse) {
        if (activity != null) {
            activity.sendMessageToRealtimeAPI(message, requestResponse, true);
        }
    }
    
    /**
     * Notify about service state changes for proper service management
     * @param mode Service mode (e.g., "enterLocalizationMode", "resumeNormalOperation")
     */
    public void notifyServiceStateChange(String mode) {
        if (activity != null) {
            activity.handleServiceStateChange(mode);
        }
    }
}
