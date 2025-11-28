package io.github.anonymous.pepper_realtime.manager

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.Promise
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.HolderBuilder
import com.aldebaran.qi.sdk.`object`.actuation.ExplorationMap
import com.aldebaran.qi.sdk.`object`.actuation.MapTopGraphicalRepresentation
import com.aldebaran.qi.sdk.`object`.autonomousabilities.DegreeOfFreedom
import com.aldebaran.qi.sdk.`object`.holder.Holder
import io.github.anonymous.pepper_realtime.controller.GestureController
import io.github.anonymous.pepper_realtime.controller.MovementController
import io.github.anonymous.pepper_realtime.data.NavigationMapCache
import io.github.anonymous.pepper_realtime.service.PerceptionService

/**
 * NavigationServiceManager coordinates robot services during navigation phases.
 * It uses MovementController for actual movement execution while managing
 * service states (perception, touch sensors, gestures, autonomous abilities).
 */
class NavigationServiceManager(private val movementController: MovementController?) {

    interface NavigationServiceListener {
        fun onNavigationPhaseChanged(phase: NavigationPhase)
        fun onNavigationStatusUpdate(mapStatus: String?, localizationStatus: String?)
    }

    enum class NavigationPhase {
        NORMAL_OPERATION,
        LOCALIZATION_MODE,
        NAVIGATION_MODE
    }

    /**
     * Result object for movement/navigation/turn operations
     */
    data class MovementResult(val success: Boolean, val error: String?)

    companion object {
        private const val TAG = "NavigationServiceManager"
    }

    private var listener: NavigationServiceListener? = null
    private var currentPhase = NavigationPhase.NORMAL_OPERATION

    // Dependencies
    private var perceptionService: PerceptionService? = null
    private var touchSensorManager: TouchSensorManager? = null
    private var gestureController: GestureController? = null

    // Autonomous abilities management
    private var autonomousAbilitiesHolder: Holder? = null
    @Volatile private var autonomousAbilitiesHeld = false
    @Volatile private var gesturesSuppressed = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mapCache: NavigationMapCache
    private val localizationCoordinator: LocalizationCoordinator

    @Volatile private var pendingMovementPromise: Promise<MovementResult>? = null

    init {
        movementController?.setListener(object : MovementController.MovementListener {
            override fun onMovementStarted() {
                Log.i(TAG, "Navigation movement started")
                setNavigationPhase(NavigationPhase.NAVIGATION_MODE)
            }

            override fun onMovementFinished(success: Boolean, error: String?) {
                Log.i(TAG, "Navigation movement finished - success: $success, error: $error")
                // Always return to normal operation after movement
                setNavigationPhase(NavigationPhase.NORMAL_OPERATION)
                // Resolve any pending promise
                val p = pendingMovementPromise
                pendingMovementPromise = null
                if (p != null) {
                    try {
                        p.setValue(MovementResult(success, error))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to resolve movement promise", e)
                    }
                }
            }
        })

        mapCache = NavigationMapCache(mainHandler, object : NavigationMapCache.CriticalSectionListener {
            override fun onEnter(qiContext: QiContext) {
                beginCriticalMapLoad(qiContext)
            }

            override fun onExit(success: Boolean) {
                endCriticalMapLoad(success)
            }
        })

        localizationCoordinator = LocalizationCoordinator(
            mainHandler,
            { phase -> setNavigationPhase(phase) },
            { mapStatus, localizationStatus -> notifyStatusUpdate(mapStatus, localizationStatus) }
        )
    }

    private fun beginCriticalMapLoad(qiContext: QiContext) {
        try {
            gesturesSuppressed = true
            gestureController?.stopNow()
            holdAutonomousAbilities(qiContext)
            perceptionService?.let {
                if (it.isInitialized) {
                    it.stopMonitoring()
                }
            }
            touchSensorManager?.pause()
            listener?.onNavigationStatusUpdate("🗺️ Map: Loading...", "🧭 Localization: Waiting")
        } catch (e: Exception) {
            Log.w(TAG, "Error entering critical map load section", e)
        }
    }

