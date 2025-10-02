package io.github.hrilab.pepper_realtime;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.aldebaran.qi.Future;
import com.aldebaran.qi.Promise;
import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.object.actuation.ExplorationMap;
import com.aldebaran.qi.sdk.object.actuation.MapTopGraphicalRepresentation;
import com.aldebaran.qi.sdk.object.holder.Holder;


/**
 * NavigationServiceManager coordinates robot services during navigation phases.
 * It uses MovementController for actual movement execution while managing
 * service states (perception, touch sensors, gestures, autonomous abilities).
 */
public class NavigationServiceManager {
    private static final String TAG = "NavigationServiceManager";
    
    public interface NavigationServiceListener {
        void onNavigationPhaseChanged(NavigationPhase phase);
        void onNavigationStatusUpdate(String mapStatus, String localizationStatus);
    }
    
    public enum NavigationPhase {
        NORMAL_OPERATION,
        LOCALIZATION_MODE,
        NAVIGATION_MODE
    }
    
    private NavigationServiceListener listener;
    private NavigationPhase currentPhase = NavigationPhase.NORMAL_OPERATION;
    
    // Dependencies
    private final MovementController movementController;
    private PerceptionService perceptionService;
    private TouchSensorManager touchSensorManager;
    private GestureController gestureController;
    
    // Autonomous abilities management
    private Holder autonomousAbilitiesHolder;
    private volatile boolean autonomousAbilitiesHeld = false;
    private volatile boolean gesturesSuppressed = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final NavigationMapCache mapCache;
    private final LocalizationCoordinator localizationCoordinator;
 
    private void beginCriticalMapLoad(QiContext qiContext) {
        try {
            gesturesSuppressed = true;
            if (gestureController != null) {
                gestureController.stopNow();
            }
            holdAutonomousAbilities(qiContext);
            if (perceptionService != null && perceptionService.isInitialized()) {
                perceptionService.stopMonitoring();
            }
            if (touchSensorManager != null) {
                touchSensorManager.pause();
            }
            if (listener != null) {
                listener.onNavigationStatusUpdate("🗺️ Map: Loading...", "🧭 Localization: Waiting");
            }
        } catch (Exception e) {
            Log.w(TAG, "Error entering critical map load section", e);
        }
    }
 
    private void endCriticalMapLoad(boolean success) {
        try {
            gesturesSuppressed = false;
            releaseAutonomousAbilities();
            if (perceptionService != null && perceptionService.isInitialized()) {
                perceptionService.startMonitoring();
            }
            if (touchSensorManager != null) {
                touchSensorManager.resume();
            }
            if (listener != null) {
                listener.onNavigationStatusUpdate(success ? "🗺️ Map: Ready" : "🗺️ Map: Failed", success ? "🧭 Localization: Not running" : "🧭 Localization: Waiting");
            }
        } catch (Exception e) {
            Log.w(TAG, "Error leaving critical map load section", e);
        }
    }
 
    /**
     * Returns true while a map build/load is currently in progress.
     */
    @SuppressWarnings("unused")
    public boolean isMapLoadingInProgress() {
        return mapCache.isLoading();
    }
    
    /**
     * Result object for movement/navigation/turn operations
     */
    public static class MovementResult {
        public final boolean success;
        public final String error;
        public MovementResult(boolean success, String error) {
            this.success = success;
            this.error = error;
        }
    }
    
    private volatile Promise<MovementResult> pendingMovementPromise;
    
    public NavigationServiceManager(MovementController movementController) {
        this.movementController = movementController;
        if (movementController != null) {
            movementController.setListener(new MovementController.MovementListener() {
                @Override
                public void onMovementStarted() {
                    Log.i(TAG, "Navigation movement started");
                    setNavigationPhase(NavigationPhase.NAVIGATION_MODE);
                }

                @Override
                public void onMovementFinished(boolean success, String error) {
                    Log.i(TAG, "Navigation movement finished - success: " + success + ", error: " + error);
                    // Always return to normal operation after movement
                    setNavigationPhase(NavigationPhase.NORMAL_OPERATION);
                    // Resolve any pending promise
                    Promise<MovementResult> p = pendingMovementPromise;
                    pendingMovementPromise = null;
                    if (p != null) {
                        try {
                            p.setValue(new MovementResult(success, error));
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to resolve movement promise", e);
                        }
                    }
                }
            });
        }

        this.mapCache = new NavigationMapCache(mainHandler, new NavigationMapCache.CriticalSectionListener() {
            @Override
            public void onEnter(QiContext qiContext) {
                beginCriticalMapLoad(qiContext);
            }

            @Override
            public void onExit(boolean success) {
                endCriticalMapLoad(success);
            }
        });

        this.localizationCoordinator = new LocalizationCoordinator(
                mainHandler,
                this::setNavigationPhase,
                this::notifyStatusUpdate
        );
    }
    
