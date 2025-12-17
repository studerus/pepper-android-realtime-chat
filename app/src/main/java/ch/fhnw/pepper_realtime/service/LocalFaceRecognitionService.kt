package ch.fhnw.pepper_realtime.service

import android.util.Log
import ch.fhnw.pepper_realtime.BuildConfig
import ch.fhnw.pepper_realtime.di.IoDispatcher
import ch.fhnw.pepper_realtime.network.HttpClientManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for local face recognition on Pepper's head.
 * Communicates with the Python-based face recognition server running on port 5000.
 * 
 * Uses OpenCV with YuNet (detection) + SFace (recognition) for offline face recognition
 * without cloud dependencies.
 */
@Singleton
class LocalFaceRecognitionService @Inject constructor(
    private val httpClientManager: HttpClientManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    companion object {
        private const val TAG = "LocalFaceRecognition"
        
        // Default IP of Pepper's head (internal network via tablet connection)
        private const val DEFAULT_PEPPER_HEAD_IP = "198.18.0.1"
        private const val PORT = 5000
        
        // SSH commands for server management
        private const val START_SERVER_CMD = "nohup bash /home/nao/face_data/run_server.sh > /home/nao/face_data/server.log 2>&1 &"
        private const val CHECK_SERVER_CMD = "pgrep -f face_recognition_server.py"
        private const val KILL_SERVER_CMD = "pkill -f face_recognition_server.py"
    }

    private var pepperHeadIp: String = DEFAULT_PEPPER_HEAD_IP
    private val isServerStarting = AtomicBoolean(false)
    private var lastServerStartAttempt = 0L
    private val serverStartCooldownMs = 30000L // Wait 30 seconds between start attempts
    
    private val baseUrl: String
        get() = "http://$pepperHeadIp:$PORT"

    /**
     * Recognition result from the face recognition server
     */
    data class RecognitionResult(
        val faces: List<RecognizedFace>,
        val captureTimeMs: Long,
        val totalTimeMs: Long,
        val error: String? = null
    )

    /**
     * A recognized face with name and confidence
     */
    data class RecognizedFace(
        val name: String,
        val confidence: Float,
        val isKnown: Boolean,
        val location: FaceLocation
    )

    /**
     * Face bounding box location in the image
     */
    data class FaceLocation(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    /**
     * Registered face info from the database
     */
    data class RegisteredFace(
        val name: String,
        val count: Int,
        val imageUrl: String
    )

    /**
     * Perception settings from the server.
     * Matches the Python PerceptionSettings dataclass.
     */
    data class PerceptionSettings(
        // Tracker settings
        val maxAngleDistance: Float = 15.0f,       // degrees - how far a face can move between frames
        val trackTimeoutMs: Int = 3000,            // ms - when to remove lost tracks
        val minTrackAgeMs: Int = 300,              // ms - minimum age before track is reported
        
        // Recognition settings
        val recognitionThreshold: Float = 0.8f,    // cosine distance threshold (lower = stricter)
        val recognitionCooldownMs: Int = 3000,     // ms - time between recognition attempts
        
        // Gaze detection settings
        val gazeCenterTolerance: Float = 0.15f,    // how much off-center is still "looking at robot"
        
        // Streaming settings
        val updateIntervalMs: Int = 700,           // ms - target update interval
        
        // Camera settings
        // Resolution: 0=QQVGA(160x120), 1=QVGA(320x240), 2=VGA(640x480)
        val cameraResolution: Int = 1              // Default: QVGA (320x240)
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("max_angle_distance", maxAngleDistance)
            put("track_timeout_ms", trackTimeoutMs)
            put("min_track_age_ms", minTrackAgeMs)
            put("recognition_threshold", recognitionThreshold)
            put("recognition_cooldown_ms", recognitionCooldownMs)
            put("gaze_center_tolerance", gazeCenterTolerance)
            put("update_interval_ms", updateIntervalMs)
            put("camera_resolution", cameraResolution)
        }
        
        companion object {
            fun fromJson(obj: JSONObject): PerceptionSettings = PerceptionSettings(
                maxAngleDistance = obj.optDouble("max_angle_distance", 15.0).toFloat(),
                trackTimeoutMs = obj.optInt("track_timeout_ms", 3000),
                minTrackAgeMs = obj.optInt("min_track_age_ms", 300),
                recognitionThreshold = obj.optDouble("recognition_threshold", 0.8).toFloat(),
                recognitionCooldownMs = obj.optInt("recognition_cooldown_ms", 3000),
                gazeCenterTolerance = obj.optDouble("gaze_center_tolerance", 0.15).toFloat(),
                updateIntervalMs = obj.optInt("update_interval_ms", 700),
                cameraResolution = obj.optInt("camera_resolution", 1)
            )
        }
    }

    /**
     * A tracked person from the head-based perception system.
     */
    data class TrackedPerson(
        val trackId: Int,
        val name: String,
        val lookingAtRobot: Boolean,
        val headDirection: String,
        val worldYaw: Float,
        val worldPitch: Float,
        val distance: Float,
        val lastSeenMs: Long
    )

    /**
     * Result from getPeople() call.
     */
    data class PeopleResult(
        val people: List<TrackedPerson>,
        val error: String? = null
    )

    /**
     * Set the IP address of Pepper's head.
     * Default is 198.18.0.1 (internal tablet connection).
     */
    fun setPepperHeadIp(ip: String) {
        pepperHeadIp = ip
        Log.i(TAG, "Pepper head IP set to: $ip")
    }

    /**
     * Get the current Pepper head IP
     */
    fun getPepperHeadIp(): String = pepperHeadIp

    /**
     * Recognize faces from Pepper's camera.
     * Takes a photo and identifies any known faces.
     * Automatically starts the server if not running.
     * 
     * @return RecognitionResult with list of recognized faces
     */
    suspend fun recognize(): RecognitionResult = withContext(ioDispatcher) {
        try {
            // Ensure server is running before making request
            if (!isServerAvailable()) {
                Log.i(TAG, "Server not available, attempting to start...")
                if (!ensureServerRunning()) {
                    return@withContext RecognitionResult(
                        faces = emptyList(),
                        captureTimeMs = 0,
                        totalTimeMs = 0,
                        error = "Could not start face recognition server"
                    )
                }
            }
            
            val request = Request.Builder()
                .url("$baseUrl/recognize")
                .get()
                .build()

            val response = httpClientManager.executeQuickApiRequest(request)
            
            response.use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Recognition failed: HTTP ${resp.code}")
                    return@withContext RecognitionResult(
                        faces = emptyList(),
                        captureTimeMs = 0,
                        totalTimeMs = 0,
                        error = "HTTP ${resp.code}: ${resp.message}"
                    )
                }

                val body = resp.body?.string() ?: "{}"
                parseRecognitionResponse(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recognition error", e)
            RecognitionResult(
                faces = emptyList(),
                captureTimeMs = 0,
                totalTimeMs = 0,
                error = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Get list of all registered faces.
     * Automatically starts the server if not running.
     * 
     * @return List of RegisteredFace objects
     */
    suspend fun listFaces(): List<RegisteredFace> = withContext(ioDispatcher) {
        try {
            // Ensure server is running before making request
            if (!isServerAvailable()) {
                Log.i(TAG, "Server not available for listFaces, attempting to start...")
                if (!ensureServerRunning()) {
                    Log.w(TAG, "Could not start server for listFaces")
                    return@withContext emptyList()
                }
            }
            
            val request = Request.Builder()
                .url("$baseUrl/faces")
                .get()
                .build()

            val response = httpClientManager.executeQuickApiRequest(request)
            
            response.use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "List faces failed: HTTP ${resp.code}")
                    return@withContext emptyList()
                }

                val body = resp.body?.string() ?: "{}"
                parseFacesListResponse(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "List faces error", e)
            emptyList()
        }
    }

    /**
     * Register a new face from the current camera view.
     * The person should be looking at Pepper's camera.
     * Automatically starts the server if not running.
     * 
     * @param name Name to associate with the face
     * @return true if registration was successful
     */
    suspend fun registerFace(name: String): Boolean = withContext(ioDispatcher) {
        try {
            // Ensure server is running before making request
            if (!isServerAvailable()) {
                Log.i(TAG, "Server not available for registerFace, attempting to start...")
                if (!ensureServerRunning()) {
                    Log.w(TAG, "Could not start server for registerFace")
                    return@withContext false
                }
            }
            
            val request = Request.Builder()
                .url("$baseUrl/faces?name=${java.net.URLEncoder.encode(name, "UTF-8")}")
                .post("".toRequestBody())
                .build()

            val response = httpClientManager.executeQuickApiRequest(request)
            
            response.use { resp ->
                if (resp.isSuccessful) {
                    Log.i(TAG, "Face registered: $name")
                    true
                } else {
                    val error = resp.body?.string() ?: "Unknown error"
                    Log.w(TAG, "Registration failed for $name: $error")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Register face error", e)
            false
        }
    }

    /**
     * Delete a registered face.
     * Automatically starts the server if not running.
     * 
     * @param name Name of the face to delete
     * @return true if deletion was successful
     */
    suspend fun deleteFace(name: String): Boolean = withContext(ioDispatcher) {
        try {
            // Ensure server is running before making request
            if (!isServerAvailable()) {
                Log.i(TAG, "Server not available for deleteFace, attempting to start...")
                if (!ensureServerRunning()) {
                    Log.w(TAG, "Could not start server for deleteFace")
                    return@withContext false
                }
            }
            
            val request = Request.Builder()
                .url("$baseUrl/faces?name=${java.net.URLEncoder.encode(name, "UTF-8")}")
                .delete()
                .build()

            val response = httpClientManager.executeQuickApiRequest(request)
            
            response.use { resp ->
                if (resp.isSuccessful) {
                    Log.i(TAG, "Face deleted: $name")
                    true
                } else {
                    Log.w(TAG, "Delete failed for $name: HTTP ${resp.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Delete face error", e)
            false
        }
    }

    /**
     * Get the URL for a face thumbnail image.
     * Use this with an image loader like Coil or Glide.
     * 
     * @param name Name of the registered face
     * @return URL string to the face image
     */
    fun getFaceImageUrl(name: String): String {
        val encodedName = java.net.URLEncoder.encode(name, "UTF-8")
        return "$baseUrl/faces/image?name=$encodedName"
    }

    /**
     * Check if the face recognition server is reachable.
     * 
     * @return true if server is responding
     */
    suspend fun isServerAvailable(): Boolean = withContext(ioDispatcher) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/faces")
                .get()
                .build()

            val response = httpClientManager.executeQuickApiRequest(request)
            response.use { it.isSuccessful }
        } catch (e: Exception) {
            Log.d(TAG, "Server not available: ${e.message}")
            false
        }
    }

    /**
     * Ensure the face recognition server is running.
     * If not running, starts it via SSH.
     * 
     * @return true if server is running (or was started successfully)
     */
    suspend fun ensureServerRunning(): Boolean = withContext(ioDispatcher) {
        // First check if server is already running
        if (isServerAvailable()) {
            Log.d(TAG, "Server already running")
            return@withContext true
        }

        // Prevent concurrent start attempts
        if (!isServerStarting.compareAndSet(false, true)) {
            Log.d(TAG, "Server start already in progress")
            return@withContext false
        }

        // Cooldown between start attempts
        val now = System.currentTimeMillis()
        if (now - lastServerStartAttempt < serverStartCooldownMs) {
            Log.d(TAG, "Server start on cooldown")
            isServerStarting.set(false)
            return@withContext false
        }
        lastServerStartAttempt = now

        try {
            Log.i(TAG, "Starting face recognition server via SSH...")
            
            val started = startServerViaSsh()
            if (!started) {
                Log.w(TAG, "Failed to start server via SSH")
                return@withContext false
            }

            // Wait for server to initialize (model loading takes ~5-8 seconds)
            Log.i(TAG, "Waiting for server to initialize...")
            var attempts = 0
            val maxAttempts = 15 // 15 seconds max wait
            
            while (attempts < maxAttempts) {
                delay(1000)
                attempts++
                
                if (isServerAvailable()) {
                    Log.i(TAG, "Server is now available after ${attempts}s")
                    return@withContext true
                }
            }

            Log.w(TAG, "Server did not become available after ${maxAttempts}s")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure server running", e)
            false
        } finally {
            isServerStarting.set(false)
        }
    }

    /**
     * Start the face recognition server on Pepper's head via SSH.
     */
    private fun startServerViaSsh(): Boolean {
        val password = BuildConfig.PEPPER_SSH_PASSWORD
        if (password.isEmpty()) {
            Log.w(TAG, "PEPPER_SSH_PASSWORD not configured in local.properties")
            return false
        }

        return try {
            SSHClient(createSshConfig()).use { ssh ->
                ssh.connectTimeout = 5000
                ssh.timeout = 10000
                ssh.addHostKeyVerifier(PromiscuousVerifier())
                ssh.connect(pepperHeadIp, 22)
                ssh.authPassword("nao", password)

                // Kill any existing server first
                ssh.startSession().use { session ->
                    val cmd = session.exec(KILL_SERVER_CMD)
                    cmd.join(5, TimeUnit.SECONDS)
                    Log.d(TAG, "Killed existing server (if any)")
                }

                // Small delay before starting new server
                Thread.sleep(500)

                // Start the server
                ssh.startSession().use { session ->
                    val cmd = session.exec(START_SERVER_CMD)
                    cmd.join(3, TimeUnit.SECONDS)
                    Log.i(TAG, "Server start command executed")
                }

                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "SSH connection failed: ${e.javaClass.simpleName} - ${e.message}")
            false
        }
    }

    /**
     * Get currently tracked people from the head server.
     * 
     * @return PeopleResult with list of tracked persons
     */
    suspend fun getPeople(): PeopleResult = withContext(ioDispatcher) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/people")
                .get()
                .build()

            val response = httpClientManager.executeQuickApiRequest(request)
            
            response.use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext PeopleResult(
                        people = emptyList(),
                        error = "HTTP ${resp.code}: ${resp.message}"
                    )
                }

                val body = resp.body?.string() ?: "{}"
                parsePeopleResponse(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get people error", e)
            PeopleResult(
                people = emptyList(),
                error = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Fetch current perception settings from the server.
     * 
     * @return PerceptionSettings or null if failed
     */
    suspend fun fetchSettings(): PerceptionSettings? = withContext(ioDispatcher) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/settings")
                .get()
                .build()

            val response = httpClientManager.executeQuickApiRequest(request)
            
            response.use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Fetch settings failed: HTTP ${resp.code}")
                    return@withContext null
                }

                val body = resp.body?.string() ?: "{}"
                val obj = JSONObject(body)
                val settingsObj = obj.optJSONObject("settings") ?: return@withContext null
                PerceptionSettings.fromJson(settingsObj)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fetch settings error", e)
            null
        }
    }

    /**
     * Update perception settings on the server.
     * 
     * @param settings New settings to apply
     * @return true if update was successful
     */
    suspend fun updateSettings(settings: PerceptionSettings): Boolean = withContext(ioDispatcher) {
        try {
            val jsonBody = settings.toJson().toString()
            
            val request = Request.Builder()
                .url("$baseUrl/settings")
                .post(jsonBody.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            val response = httpClientManager.executeQuickApiRequest(request)
            
            response.use { resp ->
                if (resp.isSuccessful) {
                    Log.i(TAG, "Settings updated successfully")
                    true
                } else {
                    Log.w(TAG, "Update settings failed: HTTP ${resp.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update settings error", e)
            false
        }
    }

    /**
     * Stop the face recognition server on Pepper's head via SSH.
     */
    suspend fun stopServer(): Boolean = withContext(ioDispatcher) {
        val password = BuildConfig.PEPPER_SSH_PASSWORD
        if (password.isEmpty()) {
            Log.w(TAG, "PEPPER_SSH_PASSWORD not configured")
            return@withContext false
        }

        try {
            SSHClient(createSshConfig()).use { ssh ->
                ssh.connectTimeout = 5000
                ssh.timeout = 10000
                ssh.addHostKeyVerifier(PromiscuousVerifier())
                ssh.connect(pepperHeadIp, 22)
                ssh.authPassword("nao", password)

                ssh.startSession().use { session ->
                    val cmd = session.exec(KILL_SERVER_CMD)
                    cmd.join(5, TimeUnit.SECONDS)
                    Log.i(TAG, "Server stopped")
                }

                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop server: ${e.message}")
            false
        }
    }

    /**
     * Create SSH config that works on Android (avoiding Curve25519 issues).
     */
    private fun createSshConfig(): net.schmizz.sshj.Config {
        val config = DefaultConfig()
        val kex = config.keyExchangeFactories.toMutableList()
        kex.removeAll { factory ->
            val name = factory.name
            name != null && name.lowercase().contains("curve25519")
        }
        config.keyExchangeFactories = kex
        return config
    }

    // ==================== PRIVATE HELPERS ====================

    private fun parseRecognitionResponse(json: String): RecognitionResult {
        return try {
            val obj = JSONObject(json)
            
            // Check for error
            if (obj.has("error")) {
                return RecognitionResult(
                    faces = emptyList(),
                    captureTimeMs = 0,
                    totalTimeMs = 0,
                    error = obj.getString("error")
                )
            }

            // Parse timing
            val timing = obj.optJSONObject("timing")
            val captureTime = ((timing?.optDouble("capture") ?: 0.0) * 1000).toLong()
            val totalTime = ((timing?.optDouble("total") ?: 0.0) * 1000).toLong()

            // Parse faces
            val facesArray = obj.optJSONArray("faces") ?: JSONArray()
            val faces = mutableListOf<RecognizedFace>()

            for (i in 0 until facesArray.length()) {
                val faceObj = facesArray.getJSONObject(i)
                val name = faceObj.getString("name")
                val confidence = faceObj.getDouble("confidence").toFloat()
                
                val locationObj = faceObj.getJSONObject("location")
                val location = FaceLocation(
                    left = locationObj.getInt("left"),
                    top = locationObj.getInt("top"),
                    right = locationObj.getInt("right"),
                    bottom = locationObj.getInt("bottom")
                )

                faces.add(RecognizedFace(
                    name = name,
                    confidence = confidence,
                    isKnown = name != "Unknown",
                    location = location
                ))
            }

            RecognitionResult(
                faces = faces,
                captureTimeMs = captureTime,
                totalTimeMs = totalTime
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse recognition response", e)
            RecognitionResult(
                faces = emptyList(),
                captureTimeMs = 0,
                totalTimeMs = 0,
                error = "Parse error: ${e.message}"
            )
        }
    }

    private fun parseFacesListResponse(json: String): List<RegisteredFace> {
        return try {
            val obj = JSONObject(json)
            val facesArray = obj.optJSONArray("faces") ?: return emptyList()
            
            val faces = mutableListOf<RegisteredFace>()
            for (i in 0 until facesArray.length()) {
                val faceObj = facesArray.getJSONObject(i)
                faces.add(RegisteredFace(
                    name = faceObj.getString("name"),
                    count = faceObj.optInt("count", 1),
                    imageUrl = faceObj.optString("image_url", "")
                ))
            }
            faces
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse faces list", e)
            emptyList()
        }
    }

    private fun parsePeopleResponse(json: String): PeopleResult {
        return try {
            val obj = JSONObject(json)
            
            // Check for error
            if (obj.has("error")) {
                return PeopleResult(
                    people = emptyList(),
                    error = obj.getString("error")
                )
            }

            val peopleArray = obj.optJSONArray("people") ?: JSONArray()
            val people = mutableListOf<TrackedPerson>()

            for (i in 0 until peopleArray.length()) {
                val personObj = peopleArray.getJSONObject(i)
                people.add(TrackedPerson(
                    trackId = personObj.getInt("track_id"),
                    name = personObj.optString("name", "Unknown"),
                    lookingAtRobot = personObj.optBoolean("looking_at_robot", false),
                    headDirection = personObj.optString("head_direction", "unknown"),
                    worldYaw = personObj.optDouble("world_yaw", 0.0).toFloat(),
                    worldPitch = personObj.optDouble("world_pitch", 0.0).toFloat(),
                    distance = personObj.optDouble("distance", 0.0).toFloat(),
                    lastSeenMs = personObj.optLong("last_seen_ms", 0)
                ))
            }

            PeopleResult(people = people)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse people response", e)
            PeopleResult(
                people = emptyList(),
                error = "Parse error: ${e.message}"
            )
        }
    }
}

