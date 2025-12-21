package ch.fhnw.pepper_realtime.service

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebSocket client for real-time perception streaming.
 * Handles all bidirectional communication with the Python server on Pepper's head.
 * 
 * Incoming messages (Server → Client):
 * - people: Tracked people updates
 * - settings: Current settings
 * - faces: List of known faces
 * - face_registered: Registration result
 * - face_deleted: Deletion result
 * - pong: Keep-alive response
 * 
 * Outgoing messages (Client → Server):
 * - get_settings: Request settings
 * - set_settings: Update settings
 * - register_face: Register new face
 * - delete_face: Delete face
 * - list_faces: Request face list
 * - ping: Keep-alive
 */
@Singleton
class PerceptionWebSocketClient @Inject constructor() {

    companion object {
        private const val TAG = "PerceptionWS"
        private const val WS_PORT = 5002
        private const val RECONNECT_DELAY_MS = 3000L
        private const val PING_INTERVAL_MS = 30000L
    }

    // Connection state
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // Incoming data flows
    private val _peopleUpdates = MutableSharedFlow<PeopleUpdate>(extraBufferCapacity = 10)
    val peopleUpdates: SharedFlow<PeopleUpdate> = _peopleUpdates

    private val _settingsUpdates = MutableSharedFlow<SettingsUpdate>(extraBufferCapacity = 5)
    val settingsUpdates: SharedFlow<SettingsUpdate> = _settingsUpdates

    private val _facesUpdates = MutableSharedFlow<List<FaceInfo>>(extraBufferCapacity = 5)
    val facesUpdates: SharedFlow<List<FaceInfo>> = _facesUpdates

    private val _faceOperationResults = MutableSharedFlow<FaceOperationResult>(extraBufferCapacity = 5)
    val faceOperationResults: SharedFlow<FaceOperationResult> = _faceOperationResults

    // Internal state
    private var webSocket: WebSocket? = null
    private var pepperHeadIp: String = "198.18.0.1"
    private var shouldReconnect = false
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // No timeout for WebSocket
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    data class PeopleUpdate(
        val people: List<TrackedPerson>,
        val timestamp: Long
    )

    data class TrackedPerson(
        val trackId: Int,
        val name: String,
        val lookingAtRobot: Boolean,
        val headDirection: String,
        val worldYaw: Float,
        val worldPitch: Float,
        val distance: Float,
        val lastSeenMs: Long,
        val timeSinceSeenMs: Long,
        val trackAgeMs: Long,
        val gazeDurationMs: Long
    )

    data class SettingsUpdate(
        val maxAngleDistance: Float,
        val trackTimeoutMs: Int,
        val minTrackAgeMs: Int,
        val recognitionThreshold: Float,
        val recognitionCooldownMs: Int,
        val gazeCenterTolerance: Float,
        val updateIntervalMs: Int,
        val cameraResolution: Int
    )

    data class FaceInfo(
        val name: String,
        val count: Int
    )

    data class FaceOperationResult(
        val operation: String, // "registered" or "deleted"
        val success: Boolean,
        val name: String,
        val error: String?
    )

    /**
     * Set the IP address of Pepper's head.
     */
    fun setPepperHeadIp(ip: String) {
        pepperHeadIp = ip
        Log.i(TAG, "Pepper head IP set to: $ip")
    }

    /**
     * Connect to the WebSocket server.
     */
    fun connect() {
        if (_connectionState.value == ConnectionState.CONNECTING || 
            _connectionState.value == ConnectionState.CONNECTED) {
            Log.d(TAG, "Already connected or connecting")
            return
        }

        shouldReconnect = true
        doConnect()
    }

