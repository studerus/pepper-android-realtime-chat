package io.github.anonymous.pepper_realtime.tools

import android.app.Activity
import android.content.Context
import io.github.anonymous.pepper_realtime.controller.GestureController
import io.github.anonymous.pepper_realtime.controller.MovementController
import io.github.anonymous.pepper_realtime.controller.RobotFocusManager
import io.github.anonymous.pepper_realtime.data.LocationProvider
import io.github.anonymous.pepper_realtime.manager.ApiKeyManager
import io.github.anonymous.pepper_realtime.manager.DashboardManager
import io.github.anonymous.pepper_realtime.manager.NavigationServiceManager
import io.github.anonymous.pepper_realtime.manager.TouchSensorManager
import io.github.anonymous.pepper_realtime.service.PerceptionService
import io.github.anonymous.pepper_realtime.tools.interfaces.RealtimeMessageSender
import io.github.anonymous.pepper_realtime.tools.interfaces.ToolHost

/**
 * Shared context providing dependencies for all tools.
 * Centralizes access to common services and components.
 * 
 * For Kotlin: Use the properties directly (e.g., context.apiKeyManager)
 * For Java: Use the getXxx() methods (e.g., context.getApiKeyManager())
 */
class ToolContext(
    private val _toolHost: ToolHost,
    private val _robotFocusManager: RobotFocusManager?,
    private val _apiKeyManager: ApiKeyManager,
    private val _movementController: MovementController,
    private val _navigationServiceManager: NavigationServiceManager,
    private val _perceptionService: PerceptionService,
    private val _dashboardManager: DashboardManager?,
    private val _touchSensorManager: TouchSensorManager,
    private val _gestureController: GestureController,
    private val _locationProvider: LocationProvider,
    private val _messageSender: RealtimeMessageSender?
) {
    /**
     * QiContext for Pepper robot operations
     */
    private var _qiContext: Any? = _robotFocusManager?.qiContext

    // Properties with Java-compatible getters via @get:JvmName
    val apiKeyManager: ApiKeyManager
        @JvmName("getApiKeyManager") get() = _apiKeyManager
    
    val movementController: MovementController
        @JvmName("getMovementController") get() = _movementController
    
    val navigationServiceManager: NavigationServiceManager
        @JvmName("getNavigationServiceManager") get() = _navigationServiceManager
    
    val perceptionService: PerceptionService
        @JvmName("getPerceptionService") get() = _perceptionService
    
    val dashboardManager: DashboardManager?
        @JvmName("getDashboardManager") get() = _dashboardManager
    
    val touchSensorManager: TouchSensorManager
        @JvmName("getTouchSensorManager") get() = _touchSensorManager
    
    val gestureController: GestureController
        @JvmName("getGestureController") get() = _gestureController
    
    val locationProvider: LocationProvider
        @JvmName("getLocationProvider") get() = _locationProvider
    
    val toolHost: ToolHost
        @JvmName("getToolHost") get() = _toolHost
    
    val appContext: Context
        @JvmName("getAppContext") get() = _toolHost.getAppContext()
    
    val activity: Activity?
        @JvmName("getActivity") get() = _toolHost.getActivity()
    
    val qiContext: Any?
        @JvmName("getQiContext") get() = _qiContext
    
    val messageSender: RealtimeMessageSender?
        @JvmName("getMessageSender") get() = _messageSender

    /**
     * Check if QiContext is not ready
     */
    fun isQiContextNotReady(): Boolean = _qiContext == null

    /**
     * Check if UI is available
     */
    fun hasUi(): Boolean = _toolHost.getActivity() != null

    /**
     * Update QiContext when robot focus changes
     * @param newQiContext New QiContext (Pepper) or null (Standalone/focus lost)
     */
    fun updateQiContext(newQiContext: Any?) {
        this._qiContext = newQiContext
    }

    /**
     * Send message directly to Realtime API
     * @param message Message to send
     * @param requestResponse Whether to request a response from AI
     */
    fun sendAsyncUpdate(message: String, requestResponse: Boolean) {
        _messageSender?.sendMessageToRealtimeAPI(message, requestResponse, true)
    }

    /**
     * Notify about service state changes for proper service management
     * @param mode Service mode (e.g., "enterLocalizationMode", "resumeNormalOperation")
     */
    fun notifyServiceStateChange(mode: String) {
        _toolHost.handleServiceStateChange(mode)
    }
}