    public void setListener(NavigationServiceListener listener) {
        this.listener = listener;
    }

    private void notifyStatusUpdate(String mapStatus, String localizationStatus) {
        if (listener != null) {
            listener.onNavigationStatusUpdate(mapStatus, localizationStatus);
        }
    }

    public void setDependencies(PerceptionService perceptionService, TouchSensorManager touchSensorManager, GestureController gestureController) {
        this.perceptionService = perceptionService;
        this.touchSensorManager = touchSensorManager;
        this.gestureController = gestureController;
    }
    
    /**
     * Handle service state changes for proper service management during navigation
     * @param mode Service mode (e.g., "enterLocalizationMode", "resumeNormalOperation")
     */
    public void handleServiceStateChange(String mode) {
        Log.i(TAG, "Service state change received: " + mode);
        try {
            switch (mode) {
                case "enterLocalizationMode":
                    setNavigationPhase(NavigationPhase.LOCALIZATION_MODE);
                    break;
                case "mappingLocalized":
                    // Update status to show mapping is ready for guidance
                    if (listener != null) {
                        listener.onNavigationStatusUpdate("🗺️ Map: Building (Ready for guidance)", "🧭 Localization: Localized");
                    }
                    Log.i(TAG, "Mapping localized - robot ready for guidance");
                    break;
                case "enterNavigationMode":
                    setNavigationPhase(NavigationPhase.NAVIGATION_MODE);
                    break;
                case "resumeNormalOperation":
                    setNavigationPhase(NavigationPhase.NORMAL_OPERATION);
                    break;
                default:
                    Log.w(TAG, "Unknown service state change mode: " + mode);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling service state change: " + mode, e);
        }
    }
    
    /**
     * Set navigation phase and coordinate all services accordingly
     */
    private void setNavigationPhase(NavigationPhase phase) {
        if (currentPhase == phase) {
            Log.d(TAG, "Already in phase: " + phase);
            return;
        }
        
        NavigationPhase previousPhase = currentPhase;
        currentPhase = phase;
        
        Log.i(TAG, "Navigation phase change: " + previousPhase + " -> " + phase);
        
        switch (phase) {
            case LOCALIZATION_MODE:
                enterLocalizationMode();
                break;
            case NAVIGATION_MODE:
                enterNavigationMode();
                break;
            case NORMAL_OPERATION:
                resumeNormalOperation();
                break;
        }
        
        if (listener != null) {
            listener.onNavigationPhaseChanged(phase);
        }
    }
    
    /**
     * Phase 2: Localization Mode - Pause ALL services for stable localization
     */
    private void enterLocalizationMode() {
        Log.i(TAG, "Navigation Phase 2: Entering Localization Mode - ALL services paused");
        
        // CRITICAL: Suppress gestures completely during localization
        gesturesSuppressed = true;
        if (gestureController != null) {
            gestureController.stopNow();
        }
        Log.i(TAG, "Gestures suppressed and stopped for localization (prevents interference)");
        
        // CRITICAL: Hold autonomous abilities (BasicAwareness, BackgroundMovement) to prevent head movement
        holdAutonomousAbilities();
        
        if (perceptionService != null && perceptionService.isInitialized()) {
            perceptionService.stopMonitoring();
            Log.i(TAG, "Perception monitoring stopped for localization");
        }
        if (touchSensorManager != null) {
            touchSensorManager.pause();
            Log.i(TAG, "Touch sensors paused for localization");
        }
        
        if (listener != null) {
            listener.onNavigationStatusUpdate("🗺️ Map: Ready", "🧭 Localization: Localizing...");
        }
        localizationCoordinator.markLocalizationInProgress();
    }
    
    /**
     * Phase 3: Navigation Mode - Keep all services paused during movement
     */
    private void enterNavigationMode() {
        Log.i(TAG, "Navigation Phase 3: Entering Navigation Mode - ALL services paused for movement");

        // CRITICAL: Suppress gestures completely during movement
        gesturesSuppressed = true;
        if (gestureController != null) {
            gestureController.stopNow();
        }
        Log.i(TAG, "Gestures suppressed and stopped for navigation");

        // CRITICAL: Hold autonomous abilities to prevent interference
        holdAutonomousAbilities();

        if (perceptionService != null && perceptionService.isInitialized()) {
            perceptionService.stopMonitoring();
            Log.i(TAG, "Perception monitoring stopped for navigation");
        }
        if (touchSensorManager != null) {
            touchSensorManager.pause();
            Log.i(TAG, "Touch sensors paused for navigation");
        }
        
        if (listener != null) {
            listener.onNavigationStatusUpdate("🗺️ Map: Ready", "🧭 Localization: Navigating...");
        }
    }
    
    /**
     * Phase 4: Resume Normal Operation - Restore all services
     */
    private void resumeNormalOperation() {
        Log.i(TAG, "Navigation Phase 4: Resuming Normal Operation - restoring all services");
        
        // CRITICAL: Re-enable gestures
        gesturesSuppressed = false;
        Log.i(TAG, "Gesture suppression lifted - gestures allowed again");
        
        // CRITICAL: Release autonomous abilities (restore BasicAwareness, BackgroundMovement)
        releaseAutonomousAbilities();
        
        // Resume perception monitoring
        if (perceptionService != null && perceptionService.isInitialized()) {
            perceptionService.startMonitoring();
            Log.i(TAG, "Perception monitoring resumed");
        }
        
        // Resume touch sensors
        if (touchSensorManager != null) {
            touchSensorManager.resume();
            Log.i(TAG, "Touch sensors resumed");
        }
        
        // Update UI status based on the *current* localization state, do not assume it's ready
        if (listener != null) {
            if (localizationCoordinator.isLocalizationReady()) {
                listener.onNavigationStatusUpdate("🗺️ Map: Ready", "🧭 Localization: Localized");
            } else {
                listener.onNavigationStatusUpdate("🗺️ Map: Ready", "🧭 Localization: Unknown");
            }
        }
        // DO NOT assume localization is ready after a simple movement.
        
        Log.i(TAG, "All services restored to normal operation");
    }

    /**
     * Check whether localization is currently ready.
     */
    public boolean isLocalizationReady() {
        return localizationCoordinator.isLocalizationReady();
    }

    /**
     * Check whether a map is currently loaded into memory (Localize created/running)
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isMapLoaded() {
        return mapCache.isMapLoaded();
    }

    /**
     * Utility: Check if a saved map file exists on disk.
     */
    public boolean isMapSavedOnDisk(Context appContext) {
        return mapCache.isMapSavedOnDisk(appContext);
    }

    /**
     * Load and cache the ExplorationMap if not already loaded.
     */
    public Future<Boolean> ensureMapLoadedIfNeeded(QiContext qiContext, Context appContext, Runnable onMapLoaded) {
        return mapCache.ensureMapLoadedIfNeeded(qiContext, appContext, onMapLoaded, this::stopCurrentLocalization);
    }

    /**
     * Ensure robot is localized (requires a loaded map). Non-blocking.
     */
    @SuppressWarnings("UnusedReturnValue")
    public Future<Boolean> ensureLocalizationIfNeeded(QiContext qiContext, Runnable onLocalized, Runnable onFailed) {
        ExplorationMap mapForLocalization = mapCache.getCachedMap();
        if (qiContext == null || mapForLocalization == null) {
            Log.w(TAG, "Cannot ensure localization: qiContext or cached map is null.");
            Promise<Boolean> failed = new Promise<>();
            failed.setValue(false);
            return failed.getFuture();
        }
        return localizationCoordinator.ensureLocalizationIfNeeded(qiContext, mapForLocalization, onLocalized, onFailed);
    }

    /**
     * Stops the currently running Localize action, if any.
     * This is crucial before starting a new LocalizeAndMap action to avoid conflicts.
     * @return a Future that is set when the cancellation is complete.
     */
    public Future<Void> stopCurrentLocalization() {
        return localizationCoordinator.stopCurrentLocalization();
    }

    /**
     * Get the cached graphical map bitmap.
     */
    public Bitmap getMapBitmap() {
        return mapCache.getMapBitmap();
    }

    /**
     * Get the cached map's graphical representation for coordinate conversion.
     */
    public MapTopGraphicalRepresentation getMapTopGraphicalRepresentation() {
        return mapCache.getMapTopGraphicalRepresentation();
    }
    
    /**
     * Directly caches a new ExplorationMap and its graphical representation.
     * Useful after creating a new map to avoid reloading it from disk.
     * Uses thread separation to avoid blocking UI/QiSDK threads.
     * @param newMap The newly created ExplorationMap object.
     * @param onCached Optional callback executed after successful caching.
     */
    public void cacheNewMap(ExplorationMap newMap, Runnable onCached) {
        mapCache.cacheNewMap(newMap, onCached);
    }

    /**
     * Directly caches a new ExplorationMap and its graphical representation.
     * Useful after creating a new map to avoid reloading it from disk.
     * Uses thread separation to avoid blocking UI/QiSDK threads.
     * @param newMap The newly created ExplorationMap object.
     */
    @SuppressWarnings("unused")
    public void cacheNewMap(ExplorationMap newMap) {
        cacheNewMap(newMap, null);
    }

    /**
     * Hold autonomous abilities (BasicAwareness, BackgroundMovement) during critical operations
     * This prevents head tracking and background movements that interfere with localization/mapping
     */
    private void holdAutonomousAbilities() {
        // This will be called with qiContext when needed
        // For now, just set the flag - actual implementation will be done when qiContext is available
        autonomousAbilitiesHeld = true;
        Log.i(TAG, "Autonomous abilities hold requested - will be applied when qiContext available");
    }
    
    /**
     * Actually hold autonomous abilities with qiContext
     */
    public void holdAutonomousAbilities(QiContext qiContext) {
        if (qiContext == null) {
            Log.w(TAG, "Cannot hold autonomous abilities - qiContext is null");
            return;
        }
        
        if (autonomousAbilitiesHolder != null) {
            Log.d(TAG, "Autonomous abilities already held");
            return;
        }
        
        try {
            // Hold head and body movements to prevent interference during critical operations
            // Based on QiSDK documentation: https://qisdk.softbankrobotics.com/sdk/doc/pepper-sdk/ch4_api/abilities/reference/autonomous_abilities.html
            // Using degrees of freedom constraint to prevent head tracking and body movements
            autonomousAbilitiesHolder = com.aldebaran.qi.sdk.builder.HolderBuilder.with(qiContext)
                    .withDegreesOfFreedom(
                            com.aldebaran.qi.sdk.object.autonomousabilities.DegreeOfFreedom.ROBOT_FRAME_ROTATION
                    )
                    .build();
            
            autonomousAbilitiesHolder.async().hold();
            autonomousAbilitiesHeld = true;
            Log.i(TAG, "Autonomous abilities held (head and body movements constrained) - robot will remain still during critical operations");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to hold autonomous abilities", e);
        }
    }

    /**
     * Ensure autonomous abilities are actually held and return a Future to chain next steps.
     * If already held with a valid holder, returns an already-resolved Future.
     */
    public Future<Void> ensureAutonomousAbilitiesHeld(QiContext qiContext) {
        Promise<Void> promise = new Promise<>();
        try {
            if (autonomousAbilitiesHolder != null) {
                // Already held
                promise.setValue(null);
                return promise.getFuture();
            }
            if (!autonomousAbilitiesHeld) {
                // Flag not set yet, set it to be consistent with state machine
                autonomousAbilitiesHeld = true;
            }
            if (qiContext == null) {
                Log.w(TAG, "QiContext is null while ensuring autonomous abilities hold");
                promise.setError("QiContext is null");
                return promise.getFuture();
            }
            autonomousAbilitiesHolder = com.aldebaran.qi.sdk.builder.HolderBuilder.with(qiContext)
                    .withDegreesOfFreedom(
                            com.aldebaran.qi.sdk.object.autonomousabilities.DegreeOfFreedom.ROBOT_FRAME_ROTATION
                    )
                    .build();
            Future<Void> fut = autonomousAbilitiesHolder.async().hold();
            fut.thenConsume(res -> {
                if (res.hasError()) {
                    Log.e(TAG, "Holding autonomous abilities failed", res.getError());
                    autonomousAbilitiesHolder = null;
                    autonomousAbilitiesHeld = false;
                    promise.setError("Hold failed");
                } else {
                    Log.i(TAG, "Autonomous abilities are now held (awaited)");
                    promise.setValue(null);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error ensuring autonomous abilities hold", e);
            autonomousAbilitiesHolder = null;
            autonomousAbilitiesHeld = false;
            promise.setError("Exception while holding");
        }
        return promise.getFuture();
    }
    
    /**
     * Release autonomous abilities to restore normal robot behavior
     */
    private void releaseAutonomousAbilities() {
        if (!autonomousAbilitiesHeld || autonomousAbilitiesHolder == null) {
            Log.d(TAG, "No autonomous abilities to release");
            autonomousAbilitiesHeld = false;
            return;
        }
        
        try {
            autonomousAbilitiesHolder.async().release();
            autonomousAbilitiesHolder = null;
            autonomousAbilitiesHeld = false;
            Log.i(TAG, "Autonomous abilities released - robot behavior restored to normal");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to release autonomous abilities", e);
        }
    }
    
    /**
     * Navigate to a location with full service coordination
     */
    public Future<MovementResult> navigateToLocation(QiContext qiContext, Object savedLocation, float speed) {
        if (movementController == null) {
            Log.e(TAG, "Cannot navigate - MovementController is null");
            Promise<MovementResult> failed = new Promise<>();
            failed.setValue(new MovementResult(false, "Navigation service not available"));
            return failed.getFuture();
        }
        if (!localizationCoordinator.isLocalizationReady()) {
            Log.w(TAG, "Cannot navigate - localization is not ready");
            Promise<MovementResult> notReady = new Promise<>();
            notReady.setValue(new MovementResult(false, "Localization not ready"));
            return notReady.getFuture();
        }
        if (pendingMovementPromise != null) {
            Log.w(TAG, "Navigation requested while another movement is in progress");
            Promise<MovementResult> busy = new Promise<>();
            busy.setValue(new MovementResult(false, "Another movement is already in progress"));
            return busy.getFuture();
        }
        Log.i(TAG, "Starting coordinated navigation to location");
        mainHandler.post(() -> setNavigationPhase(NavigationPhase.NAVIGATION_MODE));
        Promise<MovementResult> p = new Promise<>();
        pendingMovementPromise = p;
        // STRICT ORDER: await autonomous abilities hold before starting movement
        Future<Void> holdFuture = (qiContext != null) ? ensureAutonomousAbilitiesHeld(qiContext) : null;
        if (holdFuture == null) {
            movementController.navigateToLocation(qiContext, savedLocation, speed);
        } else {
            holdFuture.thenConsume(res -> {
                if (res.hasError()) {
                    Log.e(TAG, "Cannot start navigation - autonomous abilities hold failed");
                    // Return to normal operation to avoid stuck state
                    mainHandler.post(() -> setNavigationPhase(NavigationPhase.NORMAL_OPERATION));
                    try {
                        p.setValue(new MovementResult(false, "Failed to prepare robot for navigation"));
                    } catch (Exception ignored) { }
                } else {
                    movementController.navigateToLocation(qiContext, savedLocation, speed);
                }
            });
        }
        return p.getFuture();
    }
    
    /**
     * Move Pepper with service coordination
     */
    public Future<MovementResult> movePepper(QiContext qiContext, double distanceForward, double distanceSideways, double speed) {
        if (movementController == null) {
            Log.e(TAG, "Cannot move - MovementController is null");
            Promise<MovementResult> failed = new Promise<>();
            failed.setValue(new MovementResult(false, "Navigation service not available"));
            return failed.getFuture();
        }
        if (pendingMovementPromise != null) {
            Log.w(TAG, "Move requested while another movement is in progress");
            Promise<MovementResult> busy = new Promise<>();
            busy.setValue(new MovementResult(false, "Another movement is already in progress"));
            return busy.getFuture();
        }
        Log.i(TAG, "Starting coordinated movement: forward=" + distanceForward + "m, sideways=" + distanceSideways + "m");
        mainHandler.post(() -> setNavigationPhase(NavigationPhase.NAVIGATION_MODE));
        Promise<MovementResult> p = new Promise<>();
        pendingMovementPromise = p;
        Future<Void> holdFuture = (qiContext != null) ? ensureAutonomousAbilitiesHeld(qiContext) : null;
        if (holdFuture == null) {
            movementController.movePepper(qiContext, distanceForward, distanceSideways, speed);
        } else {
            holdFuture.thenConsume(res -> {
                if (res.hasError()) {
                    Log.e(TAG, "Cannot start movement - autonomous abilities hold failed");
                    mainHandler.post(() -> setNavigationPhase(NavigationPhase.NORMAL_OPERATION));
                    try {
                        p.setValue(new MovementResult(false, "Failed to prepare robot for movement"));
                    } catch (Exception ignored) { }
                } else {
                    movementController.movePepper(qiContext, distanceForward, distanceSideways, speed);
                }
            });
        }
        return p.getFuture();
    }
    
    /**
     * Turn Pepper with service coordination
     */
    public Future<MovementResult> turnPepper(QiContext qiContext, String direction, double degrees, double speed) {
        if (movementController == null) {
            Log.e(TAG, "Cannot turn - MovementController is null");
            Promise<MovementResult> failed = new Promise<>();
            failed.setValue(new MovementResult(false, "Navigation service not available"));
            return failed.getFuture();
        }
        if (pendingMovementPromise != null) {
            Log.w(TAG, "Turn requested while another movement is in progress");
            Promise<MovementResult> busy = new Promise<>();
            busy.setValue(new MovementResult(false, "Another movement is already in progress"));
            return busy.getFuture();
        }
        Log.i(TAG, "Starting coordinated turn: " + direction + " " + degrees + " degrees");
        mainHandler.post(() -> setNavigationPhase(NavigationPhase.NAVIGATION_MODE));
        Promise<MovementResult> p = new Promise<>();
        pendingMovementPromise = p;
        Future<Void> holdFuture = (qiContext != null) ? ensureAutonomousAbilitiesHeld(qiContext) : null;
        if (holdFuture == null) {
            movementController.turnPepper(qiContext, direction, degrees, speed);
        } else {
            holdFuture.thenConsume(res -> {
                if (res.hasError()) {
                    Log.e(TAG, "Cannot start turn - autonomous abilities hold failed");
                    mainHandler.post(() -> setNavigationPhase(NavigationPhase.NORMAL_OPERATION));
                    try {
                        p.setValue(new MovementResult(false, "Failed to prepare robot for turn"));
                    } catch (Exception ignored) { }
                } else {
                    movementController.turnPepper(qiContext, direction, degrees, speed);
                }
            });
        }
        return p.getFuture();
    }
    
    /**
     * Check if gestures are currently suppressed during navigation
     */
    public boolean areGesturesSuppressed() {
        return gesturesSuppressed;
    }
    
    /**
     * Clean up resources
     */
    public void shutdown() {
        Log.i(TAG, "NavigationServiceManager shutdown initiated");

        // Release autonomous abilities if held
        releaseAutonomousAbilities();

        // Return to normal operation
        if (currentPhase != NavigationPhase.NORMAL_OPERATION) {
            setNavigationPhase(NavigationPhase.NORMAL_OPERATION);
        }

        // Clear map cache and other references
        mapCache.reset();
        localizationCoordinator.reset();

        listener = null;
        perceptionService = null;
        touchSensorManager = null;
        gestureController = null;

        Log.i(TAG, "NavigationServiceManager shutdown complete");
    }

    /**
     * Aggressively clear map-related caches and force GC to free memory before loading big maps.
     */
    public void resetMapCache() {
        Log.i(TAG, "Resetting map cache before loading new map");
        mapCache.reset();
    }
}

