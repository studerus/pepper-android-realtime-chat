package ch.fhnw.pepper_realtime.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.actuation.Actuation
import com.aldebaran.qi.sdk.`object`.camera.TakePicture
import com.aldebaran.qi.sdk.`object`.human.Human
import com.aldebaran.qi.sdk.`object`.humanawareness.HumanAwareness
import com.aldebaran.qi.sdk.`object`.image.TimestampedImageHandle
import com.aldebaran.qi.sdk.builder.TakePictureBuilder
import ch.fhnw.pepper_realtime.data.PerceptionData
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Manages perception data and services for the robot.
 * Handles human detection, face recognition via Azure, and other perception capabilities.
 */
class PerceptionService {

    companion object {
        private const val TAG = "PerceptionService"
        private const val AZURE_ANALYSIS_INTERVAL_MS = 10000L // Analyze every 10 seconds when humans present
    }

    interface PerceptionListener {
        fun onHumansDetected(humans: List<PerceptionData.HumanInfo>)
        fun onPerceptionError(error: String)
        fun onServiceStatusChanged(isActive: Boolean)
    }

    private var qiContext: QiContext? = null
    private var listener: PerceptionListener? = null
    private var isMonitoring = false

    // QiSDK services
    private var humanAwareness: HumanAwareness? = null
    private var actuation: Actuation? = null
    private var robotFrame: Any? = null // Use reflection-friendly type to avoid hard dependency on geometry classes
    private var takePictureAction: Future<TakePicture>? = null

    // External services
    private var faceRecognitionService: FaceRecognitionService? = null
    private var localFaceRecognitionService: LocalFaceRecognitionService? = null
    private val isAzureAnalysisRunning = AtomicBoolean(false)
    private var lastAzureAnalysisTime = 0L
    @Volatile private var azureBackoffUntilMs = 0L
    @Volatile private var lastHumansCount = 0
    @Volatile private var triggerAzureNow = false
    
    // Local face recognition state
    private val isLocalFaceRecognitionRunning = AtomicBoolean(false)
    private var lastLocalFaceRecognitionTime = 0L
    private val localFaceRecognitionIntervalMs = 3000L // Recognize every 3 seconds
    private val localFaceNameCache = mutableMapOf<Int, String>() // humanId -> recognized name
    @Volatile private var triggerImmediateFaceRecognition = false // Trigger when new human appears

    // Threading - use background thread for QiSDK synchronous calls
    private var monitoringThread: HandlerThread? = null
    private var monitoringHandler: Handler? = null
    // Polling interval: 500ms to catch 1Hz internal refresh with minimal latency
    // (QiSDK refreshes PleasureState/ExcitementState/AttentionState at 1Hz)
    private val pollingIntervalMs = 500L
    
    private val monitoringRunnable = object : Runnable {
        override fun run() {
            if (isMonitoring) {
                monitorOnce()
                monitoringHandler?.postDelayed(this, pollingIntervalMs)
            }
        }
    }

    private val humansCacheLock = Any()
    private var humansCache: MutableList<Human> = mutableListOf()
    private val azureCacheById = mutableMapOf<Int, AzureAttrs>()

    private class AzureAttrs {
        var yaw: Double? = null
        var pitch: Double? = null
        var roll: Double? = null
        var blur: Double? = null
        var glasses: String? = null
        var quality: String? = null
        var exposure: String? = null
        var masked: Boolean? = null
    }

    private var lastUiPushMs = 0L
    private var lastUiIds: List<Int> = emptyList()
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isInitialized: Boolean
        get() = qiContext != null

    init {
        Log.d(TAG, "PerceptionService created")
        ensureMonitoringThreadReady()
    }

