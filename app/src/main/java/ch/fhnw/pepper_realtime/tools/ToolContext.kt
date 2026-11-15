package ch.fhnw.pepper_realtime.tools

import android.app.Activity
import android.content.Context
import ch.fhnw.pepper_realtime.controller.GestureController
import ch.fhnw.pepper_realtime.controller.MovementController
import ch.fhnw.pepper_realtime.controller.RobotFocusManager
import ch.fhnw.pepper_realtime.data.LocationProvider
import ch.fhnw.pepper_realtime.data.NavigationStore
import ch.fhnw.pepper_realtime.manager.ApiKeyManager
import ch.fhnw.pepper_realtime.manager.NavigationServiceManager
import ch.fhnw.pepper_realtime.manager.TouchSensorManager
import ch.fhnw.pepper_realtime.service.PerceptionService
import ch.fhnw.pepper_realtime.tools.interfaces.RealtimeMessageSender
import ch.fhnw.pepper_realtime.tools.interfaces.ToolHost

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
    private val _touchSensorManager: TouchSensorManager,
    private val _gestureController: GestureController,
    private val _locationProvider: LocationProvider,
    private val _messageSender: RealtimeMessageSender?,
    val operation: OperationScope? = null
) {
    /**
     * QiContext for Pepper robot operations
     */
    private var _qiContext: Any? = _robotFocusManager?.qiContext

    // Properties with Java-compatible getters via @get:JvmName
    val apiKeyManager: ApiKeyManager
        get() = _apiKeyManager
    
    val movementController: MovementController
        get() = _movementController
    
    val navigationServiceManager: NavigationServiceManager
        get() = _navigationServiceManager
    
    val perceptionService: PerceptionService
        get() = _perceptionService
    
    val touchSensorManager: TouchSensorManager
        get() = _touchSensorManager
    
    val gestureController: GestureController
        get() = _gestureController
    
    val locationProvider: LocationProvider
        get() = _locationProvider
    
    val toolHost: ToolHost
        get() = _toolHost
    
    val appContext: Context
        get() = _toolHost.getAppContext()

    val navigationStore: NavigationStore
        get() = NavigationStore.forDirectory(appContext.filesDir)
    
    val activity: Activity?
        get() = _toolHost.getActivity()
    
    val qiContext: Any?
        get() { ensureActive(); return _qiContext }
    
    val messageSender: RealtimeMessageSender?
        get() = _messageSender

    /**
     * Check if QiContext is not ready
     */
    fun isQiContextNotReady(): Boolean { ensureActive(); return _qiContext == null }

    val isActive: Boolean get() = operation?.isActive != false

    fun ensureActive() { operation?.ensureActive() }

    fun onCancel(action: () -> Unit): java.io.Closeable =
        operation?.onCancel(action) ?: java.io.Closeable { }

    fun <T> guard(action: () -> T): T = if (operation != null) operation.guard(action) else action()

    fun runIfActive(action: () -> Unit) {
        if (operation != null) operation.runIfActive(action) else action()
    }

    fun forOperation(owner: OperationScope): ToolContext {
        val ownsMute = java.util.concurrent.atomic.AtomicBoolean(false)
        owner.onCancel {
            if (ownsMute.getAndSet(false)) _toolHost.resetMicrophoneMute()
        }
        val host = object : ToolHost by _toolHost {
            override fun runOnUiThread(action: Runnable) {
                _toolHost.runOnUiThread { owner.runIfActive { action.run() } }
            }
            override fun getActivity(): Activity? = if (owner.isActive) _toolHost.getActivity() else null
            override fun isFinishing(): Boolean = !owner.isActive || _toolHost.isFinishing()
            override fun handleServiceStateChange(mode: String) { owner.runIfActive { _toolHost.handleServiceStateChange(mode) } }
            override fun addImageMessage(imagePath: String) { owner.runIfActive { _toolHost.addImageMessage(imagePath) } }
            override fun updateMapPreview() { owner.runIfActive { _toolHost.updateMapPreview() } }
            override fun updateNavigationStatus(mapStatus: String, localizationStatus: String) {
                owner.runIfActive { _toolHost.updateNavigationStatus(mapStatus, localizationStatus) }
            }
            override fun muteMicrophone() { owner.runIfActive { ownsMute.set(true); _toolHost.muteMicrophone() } }
            override fun unmuteMicrophone() { owner.runIfActive { ownsMute.set(false); _toolHost.unmuteMicrophone() } }
            override fun refreshChatMessages() { owner.runIfActive { _toolHost.refreshChatMessages() } }
        }
        return ToolContext(host, null, _apiKeyManager, _movementController, _navigationServiceManager,
            _perceptionService, _touchSensorManager, _gestureController, _locationProvider, _messageSender, owner)
            .also { it.updateQiContext(_qiContext) }
    }

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
        if (operation == null) _messageSender?.sendMessageToRealtimeAPI(message, requestResponse, true)
        else _messageSender?.sendScopedMessage(message, requestResponse, true, operation)
    }

    /**
     * Notify about service state changes for proper service management
     * @param mode Service mode (e.g., "enterLocalizationMode", "resumeNormalOperation")
     */
    fun notifyServiceStateChange(mode: String) {
        _toolHost.handleServiceStateChange(mode)
    }
}