    private fun endCriticalMapLoad(success: Boolean) {
        try {
            gesturesSuppressed = false
            releaseAutonomousAbilities()
            perceptionService?.let {
                if (it.isInitialized) {
                    it.startMonitoring()
                }
            }
            touchSensorManager?.resume()
            listener?.onNavigationStatusUpdate(
                if (success) "🗺️ Map: Ready" else "🗺️ Map: Failed",
                if (success) "🧭 Localization: Not running" else "🧭 Localization: Waiting"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error leaving critical map load section", e)
        }
    }

    /**
     * Returns true while a map build/load is currently in progress.
     */
    @Suppress("unused")
    fun isMapLoadingInProgress(): Boolean = mapCache.isLoading()

    fun setListener(listener: NavigationServiceListener?) {
        this.listener = listener
    }

    private fun notifyStatusUpdate(mapStatus: String?, localizationStatus: String?) {
        listener?.onNavigationStatusUpdate(mapStatus, localizationStatus)
    }

    fun setDependencies(
        perceptionService: PerceptionService?,
        touchSensorManager: TouchSensorManager?,
        gestureController: GestureController?
    ) {
        this.perceptionService = perceptionService
        this.touchSensorManager = touchSensorManager
        this.gestureController = gestureController
    }

    /**
     * Handle service state changes for proper service management during navigation
     */
    fun handleServiceStateChange(mode: String) {
        Log.i(TAG, "Service state change received: $mode")
        try {
            when (mode) {
                "enterLocalizationMode" -> setNavigationPhase(NavigationPhase.LOCALIZATION_MODE)
                "mappingLocalized" -> {
                    listener?.onNavigationStatusUpdate("🗺️ Map: Building (Ready for guidance)", "🧭 Localization: Localized")
                    Log.i(TAG, "Mapping localized - robot ready for guidance")
                }
                "enterNavigationMode" -> setNavigationPhase(NavigationPhase.NAVIGATION_MODE)
                "resumeNormalOperation" -> setNavigationPhase(NavigationPhase.NORMAL_OPERATION)
                else -> Log.w(TAG, "Unknown service state change mode: $mode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling service state change: $mode", e)
        }
    }

    /**
     * Set navigation phase and coordinate all services accordingly
     */
    private fun setNavigationPhase(phase: NavigationPhase) {
        if (currentPhase == phase) {
            Log.d(TAG, "Already in phase: $phase")
            return
        }

        val previousPhase = currentPhase
        currentPhase = phase

        Log.i(TAG, "Navigation phase change: $previousPhase -> $phase")

        when (phase) {
            NavigationPhase.LOCALIZATION_MODE -> enterLocalizationMode()
            NavigationPhase.NAVIGATION_MODE -> enterNavigationMode()
            NavigationPhase.NORMAL_OPERATION -> resumeNormalOperation()
        }

        listener?.onNavigationPhaseChanged(phase)
    }

    /**
     * Phase 2: Localization Mode - Pause ALL services for stable localization
     */
    private fun enterLocalizationMode() {
        Log.i(TAG, "Navigation Phase 2: Entering Localization Mode - ALL services paused")

        // CRITICAL: Suppress gestures completely during localization
        gesturesSuppressed = true
        gestureController?.stopNow()
        Log.i(TAG, "Gestures suppressed and stopped for localization (prevents interference)")

        // CRITICAL: Hold autonomous abilities
        holdAutonomousAbilities()

        perceptionService?.let {
            if (it.isInitialized) {
                it.stopMonitoring()
                Log.i(TAG, "Perception monitoring stopped for localization")
            }
        }
        touchSensorManager?.let {
            it.pause()
            Log.i(TAG, "Touch sensors paused for localization")
        }

        listener?.onNavigationStatusUpdate("🗺️ Map: Ready", "🧭 Localization: Localizing...")
        localizationCoordinator.markLocalizationInProgress()
    }

    /**
     * Phase 3: Navigation Mode - Keep all services paused during movement
     */
    private fun enterNavigationMode() {
        Log.i(TAG, "Navigation Phase 3: Entering Navigation Mode - ALL services paused for movement")

        gesturesSuppressed = true
        gestureController?.stopNow()
        Log.i(TAG, "Gestures suppressed and stopped for navigation")

        holdAutonomousAbilities()

        perceptionService?.let {
            if (it.isInitialized) {
                it.stopMonitoring()
                Log.i(TAG, "Perception monitoring stopped for navigation")
            }
        }
        touchSensorManager?.let {
            it.pause()
            Log.i(TAG, "Touch sensors paused for navigation")
        }

        listener?.onNavigationStatusUpdate("🗺️ Map: Ready", "🧭 Localization: Navigating...")
    }

    /**
     * Phase 4: Resume Normal Operation - Restore all services
     */
    private fun resumeNormalOperation() {
        Log.i(TAG, "Navigation Phase 4: Resuming Normal Operation - restoring all services")

        gesturesSuppressed = false
        Log.i(TAG, "Gesture suppression lifted - gestures allowed again")

        releaseAutonomousAbilities()

        perceptionService?.let {
            if (it.isInitialized) {
                it.startMonitoring()
                Log.i(TAG, "Perception monitoring resumed")
            }
        }

        touchSensorManager?.let {
            it.resume()
            Log.i(TAG, "Touch sensors resumed")
        }

        listener?.onNavigationStatusUpdate(
            "🗺️ Map: Ready",
            if (localizationCoordinator.isLocalizationReady) "🧭 Localization: Localized" else "🧭 Localization: Unknown"
        )

        Log.i(TAG, "All services restored to normal operation")
    }

    /**
     * Check whether localization is currently ready.
     */
    fun isLocalizationReady(): Boolean = localizationCoordinator.isLocalizationReady

    /**
     * Check whether a map is currently loaded into memory
     */
    @Suppress("BooleanMethodIsAlwaysInverted")
    fun isMapLoaded(): Boolean = mapCache.isMapLoaded()

    /**
     * Utility: Check if a saved map file exists on disk.
     */
    fun isMapSavedOnDisk(appContext: Context): Boolean = mapCache.isMapSavedOnDisk(appContext)

    /**
     * Load and cache the ExplorationMap if not already loaded.
     */
    fun ensureMapLoadedIfNeeded(qiContext: QiContext?, appContext: Context?, onMapLoaded: Runnable?): Future<Boolean> {
        return mapCache.ensureMapLoadedIfNeeded(qiContext, appContext, onMapLoaded) { stopCurrentLocalization() }
    }

    /**
     * Ensure robot is localized (requires a loaded map). Non-blocking.
     */
    @Suppress("UnusedReturnValue")
    fun ensureLocalizationIfNeeded(qiContext: QiContext?, onLocalized: Runnable?, onFailed: Runnable?): Future<Boolean> {
        val mapForLocalization = mapCache.getCachedMap()
        if (qiContext == null || mapForLocalization == null) {
            Log.w(TAG, "Cannot ensure localization: qiContext or cached map is null.")
            val failed = Promise<Boolean>()
            failed.setValue(false)
            return failed.future
        }
        return localizationCoordinator.ensureLocalizationIfNeeded(qiContext, mapForLocalization, onLocalized, onFailed)
    }

    /**
     * Stops the currently running Localize action, if any.
     */
    fun stopCurrentLocalization(): Future<Void> = localizationCoordinator.stopCurrentLocalization()

    /**
     * Get the cached graphical map bitmap.
     */
    fun getMapBitmap(): Bitmap? = mapCache.getMapBitmap()

    /**
     * Get the cached map's graphical representation for coordinate conversion.
     */
    fun getMapTopGraphicalRepresentation(): MapTopGraphicalRepresentation? = mapCache.getMapTopGraphicalRepresentation()

    /**
     * Get platform-independent map graph info.
     */
    fun getMapGraphInfo(): io.github.anonymous.pepper_realtime.data.MapGraphInfo? {
        val gfx = getMapTopGraphicalRepresentation() ?: return null
        return io.github.anonymous.pepper_realtime.data.MapGraphInfo(
            x = gfx.x.toFloat(),
            y = gfx.y.toFloat(),
            theta = gfx.theta.toFloat(),
            scale = gfx.scale.toFloat()
        )
    }

    /**
     * Directly caches a new ExplorationMap and its graphical representation.
     */
    fun cacheNewMap(newMap: ExplorationMap?, onCached: Runnable? = null) {
        mapCache.cacheNewMap(newMap, onCached)
    }

    /**
     * Hold autonomous abilities (flag only - actual hold requires qiContext)
     */
    private fun holdAutonomousAbilities() {
        autonomousAbilitiesHeld = true
        Log.i(TAG, "Autonomous abilities hold requested - will be applied when qiContext available")
    }

    /**
     * Actually hold autonomous abilities with qiContext
     */
    fun holdAutonomousAbilities(qiContext: QiContext?) {
        if (qiContext == null) {
            Log.w(TAG, "Cannot hold autonomous abilities - qiContext is null")
            return
        }

        if (autonomousAbilitiesHolder != null) {
            Log.d(TAG, "Autonomous abilities already held")
            return
        }

        try {
            autonomousAbilitiesHolder = HolderBuilder.with(qiContext)
                .withDegreesOfFreedom(DegreeOfFreedom.ROBOT_FRAME_ROTATION)
                .build()

            autonomousAbilitiesHolder?.async()?.hold()
            autonomousAbilitiesHeld = true
            Log.i(TAG, "Autonomous abilities held - robot will remain still during critical operations")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hold autonomous abilities", e)
        }
    }

    /**
     * Ensure autonomous abilities are actually held and return a Future to chain next steps.
     */
    fun ensureAutonomousAbilitiesHeld(qiContext: QiContext?): Future<Void> {
        val promise = Promise<Void>()
        try {
            if (autonomousAbilitiesHolder != null) {
                promise.setValue(null)
                return promise.future
            }
            if (!autonomousAbilitiesHeld) {
                autonomousAbilitiesHeld = true
            }
            if (qiContext == null) {
                Log.w(TAG, "QiContext is null while ensuring autonomous abilities hold")
                promise.setError("QiContext is null")
                return promise.future
            }
            autonomousAbilitiesHolder = HolderBuilder.with(qiContext)
                .withDegreesOfFreedom(DegreeOfFreedom.ROBOT_FRAME_ROTATION)
                .build()
            val fut = autonomousAbilitiesHolder?.async()?.hold()
            fut?.thenConsume { res ->
                if (res.hasError()) {
                    Log.e(TAG, "Holding autonomous abilities failed", res.error)
                    autonomousAbilitiesHolder = null
                    autonomousAbilitiesHeld = false
                    promise.setError("Hold failed")
                } else {
                    Log.i(TAG, "Autonomous abilities are now held (awaited)")
                    promise.setValue(null)
                }
            } ?: promise.setValue(null)
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring autonomous abilities hold", e)
            autonomousAbilitiesHolder = null
            autonomousAbilitiesHeld = false
            promise.setError("Exception while holding")
        }
        return promise.future
    }

    /**
     * Release autonomous abilities to restore normal robot behavior
     */
    private fun releaseAutonomousAbilities() {
        if (!autonomousAbilitiesHeld || autonomousAbilitiesHolder == null) {
            Log.d(TAG, "No autonomous abilities to release")
            autonomousAbilitiesHeld = false
            return
        }

        try {
            autonomousAbilitiesHolder?.async()?.release()
            autonomousAbilitiesHolder = null
            autonomousAbilitiesHeld = false
            Log.i(TAG, "Autonomous abilities released - robot behavior restored to normal")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release autonomous abilities", e)
        }
    }

    /**
     * Navigate to a location with full service coordination
     */
    fun navigateToLocation(qiContext: QiContext?, savedLocation: Any?, speed: Float): Future<MovementResult> {
        if (movementController == null) {
            Log.e(TAG, "Cannot navigate - MovementController is null")
            val failed = Promise<MovementResult>()
            failed.setValue(MovementResult(false, "Navigation service not available"))
            return failed.future
        }
        if (!localizationCoordinator.isLocalizationReady) {
            Log.w(TAG, "Cannot navigate - localization is not ready")
            val notReady = Promise<MovementResult>()
            notReady.setValue(MovementResult(false, "Localization not ready"))
            return notReady.future
        }
        if (pendingMovementPromise != null) {
            Log.w(TAG, "Navigation requested while another movement is in progress")
            val busy = Promise<MovementResult>()
            busy.setValue(MovementResult(false, "Another movement is already in progress"))
            return busy.future
        }
        Log.i(TAG, "Starting coordinated navigation to location")
        mainHandler.post { setNavigationPhase(NavigationPhase.NAVIGATION_MODE) }
        val p = Promise<MovementResult>()
        pendingMovementPromise = p
        
        val holdFuture = qiContext?.let { ensureAutonomousAbilitiesHeld(it) }
        if (holdFuture == null) {
            movementController.navigateToLocation(qiContext, savedLocation, speed)
        } else {
            holdFuture.thenConsume { res ->
                if (res.hasError()) {
                    Log.e(TAG, "Cannot start navigation - autonomous abilities hold failed")
                    mainHandler.post { setNavigationPhase(NavigationPhase.NORMAL_OPERATION) }
                    try {
                        p.setValue(MovementResult(false, "Failed to prepare robot for navigation"))
                    } catch (ignored: Exception) {
                    }
                } else {
                    movementController.navigateToLocation(qiContext, savedLocation, speed)
                }
            }
        }
        return p.future
    }

    /**
     * Move Pepper with service coordination
     */
    fun movePepper(qiContext: QiContext?, distanceForward: Double, distanceSideways: Double, speed: Double): Future<MovementResult> {
        if (movementController == null) {
            Log.e(TAG, "Cannot move - MovementController is null")
            val failed = Promise<MovementResult>()
            failed.setValue(MovementResult(false, "Navigation service not available"))
            return failed.future
        }
        if (pendingMovementPromise != null) {
            Log.w(TAG, "Move requested while another movement is in progress")
            val busy = Promise<MovementResult>()
            busy.setValue(MovementResult(false, "Another movement is already in progress"))
            return busy.future
        }
        Log.i(TAG, "Starting coordinated movement: forward=${distanceForward}m, sideways=${distanceSideways}m")
        mainHandler.post { setNavigationPhase(NavigationPhase.NAVIGATION_MODE) }
        val p = Promise<MovementResult>()
        pendingMovementPromise = p
        
        val holdFuture = qiContext?.let { ensureAutonomousAbilitiesHeld(it) }
        if (holdFuture == null) {
            movementController.movePepper(qiContext, distanceForward, distanceSideways, speed)
        } else {
            holdFuture.thenConsume { res ->
                if (res.hasError()) {
                    Log.e(TAG, "Cannot start movement - autonomous abilities hold failed")
                    mainHandler.post { setNavigationPhase(NavigationPhase.NORMAL_OPERATION) }
                    try {
                        p.setValue(MovementResult(false, "Failed to prepare robot for movement"))
                    } catch (ignored: Exception) {
                    }
                } else {
                    movementController.movePepper(qiContext, distanceForward, distanceSideways, speed)
                }
            }
        }
        return p.future
    }

    /**
     * Turn Pepper with service coordination
     */
    fun turnPepper(qiContext: QiContext?, direction: String, degrees: Double, speed: Double): Future<MovementResult> {
        if (movementController == null) {
            Log.e(TAG, "Cannot turn - MovementController is null")
            val failed = Promise<MovementResult>()
            failed.setValue(MovementResult(false, "Navigation service not available"))
            return failed.future
        }
        if (pendingMovementPromise != null) {
            Log.w(TAG, "Turn requested while another movement is in progress")
            val busy = Promise<MovementResult>()
            busy.setValue(MovementResult(false, "Another movement is already in progress"))
            return busy.future
        }
        Log.i(TAG, "Starting coordinated turn: $direction $degrees degrees")
        mainHandler.post { setNavigationPhase(NavigationPhase.NAVIGATION_MODE) }
        val p = Promise<MovementResult>()
        pendingMovementPromise = p
        
        val holdFuture = qiContext?.let { ensureAutonomousAbilitiesHeld(it) }
        if (holdFuture == null) {
            movementController.turnPepper(qiContext, direction, degrees, speed)
        } else {
            holdFuture.thenConsume { res ->
                if (res.hasError()) {
                    Log.e(TAG, "Cannot start turn - autonomous abilities hold failed")
                    mainHandler.post { setNavigationPhase(NavigationPhase.NORMAL_OPERATION) }
                    try {
                        p.setValue(MovementResult(false, "Failed to prepare robot for turn"))
                    } catch (ignored: Exception) {
                    }
                } else {
                    movementController.turnPepper(qiContext, direction, degrees, speed)
                }
            }
        }
        return p.future
    }

    /**
     * Check if gestures are currently suppressed during navigation
     */
    fun areGesturesSuppressed(): Boolean = gesturesSuppressed

    /**
     * Clean up resources
     */
    fun shutdown() {
        Log.i(TAG, "NavigationServiceManager shutdown initiated")

        releaseAutonomousAbilities()

        if (currentPhase != NavigationPhase.NORMAL_OPERATION) {
            setNavigationPhase(NavigationPhase.NORMAL_OPERATION)
        }

        mapCache.reset()
        localizationCoordinator.reset()

        listener = null
        perceptionService = null
        touchSensorManager = null
        gestureController = null

        Log.i(TAG, "NavigationServiceManager shutdown complete")
    }

    /**
     * Aggressively clear map-related caches and force GC to free memory before loading big maps.
     */
    fun resetMapCache() {
        Log.i(TAG, "Resetting map cache before loading new map")
        mapCache.reset()
    }
}


