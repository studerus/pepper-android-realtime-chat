package ch.fhnw.pepper_realtime.manager

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import ch.fhnw.pepper_realtime.tools.OperationScope
import java.io.Closeable
import com.aldebaran.qi.Future
import com.aldebaran.qi.Promise
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.HolderBuilder
import com.aldebaran.qi.sdk.`object`.actuation.ExplorationMap
import com.aldebaran.qi.sdk.`object`.actuation.MapTopGraphicalRepresentation
import com.aldebaran.qi.sdk.`object`.autonomousabilities.DegreeOfFreedom
import com.aldebaran.qi.sdk.`object`.holder.Holder
import ch.fhnw.pepper_realtime.controller.GestureController
import ch.fhnw.pepper_realtime.controller.MovementController
import ch.fhnw.pepper_realtime.data.NavigationMapCache
import ch.fhnw.pepper_realtime.service.PerceptionService

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
    private var autonomousHoldFuture: Future<Void>? = null
    @Volatile private var autonomousAbilitiesHeld = false
    @Volatile private var gesturesSuppressed = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mapCache: NavigationMapCache
    private val localizationCoordinator: LocalizationCoordinator

    private class PendingMovement(val promise: Promise<MovementResult>) {
        val owner = OperationScope()
        var registration: Closeable? = null
    }
    private val movementLock = Any()
    private var pendingMovement: PendingMovement? = null
    private var movementGeneration = 0L
    
    // Lock to prevent parallel navigation process calls (includes map loading + localization + navigation)
    private var navigationProcessOwner: OperationScope? = null
    @Volatile private var _isNavigationProcessActive = false
    val isNavigationProcessActive: Boolean get() = _isNavigationProcessActive
    
    /**
     * Try to acquire the navigation process lock.
     * @return true if lock was acquired, false if another navigation is already in progress
     */
    @Synchronized
    fun tryStartNavigationProcess(owner: OperationScope? = null): Boolean {
        if (owner?.isActive == false) return false
        if (_isNavigationProcessActive) {
            Log.w(TAG, "Navigation process lock denied - another navigation is already in progress")
            return false
        }
        _isNavigationProcessActive = true
        navigationProcessOwner = owner
        owner?.onCancel { endNavigationProcess(owner) }
        Log.i(TAG, "Navigation process lock acquired")
        return true
    }
    
    /**
     * Release the navigation process lock.
     */
    @Synchronized
    fun endNavigationProcess(owner: OperationScope? = null) {
        if (owner != null && navigationProcessOwner !== owner) return
        navigationProcessOwner = null
        _isNavigationProcessActive = false
        Log.i(TAG, "Navigation process lock released")
    }

    init {
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
            // Note: Do NOT stop Head-Based Perception during navigation!
            // Stopping would reset all tracks and cause false PERSON_APPEARED events.
            // The head camera can run independently of navigation sensors.
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
            // Head-Based Perception was never stopped, so no need to restart
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

        // Note: Do NOT stop Head-Based Perception during localization!
        // It runs independently on the head and stopping would reset all tracks.
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

        // Note: Do NOT stop Head-Based Perception during navigation!
        // It runs independently on the head and stopping would reset all tracks.
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

        // Head-Based Perception was never stopped, so no need to restart

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
    fun ensureLocalizationIfNeeded(qiContext: QiContext?, onLocalized: Runnable?, onFailed: Runnable?, operation: OperationScope? = null): Future<Boolean> {
        val mapForLocalization = mapCache.getCachedMap()
        if (qiContext == null || mapForLocalization == null) {
            Log.w(TAG, "Cannot ensure localization: qiContext or cached map is null.")
            val failed = Promise<Boolean>()
            failed.setValue(false)
            return failed.future
        }
        return localizationCoordinator.ensureLocalizationIfNeeded(qiContext, mapForLocalization, onLocalized, onFailed, operation)
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
    fun getMapGraphInfo(): ch.fhnw.pepper_realtime.data.MapGraphInfo? {
        val gfx = getMapTopGraphicalRepresentation() ?: return null
        return ch.fhnw.pepper_realtime.data.MapGraphInfo(
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

            autonomousHoldFuture = autonomousAbilitiesHolder?.async()?.hold()
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
                autonomousHoldFuture?.let { return it }
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
            val holder = HolderBuilder.with(qiContext)
                .withDegreesOfFreedom(DegreeOfFreedom.ROBOT_FRAME_ROTATION)
                .build()
            autonomousAbilitiesHolder = holder
            val fut = holder.async().hold()
            autonomousHoldFuture = fut
            promise.setOnCancel { fut.requestCancellation() }
            fut.thenConsume { res ->
                if (autonomousAbilitiesHolder !== holder) { promise.setError("Preparation cancelled"); return@thenConsume }
                if (res.hasError() || res.isCancelled) {
                    Log.e(TAG, "Holding autonomous abilities failed", if (res.hasError()) res.error else null)
                    autonomousAbilitiesHolder = null
                    autonomousAbilitiesHeld = false
                    promise.setError("Hold failed")
                } else {
                    Log.i(TAG, "Autonomous abilities are now held (awaited)")
                    promise.setValue(null)
                }
            }
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
            val holder = autonomousAbilitiesHolder
            val holdFuture = autonomousHoldFuture
            autonomousHoldFuture = null
            autonomousAbilitiesHolder = null
            autonomousAbilitiesHeld = false
            try { if (holdFuture?.isDone == false) holdFuture.requestCancellation() } finally { holder?.async()?.release() }
            Log.i(TAG, "Autonomous abilities released - robot behavior restored to normal")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release autonomous abilities", e)
        }
    }

    fun navigateToLocation(qiContext: QiContext?, savedLocation: Any?, speed: Float,
                           operation: OperationScope? = null): Future<MovementResult> {
        if (!localizationCoordinator.isLocalizationReady) return failedMovement("Localization not ready")
        return coordinatedMovement(qiContext, operation) { owner, callbacks ->
            movementController!!.navigateToLocation(qiContext, savedLocation, speed, owner, callbacks)
        }
    }

    fun movePepper(qiContext: QiContext?, distanceForward: Double, distanceSideways: Double, speed: Double,
                   operation: OperationScope? = null): Future<MovementResult> =
        coordinatedMovement(qiContext, operation) { owner, callbacks ->
            movementController!!.movePepper(qiContext, distanceForward, distanceSideways, speed, owner, callbacks)
        }

    fun turnPepper(qiContext: QiContext?, direction: String, degrees: Double, speed: Double,
                   operation: OperationScope? = null): Future<MovementResult> =
        coordinatedMovement(qiContext, operation) { owner, callbacks ->
            movementController!!.turnPepper(qiContext, direction, degrees, speed, owner, callbacks)
        }

    private fun failedMovement(message: String): Future<MovementResult> = Promise<MovementResult>().apply {
        setValue(MovementResult(false, message))
    }.future

    private fun coordinatedMovement(qiContext: QiContext?, operation: OperationScope?,
                                    start: (OperationScope, MovementController.MovementListener) -> Unit): Future<MovementResult> {
        if (movementController == null) return failedMovement("Navigation service not available")
        if (operation?.isActive == false) return failedMovement("Movement cancelled")
        val pending = PendingMovement(Promise())
        synchronized(movementLock) {
            if (pendingMovement != null) return failedMovement("Another movement is already in progress")
            pendingMovement = pending
            movementGeneration++
        }
        pending.owner.onCancel {
            movementController.cancelMovement(pending.owner)
            finishMovement(pending, false, "Movement cancelled")
        }
        pending.promise.setOnCancel { pending.owner.cancel() }
        pending.registration = operation?.onCancel { pending.owner.cancel() }
        if (!pending.owner.isActive) { pending.registration?.close(); return pending.promise.future }
        postMovementPhase(pending, NavigationPhase.NAVIGATION_MODE)
        val callbacks = object : MovementController.MovementListener {
            override fun onMovementStarted() { postMovementPhase(pending, NavigationPhase.NAVIGATION_MODE) }
            override fun onMovementFinished(success: Boolean, error: String?) { finishMovement(pending, success, error) }
        }
        try {
            val (hold, cancelHold) = pending.owner.guard {
                val hold = ensureAutonomousAbilitiesHeld(qiContext)
                hold to pending.owner.onCancel { hold.requestCancellation() }
            }
            hold.thenConsume { result ->
                cancelHold.close()
                if (!pending.owner.isActive) return@thenConsume
                if (result.hasError() || result.isCancelled) {
                    finishMovement(pending, false, "Failed to prepare robot for movement")
                } else {
                    // The controller registers ownership before building/running the action.
                    start(pending.owner, callbacks)
                }
            }
        } catch (error: Exception) {
            finishMovement(pending, false, error.message ?: "Movement preparation failed")
        }
        return pending.promise.future
    }

    private fun postMovementPhase(pending: PendingMovement, phase: NavigationPhase) {
        mainHandler.post {
            synchronized(movementLock) {
                if (pendingMovement === pending && pending.owner.isActive) setNavigationPhase(phase)
            }
        }
    }

    private fun finishMovement(pending: PendingMovement, success: Boolean, error: String?) {
        val generation = synchronized(movementLock) {
            if (pendingMovement !== pending) return
            pendingMovement = null
            releaseAutonomousAbilities()
            ++movementGeneration
        }
        pending.registration?.close()
        pending.owner.cancel()
        mainHandler.post {
            synchronized(movementLock) {
                if (movementGeneration == generation) setNavigationPhase(NavigationPhase.NORMAL_OPERATION)
            }
        }
        pending.promise.setValue(MovementResult(success, error))
    }

    fun cancelAllActions() {
        val pending = synchronized(movementLock) { pendingMovement }
        val cleanups: List<() -> Unit> = listOf(
            { pending?.owner?.cancel(); Unit },
            { movementController?.cancelCurrentMovement(); Unit },
            { localizationCoordinator.reset() },
            { mapCache.cancelPendingWork() },
            { endNavigationProcess() },
            { releaseAutonomousAbilities() }
        )
        cleanups.forEach { cleanup ->
            try { cleanup() } catch (error: Exception) { Log.w(TAG, "Navigation cleanup failed", error) }
        }
        synchronized(movementLock) { movementGeneration++ }
        mainHandler.removeCallbacksAndMessages(null)
        setNavigationPhase(NavigationPhase.NORMAL_OPERATION)
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

        cancelAllActions()

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
}


