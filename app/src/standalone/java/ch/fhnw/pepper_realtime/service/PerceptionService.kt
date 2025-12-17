package ch.fhnw.pepper_realtime.service

import android.util.Log
import ch.fhnw.pepper_realtime.data.PerceptionData
import ch.fhnw.pepper_realtime.data.PersonEvent

/**
 * Stub implementation of PerceptionService for standalone mode (no robot hardware).
 * Simulates perception capabilities without actual sensor data.
 */
class PerceptionService {

    interface PerceptionListener {
        fun onHumansDetected(humans: List<PerceptionData.HumanInfo>)
        fun onPerceptionError(error: String)
        fun onServiceStatusChanged(isActive: Boolean)
    }

    interface EventListener {
        fun onPersonEvent(
            event: PersonEvent,
            humanInfo: PerceptionData.HumanInfo,
            allHumans: List<PerceptionData.HumanInfo>
        )
    }

    companion object {
        private const val TAG = "PerceptionService[STUB]"
    }

    private var listener: PerceptionListener? = null
    private var eventListener: EventListener? = null
    private var _isMonitoring = false

    init {
        Log.d(TAG, "🤖 [SIMULATED] PerceptionService created")
    }

    /**
     * Set the perception listener for callbacks
     */
    fun setListener(listener: PerceptionListener?) {
        this.listener = listener
    }

    /**
     * Set the event listener for person events (no-op in standalone mode).
     */
    fun setEventListener(listener: EventListener?) {
        this.eventListener = listener
        Log.d(TAG, "🤖 [SIMULATED] EventListener set (no events in standalone mode)")
    }

    /**
     * Set the WebSocket client (no-op in standalone mode).
     */
    fun setWebSocketClient(@Suppress("UNUSED_PARAMETER") client: PerceptionWebSocketClient) {
        Log.d(TAG, "🤖 [SIMULATED] PerceptionWebSocketClient ignored in standalone mode")
    }

    /**
     * Set the local face recognition service (no-op in standalone mode).
     */
    fun setLocalFaceRecognitionService(@Suppress("UNUSED_PARAMETER") service: LocalFaceRecognitionService) {
        Log.d(TAG, "🤖 [SIMULATED] LocalFaceRecognitionService ignored in standalone mode")
    }

    /**
     * Simulates initializing the perception service
     */
    fun initialize(@Suppress("UNUSED_PARAMETER") qiContext: Any?) {
        Log.i(TAG, "🤖 [SIMULATED] PerceptionService initialized")
    }

    /**
     * Simulates starting perception monitoring
     */
    fun startMonitoring() {
        if (_isMonitoring) {
            Log.d(TAG, "🤖 [SIMULATED] Already monitoring")
            return
        }
        _isMonitoring = true
        Log.i(TAG, "🤖 [SIMULATED] Started perception monitoring")
        listener?.onServiceStatusChanged(true)
    }

    /**
     * Simulates stopping perception monitoring
     */
    fun stopMonitoring() {
        if (!_isMonitoring) {
            Log.d(TAG, "🤖 [SIMULATED] Already stopped")
            return
        }
        _isMonitoring = false
        Log.i(TAG, "🤖 [SIMULATED] Stopped perception monitoring")
        listener?.onServiceStatusChanged(false)
    }
    
    /**
     * Force restart monitoring - useful after lifecycle changes.
     */
    fun restartMonitoring() {
        Log.i(TAG, "🤖 [SIMULATED] Restarting perception monitoring")
        stopMonitoring()
        startMonitoring()
    }

    /**
     * Returns empty list (no humans detected in standalone mode)
     */
    fun getCurrentHumans(): List<PerceptionData.HumanInfo> = emptyList()

    /**
     * Checks if monitoring is active
     */
    fun isMonitoring(): Boolean = _isMonitoring

    /**
     * Simulates getting the number of detected humans
     */
    fun getHumanCount(): Int = 0 // No humans in standalone mode

    /**
     * Checks if service is initialized
     */
    val isInitialized: Boolean = true // Always initialized in standalone mode

    /**
     * Simulates getting perception data
     */
    fun getPerceptionData(): PerceptionData = PerceptionData()

    /**
     * Shuts down the service
     */
    fun shutdown() {
        stopMonitoring()
        Log.i(TAG, "🤖 [SIMULATED] PerceptionService shutdown")
    }
}

