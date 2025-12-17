package ch.fhnw.pepper_realtime.service

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.human.Human
import com.aldebaran.qi.sdk.`object`.humanawareness.HumanAwareness
import ch.fhnw.pepper_realtime.data.PerceptionData
import ch.fhnw.pepper_realtime.data.PersonEvent
import ch.fhnw.pepper_realtime.data.PersonEventType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Manages perception data using the Head-Based Perception System via WebSocket.
 * 
 * Uses WebSocket streaming for real-time updates from the Python server on Pepper's head.
 * This provides lower latency than HTTP polling.
 * 
 * QiSDK is only used for HumanAwareness (getRecommendedHumanToApproach)
 * but NOT for human tracking or recognition - that's handled by the head server.
 */
class PerceptionService {

    companion object {
        private const val TAG = "PerceptionService"
    }

    interface PerceptionListener {
        fun onHumansDetected(humans: List<PerceptionData.HumanInfo>)
        fun onPerceptionError(error: String)
        fun onServiceStatusChanged(isActive: Boolean)
    }

    /**
     * Listener for person events that can trigger rules.
     */
    interface EventListener {
        fun onPersonEvent(event: PersonEvent, humanInfo: PerceptionData.HumanInfo, allHumans: List<PerceptionData.HumanInfo>)
    }

    private var qiContext: QiContext? = null
    private var listener: PerceptionListener? = null
    private var isMonitoring = false

    // QiSDK services - only for approach recommendations
    private var humanAwareness: HumanAwareness? = null

    // WebSocket client for real-time perception streaming
    private var webSocketClient: PerceptionWebSocketClient? = null
    
    // Legacy HTTP service (kept for face management fallback)
    private var localFaceRecognitionService: LocalFaceRecognitionService? = null

    // Event detection state tracking
    private var eventListener: EventListener? = null
    private var previousTrackIds: Set<Int> = emptySet()
    private var previousLookingStates: Map<Int, Boolean> = emptyMap()
    private var previousDistances: Map<Int, Float> = emptyMap()
    private var previousRecognizedNames: Map<Int, String> = emptyMap()
    
    // Distance thresholds for approach events
    private val closeDistanceThreshold = 1.5f // meters
    private val interactionDistanceThreshold = 3.0f // meters
    
    // Track which approach events have been fired to avoid duplicates
    private var firedCloseApproach: MutableSet<Int> = mutableSetOf()
    private var firedInteractionApproach: MutableSet<Int> = mutableSetOf()

    // Threading
    private var monitoringThread: HandlerThread? = null
    private var monitoringHandler: Handler? = null
    
    // Use a recreatable scope - gets recreated if cancelled
    private var serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var peopleCollectionJob: Job? = null
    
