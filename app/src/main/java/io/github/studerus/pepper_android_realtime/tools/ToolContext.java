package io.github.studerus.pepper_android_realtime.tools;

import android.content.Context;
import com.aldebaran.qi.sdk.QiContext;
import io.github.studerus.pepper_android_realtime.ApiKeyManager;
import io.github.studerus.pepper_android_realtime.ChatActivity;
import io.github.studerus.pepper_android_realtime.MovementController;
import io.github.studerus.pepper_android_realtime.NavigationServiceManager;
import io.github.studerus.pepper_android_realtime.data.LocationProvider;

/**
 * Shared context providing dependencies for all tools.
 * Centralizes access to common services and components.
 */
public class ToolContext {
    
    private final Context appContext;
    private QiContext qiContext; // Made non-final to allow updates
    private final ChatActivity activity;
    private final ApiKeyManager apiKeyManager;
    private final MovementController movementController;
    private final LocationProvider locationProvider;
    
    // Constructor
    public ToolContext(Context appContext, QiContext qiContext, ChatActivity activity,
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
    public QiContext getQiContext() { return qiContext; }
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
    
    // Convenience methods
    public boolean isQiContextNotReady() { return qiContext == null; }
    public boolean hasUi() { return activity != null; }
    
    /**
     * Update QiContext when robot focus changes
     * @param newQiContext New QiContext or null if focus is lost
     */
    public void updateQiContext(QiContext newQiContext) {
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