    private fun doConnect() {
        _connectionState.value = ConnectionState.CONNECTING
        
        val url = "ws://$pepperHeadIp:$WS_PORT"
        Log.i(TAG, "Connecting to $url...")

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                _connectionState.value = ConnectionState.CONNECTED
                startPingLoop()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                handleDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                handleDisconnect()
            }
        })
    }

    private fun handleDisconnect() {
        _connectionState.value = ConnectionState.DISCONNECTED
        pingJob?.cancel()
        
        if (shouldReconnect) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (shouldReconnect && _connectionState.value == ConnectionState.DISCONNECTED) {
                Log.i(TAG, "Attempting reconnect...")
                doConnect()
            }
        }
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive && _connectionState.value == ConnectionState.CONNECTED) {
                delay(PING_INTERVAL_MS)
                sendMessage(JSONObject().put("type", "ping"))
            }
        }
    }

    /**
     * Disconnect from the WebSocket server.
     */
    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        pingJob?.cancel()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        Log.i(TAG, "Disconnected")
    }

    /**
     * Send a message to the server.
     */
    private fun sendMessage(message: JSONObject): Boolean {
        val ws = webSocket
        if (ws == null || _connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot send message: not connected")
            return false
        }
        return ws.send(message.toString())
    }

    /**
     * Handle incoming message from server.
     */
    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")

            when (type) {
                "people" -> {
                    val data = json.optJSONObject("data") ?: return
                    val timestamp = json.optLong("timestamp", System.currentTimeMillis())
                    val peopleArray = data.optJSONArray("people") ?: JSONArray()
                    
                    val people = mutableListOf<TrackedPerson>()
                    for (i in 0 until peopleArray.length()) {
                        val p = peopleArray.getJSONObject(i)
                        people.add(TrackedPerson(
                            trackId = p.getInt("track_id"),
                            name = p.optString("name", "Unknown"),
                            lookingAtRobot = p.optBoolean("looking_at_robot", false),
                            headDirection = p.optString("head_direction", "unknown"),
                            worldYaw = p.optDouble("world_yaw", 0.0).toFloat(),
                            worldPitch = p.optDouble("world_pitch", 0.0).toFloat(),
                            distance = p.optDouble("distance", 0.0).toFloat(),
                            lastSeenMs = p.optLong("last_seen_ms", 0),
                            timeSinceSeenMs = p.optLong("time_since_seen_ms", 0),
                            trackAgeMs = p.optLong("track_age_ms", 0),
                            gazeDurationMs = p.optLong("gaze_duration_ms", 0)
                        ))
                    }
                    
                    scope.launch {
                        _peopleUpdates.emit(PeopleUpdate(people, timestamp))
                    }
                }

                "settings" -> {
                    val data = json.optJSONObject("data") ?: return
                    val settings = SettingsUpdate(
                        maxAngleDistance = data.optDouble("max_angle_distance", 15.0).toFloat(),
                        trackTimeoutMs = data.optInt("track_timeout_ms", 3000),
                        minTrackAgeMs = data.optInt("min_track_age_ms", 300),
                        recognitionThreshold = data.optDouble("recognition_threshold", 0.65).toFloat(),
                        recognitionCooldownMs = data.optInt("recognition_cooldown_ms", 3000),
                        gazeCenterTolerance = data.optDouble("gaze_center_tolerance", 0.15).toFloat(),
                        updateIntervalMs = data.optInt("update_interval_ms", 150),
                        cameraResolution = data.optInt("camera_resolution", 1)
                    )
                    scope.launch {
                        _settingsUpdates.emit(settings)
                    }
                }

                "faces" -> {
                    val dataArray = json.optJSONArray("data") ?: JSONArray()
                    val faces = mutableListOf<FaceInfo>()
                    for (i in 0 until dataArray.length()) {
                        val f = dataArray.getJSONObject(i)
                        faces.add(FaceInfo(
                            name = f.getString("name"),
                            count = f.optInt("count", 1)
                        ))
                    }
                    scope.launch {
                        _facesUpdates.emit(faces)
                    }
                }

                "face_registered" -> {
                    val data = json.optJSONObject("data") ?: return
                    scope.launch {
                        _faceOperationResults.emit(FaceOperationResult(
                            operation = "registered",
                            success = data.optBoolean("success", false),
                            name = data.optString("name", ""),
                            error = if (data.has("error") && !data.isNull("error")) data.getString("error") else null
                        ))
                    }
                }

                "face_deleted" -> {
                    val data = json.optJSONObject("data") ?: return
                    scope.launch {
                        _faceOperationResults.emit(FaceOperationResult(
                            operation = "deleted",
                            success = data.optBoolean("success", false),
                            name = data.optString("name", ""),
                            error = if (data.has("error") && !data.isNull("error")) data.getString("error") else null
                        ))
                    }
                }

                "pong" -> {
                    // Keep-alive response, nothing to do
                }

                "error" -> {
                    val data = json.optJSONObject("data")
                    Log.e(TAG, "Server error: ${data?.optString("message", "Unknown error")}")
                }

                else -> {
                    Log.d(TAG, "Unknown message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: ${e.message}")
        }
    }

    // ==================== PUBLIC API ====================

    /**
     * Request current settings from server.
     */
    fun requestSettings() {
        sendMessage(JSONObject().put("type", "get_settings"))
    }

    /**
     * Update settings on server.
     */
    fun updateSettings(settings: LocalFaceRecognitionService.PerceptionSettings) {
        val data = JSONObject().apply {
            put("max_angle_distance", settings.maxAngleDistance)
            put("track_timeout_ms", settings.trackTimeoutMs)
            put("min_track_age_ms", settings.minTrackAgeMs)
            put("recognition_threshold", settings.recognitionThreshold)
            put("recognition_cooldown_ms", settings.recognitionCooldownMs)
            put("gaze_center_tolerance", settings.gazeCenterTolerance)
            put("update_interval_ms", settings.updateIntervalMs)
            put("camera_resolution", settings.cameraResolution)
        }
        sendMessage(JSONObject()
            .put("type", "set_settings")
            .put("data", data))
    }

    /**
     * Register a new face with the given name.
     * The server will capture an image from the camera.
     */
    fun registerFace(name: String) {
        sendMessage(JSONObject()
            .put("type", "register_face")
            .put("data", JSONObject().put("name", name)))
    }

    /**
     * Delete a face from the database.
     */
    fun deleteFace(name: String) {
        sendMessage(JSONObject()
            .put("type", "delete_face")
            .put("data", JSONObject().put("name", name)))
    }

    /**
     * Request list of known faces.
     */
    fun requestFaces() {
        sendMessage(JSONObject().put("type", "list_faces"))
    }
}