    private fun ensureScopeActive(): CoroutineScope {
        if (!serviceScope.isActive) {
            Log.i(TAG, "Recreating service scope")
            serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        return serviceScope
    }

    private var lastUiPushMs = 0L
    private var lastUiIds: List<Int> = emptyList()

    val isInitialized: Boolean
        get() = webSocketClient != null || localFaceRecognitionService != null

    init {
        Log.d(TAG, "PerceptionService created (WebSocket mode)")
        ensureMonitoringThreadReady()
    }

    /**
     * Ensures the monitoring thread and handler are ready.
     */
    @Synchronized
    private fun ensureMonitoringThreadReady() {
        if (monitoringThread == null || monitoringThread?.isAlive != true) {
            Log.i(TAG, "Initializing monitoring thread")
            monitoringThread = HandlerThread("PerceptionMonitoringThread").also {
                it.start()
                monitoringHandler = Handler(it.looper)
            }
        } else if (monitoringHandler == null) {
            monitoringHandler = Handler(monitoringThread!!.looper)
        }
    }

    /**
     * Set the perception listener for callbacks
     */
    fun setListener(listener: PerceptionListener?) {
        this.listener = listener
    }

    /**
     * Set the WebSocket client for real-time perception streaming.
     * This is the preferred method for perception data.
     */
    fun setWebSocketClient(client: PerceptionWebSocketClient) {
        this.webSocketClient = client
        Log.i(TAG, "WebSocket client configured")
    }

    /**
     * Set the local face recognition service (HTTP fallback).
     * Used for face management operations.
     */
    fun setLocalFaceRecognitionService(service: LocalFaceRecognitionService) {
        this.localFaceRecognitionService = service
        Log.i(TAG, "HTTP service configured (for fallback/management)")
    }

    /**
     * Set the event listener for rule-based event handling.
     */
    fun setEventListener(listener: EventListener?) {
        this.eventListener = listener
        Log.i(TAG, "Event listener ${if (listener != null) "set" else "cleared"}")
    }

    /**
     * Initialize the perception service.
     * QiContext is optional - only used for approach recommendations.
     */
    fun initialize(robotContext: Any?) {
        ensureMonitoringThreadReady()

        // QiContext is optional - only for approach recommendations
        if (robotContext != null) {
            try {
                val qiContext = robotContext as QiContext
                this.qiContext = qiContext
                this.humanAwareness = qiContext.humanAwareness
                Log.i(TAG, "QiContext available for approach recommendations")
            } catch (e: Exception) {
                Log.w(TAG, "QiContext not available: ${e.message}")
            }
        }

        Log.i(TAG, "PerceptionService initialized (WebSocket mode)")
        listener?.onServiceStatusChanged(true)
    }

    /**
     * Start monitoring for perception data via WebSocket.
     * Safe to call multiple times - will only connect once.
     */
    fun startMonitoring() {
        val wsClient = webSocketClient
        if (wsClient == null) {
            Log.w(TAG, "Cannot start monitoring - WebSocket client not set")
            listener?.onPerceptionError("WebSocket not configured")
            return
        }

        // Already monitoring - just notify listener silently
        if (isMonitoring) {
            listener?.onServiceStatusChanged(true)
            return
        }

        isMonitoring = true
        Log.i(TAG, "WebSocket perception monitoring started")
        listener?.onServiceStatusChanged(true)

        // Connect WebSocket and start collecting updates
        // WebSocket has auto-reconnect, so this is only needed once
        wsClient.connect()
        startPeopleCollection(wsClient)
    }
    
    /**
     * Start collecting people updates from WebSocket.
     */
    private fun startPeopleCollection(wsClient: PerceptionWebSocketClient) {
        peopleCollectionJob?.cancel()
        peopleCollectionJob = ensureScopeActive().launch {
            wsClient.peopleUpdates.collectLatest { update ->
                if (!isMonitoring) return@collectLatest
                
                try {
                    // Convert TrackedPerson to HumanInfo
                    val humanInfoList = update.people.map { person ->
                        mapTrackedPersonToHumanInfo(person)
                    }

                    // Detect events and push to UI
                    detectEvents(humanInfoList)
                    maybePushUi(humanInfoList)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing people update", e)
                }
            }
        }
    }
    
    /**
     * Force restart monitoring - useful after lifecycle changes.
     * Stops and restarts the WebSocket connection to ensure fresh state.
     */
    fun restartMonitoring() {
        Log.i(TAG, "Restarting perception monitoring...")
        stopMonitoring()
        ensureScopeActive().launch {
            kotlinx.coroutines.delay(100)
            startMonitoring()
        }
    }

    /**
     * Stop monitoring for perception data
     */
    fun stopMonitoring() {
        isMonitoring = false
        peopleCollectionJob?.cancel()
        webSocketClient?.disconnect()
        Log.i(TAG, "Perception monitoring stopped")
        listener?.onServiceStatusChanged(false)
    }

    /**
     * Convert TrackedPerson from WebSocket to HumanInfo for UI.
     */
    private fun mapTrackedPersonToHumanInfo(person: PerceptionWebSocketClient.TrackedPerson): PerceptionData.HumanInfo {
        return PerceptionData.HumanInfo().apply {
            // Use stable track ID from head server
            id = person.trackId
            trackId = person.trackId
            
            // Recognition data
            recognizedName = if (person.name != "Unknown") person.name else null
            
            // Gaze detection
            lookingAtRobot = person.lookingAtRobot
            // Convert head direction string to float: "looking"=0, "left"=+1, "right"=-1
            headDirection = when (person.headDirection) {
                "looking", "center" -> 0f
                "left" -> 1f
                "right" -> -1f
                else -> 0f
            }
            attentionState = if (person.lookingAtRobot) "LOOKING_AT_ROBOT" else "LOOKING_ELSEWHERE"
            
            // Position data
            worldYaw = person.worldYaw
            worldPitch = person.worldPitch
            distanceMeters = person.distance.toDouble()
            
            // Convert world angles to approximate X/Y position
            // worldYaw: 0=front, +=left, -=right
            // Distance * sin(yaw) gives approximate Y offset
            if (person.distance > 0) {
                val yawRad = Math.toRadians(person.worldYaw.toDouble())
                positionX = person.distance * kotlin.math.cos(yawRad)
                positionY = person.distance * kotlin.math.sin(yawRad)
            }
        }
    }

    /**
     * Shutdown and cleanup the perception service
     */
    fun shutdown() {
        stopMonitoring()
        peopleCollectionJob?.cancel()
        webSocketClient?.disconnect()
        serviceScope.cancel()
        
        this.qiContext = null
        this.listener = null
        this.humanAwareness = null

        monitoringThread?.let { thread ->
            thread.quitSafely()
            try {
                thread.join(1000)
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrupted while waiting for monitoring thread to stop", e)
            }
        }
        monitoringThread = null
        monitoringHandler = null

        Log.i(TAG, "PerceptionService shutdown")
    }

    /**
     * Push updates to UI listener.
     */
    private fun maybePushUi(list: List<PerceptionData.HumanInfo>) {
        try {
            val now = System.currentTimeMillis()
            val ids = list.map { it.id }
            val idsChanged = ids != lastUiIds
            val timeOk = (now - lastUiPushMs) >= 500L

            if (idsChanged || timeOk) {
                listener?.onHumansDetected(list)
                lastUiPushMs = now
                lastUiIds = ids
            }
        } catch (e: Exception) {
            listener?.onHumansDetected(list)
        }
    }

    /**
     * Get the recommended human to approach based on QiSDK HumanAwareness.
     * Note: QiContext must be available for this to work.
     *
     * @return Human object recommended for approach, or null if not available
     */
    fun getRecommendedHumanToApproach(): Human? {
        if (humanAwareness == null) {
            Log.d(TAG, "QiSDK HumanAwareness not available for approach recommendation")
            return null
        }

        return try {
            humanAwareness?.recommendedHumanToApproach
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get recommended human to approach", e)
            null
        }
    }

    /**
     * Find a QiSDK Human by ID (hashCode).
     * Note: With head-based perception, track IDs from the server are used instead.
     * This method is kept for backward compatibility with ApproachHuman tool.
     */
    fun getHumanById(humanId: Int): Human? {
        if (humanAwareness == null) {
            Log.d(TAG, "QiSDK HumanAwareness not available")
            return null
        }

        return try {
            val detectedHumans = humanAwareness?.humansAround ?: return null
            detectedHumans.find { it.hashCode() == humanId }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to find human by ID $humanId", e)
            null
        }
    }

    /**
     * Get all QiSDK Human objects (for backward compatibility).
     * Note: Prefer using head-based perception data instead.
     */
    fun getDetectedHumans(): List<Human> {
        if (humanAwareness == null) {
            return emptyList()
        }

        return try {
            humanAwareness?.humansAround?.toList() ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get detected humans", e)
            emptyList()
        }
    }

    /**
     * Detect events by comparing current state with previous state.
     * Uses stable track IDs from head-based perception.
     */
    private fun detectEvents(humanInfoList: List<PerceptionData.HumanInfo>) {
        val listener = eventListener ?: return
        
        // Use track ID for stable identification
        val currentIds = humanInfoList.map { it.trackId }.toSet()
        val currentLooking = humanInfoList.associate { it.trackId to it.lookingAtRobot }
        val currentDistances = humanInfoList.associate { it.trackId to it.distanceMeters.toFloat() }
        val currentNames = humanInfoList.associate { it.trackId to (it.recognizedName ?: "") }
        
        // Detect PERSON_APPEARED events
        val appearedIds = currentIds - previousTrackIds
        for (id in appearedIds) {
            val humanInfo = humanInfoList.find { it.trackId == id } ?: continue
            val event = PersonEvent(PersonEventType.PERSON_APPEARED, id)
            Log.i(TAG, "Event: PERSON_APPEARED - track $id")
            listener.onPersonEvent(event, humanInfo, humanInfoList)
        }
        
        // Detect PERSON_DISAPPEARED events
        val disappearedIds = previousTrackIds - currentIds
        for (id in disappearedIds) {
            val humanInfo = PerceptionData.HumanInfo().apply { 
                this.id = id
                this.trackId = id 
            }
            val event = PersonEvent(PersonEventType.PERSON_DISAPPEARED, id)
            Log.i(TAG, "Event: PERSON_DISAPPEARED - track $id")
            listener.onPersonEvent(event, humanInfo, humanInfoList)
            
            firedCloseApproach.remove(id)
            firedInteractionApproach.remove(id)
        }
        
        // Process events for each current human
        for (humanInfo in humanInfoList) {
            val id = humanInfo.trackId
            
            // Detect PERSON_RECOGNIZED events
            val prevName = previousRecognizedNames[id] ?: ""
            val currName = humanInfo.recognizedName ?: ""
            if (currName.isNotEmpty() && prevName.isEmpty()) {
                val event = PersonEvent(PersonEventType.PERSON_RECOGNIZED, id)
                Log.i(TAG, "Event: PERSON_RECOGNIZED - track $id as '$currName'")
                listener.onPersonEvent(event, humanInfo, humanInfoList)
            }
            
            // Detect PERSON_LOOKING / PERSON_STOPPED_LOOKING events (using head-based gaze)
            val wasLooking = previousLookingStates[id] ?: false
            val isLooking = humanInfo.lookingAtRobot
            
            if (!wasLooking && isLooking) {
                val event = PersonEvent(PersonEventType.PERSON_LOOKING, id)
                Log.i(TAG, "Event: PERSON_LOOKING - track $id")
                listener.onPersonEvent(event, humanInfo, humanInfoList)
            } else if (wasLooking && !isLooking) {
                val event = PersonEvent(PersonEventType.PERSON_STOPPED_LOOKING, id)
                Log.i(TAG, "Event: PERSON_STOPPED_LOOKING - track $id")
                listener.onPersonEvent(event, humanInfo, humanInfoList)
            }
            
            // Detect PERSON_APPROACHED_CLOSE events
            val currDist = humanInfo.distanceMeters.toFloat()
            if (currDist > 0 && currDist <= closeDistanceThreshold) {
                if (!firedCloseApproach.contains(id)) {
                    val event = PersonEvent(PersonEventType.PERSON_APPROACHED_CLOSE, id)
                    Log.i(TAG, "Event: PERSON_APPROACHED_CLOSE - track $id at ${currDist}m")
                    listener.onPersonEvent(event, humanInfo, humanInfoList)
                    firedCloseApproach.add(id)
                }
            } else {
                firedCloseApproach.remove(id)
            }
            
            // Detect PERSON_APPROACHED_INTERACTION events
            if (currDist > 0 && currDist <= interactionDistanceThreshold) {
                if (!firedInteractionApproach.contains(id)) {
                    val event = PersonEvent(PersonEventType.PERSON_APPROACHED_INTERACTION, id)
                    Log.i(TAG, "Event: PERSON_APPROACHED_INTERACTION - track $id at ${currDist}m")
                    listener.onPersonEvent(event, humanInfo, humanInfoList)
                    firedInteractionApproach.add(id)
                }
            } else {
                firedInteractionApproach.remove(id)
            }
        }
        
        // Update previous state
        previousTrackIds = currentIds
        previousLookingStates = currentLooking
        previousDistances = currentDistances
        previousRecognizedNames = currentNames
    }
}