    /**
     * Ensures the monitoring thread and handler are ready.
     * Reinitializes them if they were shutdown or are in an invalid state.
     */
    @Synchronized
    private fun ensureMonitoringThreadReady() {
        if (monitoringThread == null || monitoringThread?.isAlive != true) {
            Log.i(TAG, "Reinitializing monitoring thread (was shutdown or invalid)")
            monitoringThread = HandlerThread("PerceptionMonitoringThread").also {
                it.start()
                monitoringHandler = Handler(it.looper)
            }
        } else if (monitoringHandler == null) {
            Log.i(TAG, "Reinitializing monitoring handler")
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
     * Set the local face recognition service for identifying known faces.
     * Should be called after the service is injected by Hilt/Dagger.
     */
    fun setLocalFaceRecognitionService(service: LocalFaceRecognitionService) {
        this.localFaceRecognitionService = service
        Log.i(TAG, "Local face recognition service configured")
    }

    /**
     * Initialize the perception service with QiContext.
     * Reinitializes internal resources if they were previously shutdown.
     */
    fun initialize(robotContext: Any?) {
        if (robotContext == null) {
            Log.w(TAG, "PerceptionService: No robot context available.")
            return
        }

        // Reinitialize monitoring thread if it was shutdown
        ensureMonitoringThreadReady()

        val qiContext = robotContext as QiContext
        this.qiContext = qiContext

        try {
            this.humanAwareness = qiContext.humanAwareness
            this.actuation = qiContext.actuation
            this.robotFrame = actuation?.robotFrame()

            // Build the TakePicture action once for reuse
            this.takePictureAction = TakePictureBuilder.with(qiContext).buildAsync()

            // Initialize Azure Face Recognition Service
            this.faceRecognitionService = FaceRecognitionService()
            
            // Note: LocalFaceRecognitionService is injected via setLocalFaceRecognitionService()

            // Event-driven trigger: react to humansAround changes
            try {
                this.humanAwareness?.addOnHumansAroundChangedListener { humans ->
                    try {
                        val count = humans?.size ?: 0
                        Log.d(TAG, "OnHumansAroundChanged: $count humans detected")
                        if (count != lastHumansCount) {
                            // Trigger immediate face recognition when human count INCREASES
                            if (count > lastHumansCount && count > 0) {
                                triggerImmediateFaceRecognition = true
                                Log.i(TAG, "New human detected! Triggering immediate face recognition.")
                            }
                            lastHumansCount = count
                            // trigger Azure immediately when humans appear/disappear (only if there is at least one human)
                            triggerAzureNow = count > 0
                            Log.i(TAG, "Human count changed to: $count")
                        }
                        synchronized(humansCacheLock) {
                            humansCache = humans?.toMutableList() ?: mutableListOf()
                            Log.d(TAG, "humansCache updated with ${humansCache.size} humans")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in OnHumansAroundChanged listener", e)
                    }
                }
                Log.i(TAG, "OnHumansAroundChangedListener registered successfully")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to attach OnHumansAroundChangedListener", e)
            }

            Log.i(TAG, "PerceptionService initialized: HumanAwareness, Actuation, and FaceRecognition ready")
            listener?.onServiceStatusChanged(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize perception services", e)
            listener?.onPerceptionError("Init failed: ${e.message}")
        }
    }

    /**
     * Start monitoring for perception data
     */
    fun startMonitoring() {
        if (!isInitialized || humanAwareness == null) {
            Log.w(TAG, "Cannot start monitoring - service not initialized")
            listener?.onPerceptionError("Service not initialized")
            return
        }

        if (isMonitoring) {
            Log.i(TAG, "Perception monitoring already active")
            return
        }

        isMonitoring = true
        Log.i(TAG, "Perception monitoring started")
        listener?.onServiceStatusChanged(true)

        // Start lightweight polling with Handler (Android-lifecycle-aware)
        monitoringHandler?.post(monitoringRunnable)
    }

    /**
     * Stop monitoring for perception data
     */
    fun stopMonitoring() {
        isMonitoring = false
        Log.i(TAG, "Perception monitoring stopped")
        listener?.onServiceStatusChanged(false)
        // Remove any pending callbacks
        monitoringHandler?.removeCallbacks(monitoringRunnable)
    }

    /**
     * Monitoring loop: periodically polls HumanAwareness and emits structured data
     */
    private fun monitorOnce() {
        if (!isMonitoring || humanAwareness == null) {
            Log.d(TAG, "monitorOnce: skipped (isMonitoring=$isMonitoring, humanAwareness=${humanAwareness != null})")
            return
        }

        try {
            val humansSnapshot: List<Human>
            synchronized(humansCacheLock) {
                humansSnapshot = humansCache.toList()
                Log.d(TAG, "monitorOnce: humansCache.size=${humansCache.size}, snapshot.size=${humansSnapshot.size}")
            }

            if (humansSnapshot.isEmpty()) {
                Log.d(TAG, "monitorOnce: snapshot empty, sending empty list to listener")
                listener?.onHumansDetected(emptyList())
                return
            }

            Log.d(TAG, "monitorOnce: processing ${humansSnapshot.size} humans")

            // Build base list
            val humanInfoList = mutableListOf<PerceptionData.HumanInfo>()
            for (h in humansSnapshot) {
                val info = mapHuman(h)
                // Apply cached Azure attrs so UI does not flip back to N/A
                azureCacheById[info.id]?.let { cached ->
                    cached.yaw?.let { info.azureYawDeg = it }
                    cached.pitch?.let { info.azurePitchDeg = it }
                    cached.roll?.let { info.azureRollDeg = it }
                    cached.glasses?.let { info.glassesType = it }
                    cached.masked?.let { info.isMasked = it }
                    cached.quality?.let { info.imageQuality = it }
                    cached.blur?.let { info.blurLevel = it }
                    cached.exposure?.let { info.exposureLevel = it }
                }
                humanInfoList.add(info)
            }

            val currentCount = humansSnapshot.size
            val timeWindowElapsed = (System.currentTimeMillis() - lastAzureAnalysisTime) >= AZURE_ANALYSIS_INTERVAL_MS
            val shouldRunAzureAnalysis = faceRecognitionService?.isConfigured() == true &&
                    !isAzureAnalysisRunning.get() &&
                    (triggerAzureNow || (timeWindowElapsed && currentCount > 0)) &&
                    (System.currentTimeMillis() >= azureBackoffUntilMs)

            if (shouldRunAzureAnalysis) {
                isAzureAnalysisRunning.set(true)
                lastAzureAnalysisTime = System.currentTimeMillis()
                triggerAzureNow = false
                // Decouple Qi action start from this scheduler thread
                serviceScope.launch {
                    takePictureAndAnalyze(humansSnapshot, humanInfoList)
                }
            } else {
                maybePushUi(humanInfoList)
            }
            
            // Run local face recognition in parallel (independent of Azure)
            // Triggers: 1) Regular interval (3s), or 2) New human appeared
            val timeIntervalElapsed = (System.currentTimeMillis() - lastLocalFaceRecognitionTime) >= localFaceRecognitionIntervalMs
            val wantsToRun = localFaceRecognitionService != null &&
                    currentCount > 0 &&
                    (timeIntervalElapsed || triggerImmediateFaceRecognition)
            val canRun = !isLocalFaceRecognitionRunning.get()
                    
            if (wantsToRun && canRun) {
                if (triggerImmediateFaceRecognition) {
                    Log.i(TAG, "Running immediate face recognition for new human")
                }
                // Only clear the trigger when we actually start the recognition
                triggerImmediateFaceRecognition = false
                isLocalFaceRecognitionRunning.set(true)
                lastLocalFaceRecognitionTime = System.currentTimeMillis()
                serviceScope.launch {
                    runLocalFaceRecognition(humanInfoList)
                }
            } else if (wantsToRun && !canRun) {
                // Recognition already running - keep the trigger for next poll cycle
                Log.d(TAG, "Face recognition busy, will retry on next poll (trigger preserved: $triggerImmediateFaceRecognition)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Monitor tick failed", e)
            listener?.onPerceptionError("Monitor failed: ${e.message}")
        }
    }

    private fun takePictureAndAnalyze(
        pepperHumans: List<Human>,
        initialHumanInfo: List<PerceptionData.HumanInfo>
    ) {
        val action = takePictureAction ?: run {
            isAzureAnalysisRunning.set(false)
            return
        }

        action.andThenCompose { takePicture -> takePicture.async().run() }
            .andThenConsume { timestampedImageHandle ->
                val bitmap = convertToBitmap(timestampedImageHandle)
                if (bitmap == null) {
                    // Send initial data if picture fails
                    listener?.onHumansDetected(initialHumanInfo)
                    isAzureAnalysisRunning.set(false)
                    return@andThenConsume
                }

                // Use coroutine to call suspend function
                serviceScope.launch {
                    try {
                        val faces = faceRecognitionService?.detectFaces(bitmap) ?: emptyList()
                        val fusedList = fuseHumanAndFaceData(pepperHumans, initialHumanInfo, faces)
                        maybePushUi(fusedList)
                    } catch (e: FaceRecognitionService.RateLimitException) {
                        azureBackoffUntilMs = System.currentTimeMillis() + e.retryAfterMs
                        Log.e(TAG, "Azure face detection rate limited", e)
                        maybePushUi(initialHumanInfo)
                    } catch (e: Exception) {
                        Log.e(TAG, "Azure face detection failed", e)
                        maybePushUi(initialHumanInfo)
                    } finally {
                        isAzureAnalysisRunning.set(false)
                    }
                }
            }
    }

    private fun fuseHumanAndFaceData(
        pepperHumans: List<Human>,
        humanInfoList: List<PerceptionData.HumanInfo>,
        azureFaces: List<FaceRecognitionService.FaceInfo>?
    ): List<PerceptionData.HumanInfo> {
        if (azureFaces.isNullOrEmpty() || pepperHumans.isEmpty()) {
            return humanInfoList // Nothing to match, return original list
        }

        // --- Sort Azure faces by horizontal position (left to right) ---
        val sortedAzureFaces = azureFaces.sortedBy { it.left }

        // --- Sort Pepper humans by their angle relative to the robot's front (left to right) ---
        val sortedPepperHumans = pepperHumans.sortedByDescending { h ->
            try {
                val xy = getXYTranslationReflect(h.headFrame, robotFrame)
                if (xy != null) atan2(xy[1], xy[0]) else 0.0
            } catch (e: Exception) {
                0.0
            }
        }

        // Re-create the humanInfoList in the new sorted order
        val sortedHumanInfoList = mutableListOf<PerceptionData.HumanInfo>()
        for (sortedHuman in sortedPepperHumans) {
            humanInfoList.find { it.id == sortedHuman.hashCode() }?.let {
                sortedHumanInfoList.add(it)
            }
        }

        if (sortedHumanInfoList.size != humanInfoList.size) {
            return humanInfoList // sort failed
        }

        // --- Match sorted lists element by element ---
        val matchCount = minOf(sortedHumanInfoList.size, sortedAzureFaces.size)
        for (i in 0 until matchCount) {
            val humanInfo = sortedHumanInfoList[i]
            val azureFace = sortedAzureFaces[i]

            // --- Enrich HumanInfo with Azure Data (cache last good values) ---
            azureFace.yawDeg?.let { humanInfo.azureYawDeg = it }
            azureFace.pitchDeg?.let { humanInfo.azurePitchDeg = it }
            azureFace.rollDeg?.let { humanInfo.azureRollDeg = it }
            azureFace.glassesType?.let { humanInfo.glassesType = it }
            azureFace.isMasked?.let { humanInfo.isMasked = it }
            azureFace.imageQuality?.let { humanInfo.imageQuality = it }
            azureFace.blurValue?.let { humanInfo.blurLevel = it }
            azureFace.exposureLevel?.let { humanInfo.exposureLevel = it }

            // Update cache
            val a = azureCacheById.getOrPut(humanInfo.id) { AzureAttrs() }
            a.yaw = humanInfo.azureYawDeg
            a.pitch = humanInfo.azurePitchDeg
            a.roll = humanInfo.azureRollDeg
            a.glasses = humanInfo.glassesType
            a.masked = humanInfo.isMasked
            a.quality = humanInfo.imageQuality
            a.blur = humanInfo.blurLevel
            a.exposure = humanInfo.exposureLevel
        }

        return sortedHumanInfoList
    }

    private fun convertToBitmap(timestampedImageHandle: TimestampedImageHandle): Bitmap? {
        return try {
            val encodedImageHandle = timestampedImageHandle.image
            val encodedImage = encodedImageHandle.value
            val buffer = encodedImage.data
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert EncodedImage to Bitmap", e)
            null
        }
    }

    /**
     * Map QiSDK Human to UI-friendly HumanInfo
     */
    private fun mapHuman(human: Human): PerceptionData.HumanInfo {
        val info = PerceptionData.HumanInfo()
        info.id = human.hashCode() // Use hashCode as a temporary, unstable ID for now

        try {
            // Basic demographics & states
            try {
                info.estimatedAge = human.estimatedAge.years
            } catch (ignored: Exception) {
            }

            human.estimatedGender?.let { info.gender = it.toString() }

            human.emotion?.let { emotion ->
                info.pleasureState = emotion.pleasure.toString()
                info.excitementState = emotion.excitement.toString()
            }

            human.engagementIntention?.let { info.engagementState = it.toString() }

            human.facialExpressions?.smile?.let { info.smileState = it.toString() }

            human.attention?.let { info.attentionState = it.toString() }

            // Distance computation (robot frame vs human head frame)
            try {
                val humanFrame = human.headFrame
                info.distanceMeters = computeDistanceMetersReflect(humanFrame, robotFrame)
            } catch (distEx: Exception) {
                // keep default -1.0 on failure
            }

            // Extract face picture with improved error handling and thread safety
            info.facePicture = extractFacePictureSafely(human, info.id)

            // Compute basic emotion for dashboard
            info.basicEmotion = PerceptionData.HumanInfo.computeBasicEmotion(info.excitementState, info.pleasureState)
        } catch (e: Exception) {
            Log.w(TAG, "mapHuman: partial data due to exception", e)
        }

        return info
    }

    /**
     * Helper: get XY translation between headFrame and base frame using reflection.
     */
    private fun getXYTranslationReflect(headFrame: Any?, baseFrame: Any?): DoubleArray? {
        if (headFrame == null || baseFrame == null) return null

        return try {
            val computeTransform = headFrame.javaClass.methods.find {
                it.name == "computeTransform" && it.parameterTypes.size == 1
            } ?: return null

            val transformTime = computeTransform.invoke(headFrame, baseFrame) ?: return null
            val transform = transformTime.javaClass.getMethod("getTransform").invoke(transformTime) ?: return null
            val translation = transform.javaClass.getMethod("getTranslation").invoke(transform) ?: return null
            val xObj = translation.javaClass.getMethod("getX").invoke(translation)
            val yObj = translation.javaClass.getMethod("getY").invoke(translation)

            if (xObj !is Number || yObj !is Number) return null
            doubleArrayOf(xObj.toDouble(), yObj.toDouble())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Safely extract face picture from human with comprehensive error handling
     * Based on QiSDK tutorial but with improved null checks and exception handling
     */
    private fun extractFacePictureSafely(human: Human?, humanId: Int): Bitmap? {
        if (human == null) {
            Log.d(TAG, "Human object is null for ID $humanId")
            return null
        }

        return try {
            val facePicture = human.facePicture
            if (facePicture == null) {
                Log.d(TAG, "Face picture object is null for human $humanId")
                return null
            }

            val image = facePicture.image
            if (image == null) {
                Log.d(TAG, "Image object is null for human $humanId")
                return null
            }

            val facePictureBuffer = try {
                image.data
            } catch (e: Exception) {
                Log.d(TAG, "Failed to get image data for human $humanId: ${e.message}")
                return null
            }

            if (facePictureBuffer == null) {
                Log.d(TAG, "Face picture buffer is null for human $humanId")
                return null
            }

            try {
                facePictureBuffer.rewind()
                val pictureBufferSize = facePictureBuffer.remaining()

                if (pictureBufferSize <= 0) {
                    Log.d(TAG, "Face picture buffer empty for human $humanId (size: $pictureBufferSize)")
                    return null
                }

                if (pictureBufferSize > 5 * 1024 * 1024) {
                    Log.w(TAG, "Face picture buffer too large for human $humanId: $pictureBufferSize bytes")
                    return null
                }

                val facePictureArray = ByteArray(pictureBufferSize)
                facePictureBuffer.get(facePictureArray)

                val bitmap = BitmapFactory.decodeByteArray(facePictureArray, 0, pictureBufferSize)

                if (bitmap != null) {
                    Log.i(TAG, "✅ Face picture extracted for human $humanId " +
                            "(${bitmap.width}x${bitmap.height}, $pictureBufferSize bytes)")
                    bitmap
                } else {
                    Log.d(TAG, "Failed to decode face picture bitmap for human $humanId (invalid image data)")
                    null
                }
            } catch (oom: OutOfMemoryError) {
                Log.w(TAG, "Out of memory processing face picture for human $humanId")
                null
            } catch (bufferEx: Exception) {
                Log.w(TAG, "Buffer processing failed for human $humanId: ${bufferEx.message}")
                null
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Face picture extraction failed for human $humanId: ${ex.javaClass.simpleName} - ${ex.message}")
            null
        }
    }

    /**
     * Compute planar distance using reflection to avoid compile-time dependency on geometry types.
     */
    private fun computeDistanceMetersReflect(humanFrame: Any?, robotFrame: Any?): Double {
        if (humanFrame == null || robotFrame == null) {
            Log.w(TAG, "computeDistanceMetersReflect: frame null (human=${humanFrame != null}, robot=${robotFrame != null})")
            return -1.0
        }

        return try {
            val computeTransform = humanFrame.javaClass.methods.find {
                it.name == "computeTransform" && it.parameterTypes.size == 1
            }

            if (computeTransform == null) {
                Log.w(TAG, "computeDistanceMetersReflect: computeTransform not found on ${humanFrame.javaClass}")
                return -1.0
            }

            val transformTime = computeTransform.invoke(humanFrame, robotFrame) ?: return -1.0
            val transform = transformTime.javaClass.getMethod("getTransform").invoke(transformTime) ?: return -1.0
            val translation = transform.javaClass.getMethod("getTranslation").invoke(transform) ?: return -1.0

            val xObj = translation.javaClass.getMethod("getX").invoke(translation)
            val yObj = translation.javaClass.getMethod("getY").invoke(translation)

            if (xObj !is Number || yObj !is Number) return -1.0

            val x = xObj.toDouble()
            val y = yObj.toDouble()
            sqrt(x * x + y * y)
        } catch (e: Exception) {
            Log.w(TAG, "computeDistanceMetersReflect failed: ${e.javaClass.simpleName}: ${e.message}")
            -1.0
        }
    }

    /**
     * Shutdown and cleanup the perception service
     */
    fun shutdown() {
        stopMonitoring()
        this.qiContext = null
        this.listener = null

        // Remove listeners on background thread to avoid NetworkOnMainThreadException
        // Use monitoring thread if available, otherwise use a temporary thread
        val humanAwarenessRef = this.humanAwareness
        if (humanAwarenessRef != null) {
            val handler = monitoringHandler
            val thread = monitoringThread
            if (handler != null && thread != null && thread.isAlive) {
                // Use existing monitoring thread
                handler.post {
                    try {
                        humanAwarenessRef.removeAllOnHumansAroundChangedListeners()
                        Log.d(TAG, "HumanAwareness listeners removed successfully")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed removing humansAround listeners", e)
                    }
                }
            } else {
                // Monitoring thread not available, use temporary thread for cleanup
                Thread({
                    try {
                        humanAwarenessRef.removeAllOnHumansAroundChangedListeners()
                        Log.d(TAG, "HumanAwareness listeners removed successfully (temp thread)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed removing humansAround listeners", e)
                    }
                }, "PerceptionCleanup").start()
            }
        }

        this.humanAwareness = null
        this.actuation = null
        this.robotFrame = null

        // Clean up monitoring handler and background thread
        monitoringHandler?.removeCallbacks(monitoringRunnable)
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

    private fun maybePushUi(list: List<PerceptionData.HumanInfo>) {
        try {
            // Apply cached recognized names before pushing to UI
            for (info in list) {
                localFaceNameCache[info.id]?.let { cachedName ->
                    info.recognizedName = cachedName
                }
            }
            
            val now = System.currentTimeMillis()
            val ids = list.map { it.id }
            val idsChanged = ids != lastUiIds
            val timeOk = (now - lastUiPushMs) >= 1000L

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
     * Run local face recognition on Pepper's head camera.
     * Updates the face name cache with recognized names.
     */
    private suspend fun runLocalFaceRecognition(humanInfoList: List<PerceptionData.HumanInfo>) {
        try {
            val service = localFaceRecognitionService ?: return
            
            val result = service.recognize()
            
            if (result.error != null) {
                Log.w(TAG, "Local face recognition error: ${result.error}")
                return
            }
            
            // Get known faces (exclude "Unknown")
            val knownFaces = result.faces.filter { it.isKnown }
            
            if (knownFaces.isEmpty()) {
                Log.d(TAG, "Local face recognition: no known faces detected")
                // Clear cache for humans that are no longer recognized
                // (but keep cache entries for humans still present)
                return
            }
            
            Log.i(TAG, "Local face recognition: found ${knownFaces.size} known face(s): ${knownFaces.map { it.name }}")
            
            // Match recognized faces to HumanInfo by horizontal position
            // Similar logic to Azure face matching - sort both by horizontal position
            val sortedKnownFaces = knownFaces.sortedBy { it.location.left }
            val sortedHumans = humanInfoList.sortedBy { it.id } // Simple sort for now
            
            // Simple 1:1 matching when counts match
            if (sortedKnownFaces.size == sortedHumans.size) {
                for (i in sortedKnownFaces.indices) {
                    val recognizedFace = sortedKnownFaces[i]
                    val humanInfo = sortedHumans[i]
                    
                    humanInfo.recognizedName = recognizedFace.name
                    localFaceNameCache[humanInfo.id] = recognizedFace.name
                    
                    Log.i(TAG, "Matched face '${recognizedFace.name}' to human ${humanInfo.id} (confidence: ${recognizedFace.confidence})")
                }
            } else if (sortedKnownFaces.size == 1 && sortedHumans.isNotEmpty()) {
                // Only one face recognized - assign to first/closest human
                val recognizedFace = sortedKnownFaces.first()
                val humanInfo = sortedHumans.first()
                
                humanInfo.recognizedName = recognizedFace.name
                localFaceNameCache[humanInfo.id] = recognizedFace.name
                
                Log.i(TAG, "Single face '${recognizedFace.name}' matched to human ${humanInfo.id}")
            } else {
                Log.d(TAG, "Face count mismatch: ${sortedKnownFaces.size} faces vs ${sortedHumans.size} humans - using position-based matching")
                // For mismatched counts, match what we can
                val matchCount = minOf(sortedKnownFaces.size, sortedHumans.size)
                for (i in 0 until matchCount) {
                    val recognizedFace = sortedKnownFaces[i]
                    val humanInfo = sortedHumans[i]
                    
                    humanInfo.recognizedName = recognizedFace.name
                    localFaceNameCache[humanInfo.id] = recognizedFace.name
                }
            }
            
            // Trigger UI update with new names
            maybePushUi(humanInfoList)
            
        } catch (e: Exception) {
            Log.e(TAG, "Local face recognition failed", e)
        } finally {
            isLocalFaceRecognitionRunning.set(false)
        }
    }

    /**
     * Get the recommended human to approach based on QiSDK HumanAwareness.
     * This is needed for the ApproachHuman tool integration.
     *
     * @return Human object recommended for approach, or null if none suitable
     */
    fun getRecommendedHumanToApproach(): Human? {
        if (!isInitialized || humanAwareness == null) {
            Log.w(TAG, "Cannot get recommended human - service not initialized")
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
     * Find a specific Human object by its ID (hashCode).
     * Used by ApproachHuman tool to convert HumanInfo ID back to QiSDK Human object.
     *
     * @param humanId The ID (hashCode) of the human to find
     * @return Human object with matching ID, or null if not found
     */
    fun getHumanById(humanId: Int): Human? {
        if (!isInitialized || humanAwareness == null) {
            Log.w(TAG, "Cannot find human by ID - service not initialized")
            return null
        }

        return try {
            val detectedHumans = humanAwareness?.humansAround ?: return null
            detectedHumans.find { it.hashCode() == humanId }.also {
                if (it == null) {
                    Log.d(TAG, "Human with ID $humanId not found in current detection")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to find human by ID $humanId", e)
            null
        }
    }

    /**
     * Get all currently detected humans as QiSDK Human objects.
     * Provides direct access to the raw Human objects for tools that need them.
     *
     * @return List of detected Human objects, empty list if none or service not ready
     */
    fun getDetectedHumans(): List<Human> {
        if (!isInitialized || humanAwareness == null) {
            Log.w(TAG, "Cannot get detected humans - service not initialized")
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

