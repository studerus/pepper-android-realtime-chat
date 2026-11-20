package ch.fhnw.pepper_realtime.service

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
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

    // Threading
    private var monitoringThread: HandlerThread? = null
    private var monitoringHandler: Handler? = null
    
    // Use a recreatable scope - gets recreated if cancelled
    private var serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var peopleCollectionJob: Job? = null
    private class ApproachFeed {
        @Volatile var latest: PerceptionWebSocketClient.PeopleUpdate? = null
    }
    @Volatile private var approachFeed: ApproachFeed? = null
    
    private fun ensureScopeActive(): CoroutineScope {
        if (!serviceScope.isActive) {
            Log.i(TAG, "Recreating service scope")
            serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        return serviceScope
    }

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
        val feed = ApproachFeed()
        approachFeed = feed
        peopleCollectionJob = ensureScopeActive().launch {
            val detector = PersonEventDetector()
            wsClient.peopleUpdates.collectLatest { update ->
                if (!isMonitoring) return@collectLatest
                if (update.receivedElapsedMs < (feed.latest?.receivedElapsedMs ?: 0L)) return@collectLatest
                feed.latest = update
                
                try {
                    // Convert TrackedPerson to HumanInfo
                    val humanInfoList = update.people.map { person ->
                        mapTrackedPersonToHumanInfo(person)
                    }

                    // Detect events and push to UI
                    eventListener?.let { detector.detectEvents(humanInfoList, it) }
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
        approachFeed = null
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
            headDirection = person.headDirection
            attentionState = if (person.lookingAtRobot) "LOOKING_AT_ROBOT" else "LOOKING_ELSEWHERE"
            
            // Position data
            worldYaw = person.worldYaw.takeIf { it.isFinite() } ?: 0f
            worldPitch = person.worldPitch
            distanceMeters = person.distance.toDouble()
            
            // Convert world angles to approximate X/Y position
            // worldYaw: 0=front, +=left, -=right
            // Distance * sin(yaw) gives approximate Y offset
            if (person.distance > 0) {
                val yawRad = Math.toRadians(worldYaw.toDouble())
                positionX = person.distance * kotlin.math.cos(yawRad)
                positionY = person.distance * kotlin.math.sin(yawRad)
            }
            
            // Freshness indicator
            // We use timeSinceSeenMs from server to calculate a synchronized timestamp relative to Android clock
            lastSeenMs = System.currentTimeMillis() - person.timeSinceSeenMs
            
            // Track age - how long this person has been tracked (directly from server)
            trackAgeMs = person.trackAgeMs
            
            // Gaze duration - how long this person has been looking at the robot
            gazeDurationMs = person.gazeDurationMs
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
     * No throttling needed - backend already limits updates to ~4-6 Hz.
     */
    private fun maybePushUi(list: List<PerceptionData.HumanInfo>) {
        listener?.onHumansDetected(list)
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

    internal class ApproachTarget(val human: Human, val stillMatches: () -> Boolean)

    internal fun resolveApproachTrack(trackId: Int): ApproachTarget? {
        val feed = approachFeed ?: return null
        val qi = qiContext ?: return null
        val sampledAt = SystemClock.elapsedRealtime()
        return try {
            val humans = humanAwareness?.humansAround?.toList() ?: return null
            val robot = qi.actuation.robotFrame()
            val positions = humans.map { human ->
                val t = human.headFrame.computeTransform(robot).transform.translation
                ApproachTrackMatcher.Position.fromRobotFrame(t.x, t.y)
            }
            fun currentIndex(): Int? {
                val now = SystemClock.elapsedRealtime()
                if (approachFeed !== feed || qi !== qiContext ||
                    webSocketClient?.connectionState?.value != PerceptionWebSocketClient.ConnectionState.CONNECTED ||
                    now - sampledAt !in 0..ApproachTrackMatcher.MAX_AGE_MS) return null
                return ApproachTrackMatcher.match(trackId, feed.latest, positions, now)
            }
            val index = currentIndex() ?: return null
            ApproachTarget(humans[index]) { currentIndex() == index }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot resolve perception track $trackId to a current QiSDK human", e)
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

}

internal class PersonEventDetector {
    private companion object { const val TAG = "PersonEventDetector" }
    private var previous = emptyMap<Int, PerceptionData.HumanInfo>()
    private val closeDistanceThreshold = 1.5f
    private val interactionDistanceThreshold = 3.0f
    private val firedCloseApproach = mutableSetOf<Int>()
    private val firedInteractionApproach = mutableSetOf<Int>()

    fun detectEvents(humanInfoList: List<PerceptionData.HumanInfo>, listener: PerceptionService.EventListener) {
        val current = humanInfoList.associateBy { it.trackId }
        
        // Detect PERSON_APPEARED events
        val appearedIds = current.keys - previous.keys
        for (id in appearedIds) {
            val humanInfo = current.getValue(id)
            val event = PersonEvent(PersonEventType.PERSON_APPEARED, id)
            Log.i(TAG, "Event: PERSON_APPEARED - track $id")
            listener.onPersonEvent(event, humanInfo, humanInfoList)
        }
        
        // Detect PERSON_DISAPPEARED events
        val disappearedIds = previous.keys - current.keys
        for (id in disappearedIds) {
            val humanInfo = previous.getValue(id)
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
            val last = previous[id]
            val prevName = last?.recognizedName.orEmpty()
            val currName = humanInfo.recognizedName ?: ""
            if (currName.isNotEmpty() && prevName.isEmpty()) {
                val event = PersonEvent(PersonEventType.PERSON_RECOGNIZED, id)
                Log.i(TAG, "Event: PERSON_RECOGNIZED - track $id as '$currName'")
                listener.onPersonEvent(event, humanInfo, humanInfoList)
            }
            
            // Detect PERSON_LOOKING / PERSON_STOPPED_LOOKING events (using head-based gaze)
            val wasLooking = last?.lookingAtRobot == true
            val isLooking = humanInfo.lookingAtRobot
            
            if (!wasLooking && isLooking) {
                val event = PersonEvent(PersonEventType.PERSON_LOOKING, id)
                Log.i(TAG, "Event: PERSON_LOOKING - track $id")
                listener.onPersonEvent(event, humanInfo, humanInfoList)
            } else if (isLooking && last != null && humanInfo.gazeDurationMs > last.gazeDurationMs) {
                val event = PersonEvent(PersonEventType.PERSON_LOOKING, id, previousGazeDurationMs = last.gazeDurationMs)
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
        previous = current
    }
}

