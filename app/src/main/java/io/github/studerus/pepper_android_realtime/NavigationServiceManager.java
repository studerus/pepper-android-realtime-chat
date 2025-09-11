package io.github.studerus.pepper_android_realtime;

import android.content.Context;
import android.util.Log;

import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.Future;
import com.aldebaran.qi.Promise;
import com.aldebaran.qi.sdk.builder.ExplorationMapBuilder;
import com.aldebaran.qi.sdk.builder.LocalizeBuilder;
import com.aldebaran.qi.sdk.object.actuation.ExplorationMap;
import com.aldebaran.qi.sdk.object.actuation.Localize;
import com.aldebaran.qi.sdk.object.actuation.LocalizationStatus;
import com.aldebaran.qi.sdk.object.holder.Holder;
import com.aldebaran.qi.sdk.object.image.EncodedImage;
import com.aldebaran.qi.sdk.object.actuation.MapTopGraphicalRepresentation;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private volatile boolean localizationReady = false;
    private volatile Localize activeLocalizeAction; // Reference to the running Localize action
    private volatile Future<Void> activeLocalizeFuture; // Future from localize.async().run()
    private volatile boolean mapLoaded = false;
    private volatile ExplorationMap cachedMap;
    private volatile Bitmap cachedMapBitmap;
    private volatile MapTopGraphicalRepresentation cachedMapGfx;
    
    // Concurrency control for map building
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean isMapBeingBuilt = new AtomicBoolean(false);
    private volatile Promise<Boolean> inFlightMapLoadPromise;
 
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
        return isMapBeingBuilt.get();
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
    }
    
    public void setListener(NavigationServiceListener listener) {
        this.listener = listener;
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
        localizationReady = false;
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
            if (localizationReady) {
                listener.onNavigationStatusUpdate("🗺️ Map: Ready", "🧭 Localization: Localized");
            } else {
                listener.onNavigationStatusUpdate("🗺️ Map: Ready", "🧭 Localization: Unknown");
            }
        }
        // DO NOT assume localization is ready after a simple movement.
        // localizationReady = true; <-- THIS WAS THE BUG
        
        Log.i(TAG, "All services restored to normal operation");
    }

    /**
     * Check whether localization is currently ready.
     */
    public boolean isLocalizationReady() {
        return localizationReady;
    }

    /**
     * Check whether a map is currently loaded into memory (Localize created/running)
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isMapLoaded() {
        return mapLoaded;
    }

    /**
     * Utility: Check if a saved map file exists on disk.
     */
    public boolean isMapSavedOnDisk(Context appContext) {
        try {
            File mapsDir = new File(appContext.getFilesDir(), "maps");
            File mapFile = new File(mapsDir, "default_map.map");
            return mapFile.exists() && mapFile.length() > 0;
        } catch (Throwable t) {
            Log.w(TAG, "Failed checking map on disk", t);
            return false;
        }
    }

    /**
     * Load and cache the ExplorationMap if not already loaded. 
     * CRITICAL: Uses complete thread separation to avoid QiSDK MainEventLoop deadlocks.
     */
    public Future<Boolean> ensureMapLoadedIfNeeded(QiContext qiContext, Context appContext, Runnable onMapLoaded) {
        Promise<Boolean> promise = new Promise<>();
        if (mapLoaded && cachedMap != null) {
            promise.setValue(true);
            return promise.getFuture();
        }
        if (qiContext == null || appContext == null) {
            promise.setValue(false);
            return promise.getFuture();
        }

        // --- ROBUST MAP LOADING WITH DEDICATED THREAD ---
        // Prevent multiple concurrent builds of the large map object
        if (isMapBeingBuilt.compareAndSet(false, true)) {
            Log.i(TAG, "Starting robust map loading process...");
            inFlightMapLoadPromise = promise;
            // Enter critical section: pause interfering services and autonomous abilities
            beginCriticalMapLoad(qiContext);
            
            // Stop any running localization first to avoid SDK contention
            stopCurrentLocalization().thenConsume(ignored -> {
                // STEP 0: Proactive cache reset to avoid OOM on large maps
                resetMapCache();

                // STEP 1: Load file to string on I/O thread
                OptimizedThreadManager.getInstance().executeIO(() -> {
                    final String mapData = loadMapFileToString(appContext);
                    if (mapData == null) {
                        Log.e(TAG, "Map data could not be loaded from file.");
                        isMapBeingBuilt.set(false);
                        // Leave critical section (failure)
                        mainHandler.post(() -> endCriticalMapLoad(false));
                        Promise<Boolean> p = inFlightMapLoadPromise;
                        inFlightMapLoadPromise = null;
                        if (p != null) p.setValue(false);
                        return;
                    }

                    // STEP 2: Build ExplorationMap on a Qi-compatible background thread
                    OptimizedThreadManager.getInstance().executeNetwork(() -> {
                        try {
                            Log.i(TAG, "Building ExplorationMap on Qi-compatible background thread...");
                            final ExplorationMap map = ExplorationMapBuilder
                                    .with(qiContext)
                                    .withMapString(mapData)
                                    .build();
                            Log.i(TAG, "ExplorationMap built successfully.");

                            // STEP 3: Extract graphics on I/O thread (heavy work off main/Qi threads)
                            OptimizedThreadManager.getInstance().executeIO(() -> {
                                try {
                                    extractAndCacheMapGraphics(map);
                                    cachedMap = map;
                                    mapLoaded = true;
                                    // Notify callback and leave critical section on main thread
                                    mainHandler.post(() -> {
                                        try { if (onMapLoaded != null) onMapLoaded.run(); } catch (Throwable t) { Log.w(TAG, "onMapLoaded callback error", t); }
                                        endCriticalMapLoad(true);
                                    });
                                    Promise<Boolean> p = inFlightMapLoadPromise;
                                    inFlightMapLoadPromise = null;
                                    if (p != null) p.setValue(true);
                                } catch (Exception e) {
                                    Log.e(TAG, "Error caching map graphics after build", e);
                                    mainHandler.post(() -> endCriticalMapLoad(false));
                                    Promise<Boolean> p = inFlightMapLoadPromise;
                                    inFlightMapLoadPromise = null;
                                    if (p != null) p.setValue(false);
                                } finally {
                                    isMapBeingBuilt.set(false); // Release lock
                                }
                            });

                        } catch (Exception e) {
                            Log.e(TAG, "Failed to build ExplorationMap", e);
                            mainHandler.post(() -> endCriticalMapLoad(false));
                            Promise<Boolean> p = inFlightMapLoadPromise;
                            inFlightMapLoadPromise = null;
                            if (p != null) p.setValue(false);
                            isMapBeingBuilt.set(false); // Release lock on failure
                        }
                    });
                });
            });
        } else {
            Log.w(TAG, "Map is already being built. Waiting for completion.");
            // Return the in-flight future so callers can await completion
            Promise<Boolean> p = inFlightMapLoadPromise;
            if (p != null) {
                return p.getFuture();
            }
            // Fallback: no known in-flight promise; fail fast to avoid hanging callers
            Promise<Boolean> fail = new Promise<>();
            fail.setValue(false);
            return fail.getFuture();
        }
        
        return promise.getFuture();
    }

    /**
     * Load map file to string on I/O thread with memory optimization
     */
    private String loadMapFileToString(Context appContext) {
        try {
            File mapsDir = new File(appContext.getFilesDir(), "maps");
            File mapFile = new File(mapsDir, "default_map.map");
            if (!mapFile.exists() || mapFile.length() <= 0) {
                Log.w(TAG, "No map file on disk: " + mapFile.getAbsolutePath());
                return null;
            }
            
            long fileSize = mapFile.length();
            Log.i(TAG, "Loading map file: " + mapFile.getAbsolutePath() + " (" + fileSize + " bytes)");
            
            // Memory cleanup for large files
            if (fileSize > 5_000_000L) { // Trigger for maps larger than 5MB
                Log.i(TAG, "Large map file detected, performing pre-emptive memory cleanup");
                System.gc(); 
                try { Thread.sleep(100); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            
            byte[] mapBytes = new byte[(int) fileSize];
            int totalRead = 0;
            try (FileInputStream fis = new FileInputStream(mapFile)) {
                while (totalRead < mapBytes.length) {
                    int r = fis.read(mapBytes, totalRead, mapBytes.length - totalRead);
                    if (r == -1) break; 
                    totalRead += r;
                }
            }
            
            String mapData = new String(mapBytes, StandardCharsets.UTF_8);
            //noinspection UnusedAssignment
            mapBytes = null; // Release memory immediately
            System.gc();
            
            Log.i(TAG, "Map file loaded to string successfully");
            return mapData;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to load map file to string", e);
            return null;
        }
    }

    /**
     * Extract and cache map graphics (bitmap) - runs on I/O thread
     */
    private void extractAndCacheMapGraphics(ExplorationMap map) {
        try {
            // Clear previous cached data to free memory
            if (cachedMapBitmap != null && !cachedMapBitmap.isRecycled()) {
                cachedMapBitmap.recycle();
            }
            cachedMapBitmap = null;
            cachedMapGfx = null;
            
            // Force garbage collection before heavy bitmap operations
            System.gc();
            
            Log.i(TAG, "Extracting graphical representation from ExplorationMap");
            cachedMapGfx = map.getTopGraphicalRepresentation();
            EncodedImage encodedImage = cachedMapGfx.getImage();
            ByteBuffer buffer = encodedImage.getData();
            byte[] bitmapBytes = new byte[buffer.remaining()];
            buffer.get(bitmapBytes);
            
            // Decode bitmap with memory-efficient options
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565; // Use less memory than ARGB_8888
            cachedMapBitmap = BitmapFactory.decodeByteArray(bitmapBytes, 0, bitmapBytes.length, options);
            
            // Clear temporary byte array immediately
            //noinspection UnusedAssignment
            bitmapBytes = null;
            System.gc();
            
            if (cachedMapBitmap != null) {
                Log.i(TAG, "Successfully decoded and cached map bitmap (" + cachedMapBitmap.getWidth() + "x" + cachedMapBitmap.getHeight() + ")");
            } else {
                Log.e(TAG, "Bitmap decoding returned null");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract graphical representation from map", e);
            cachedMapBitmap = null;
            cachedMapGfx = null;
        }
    }

    /**
     * Ensure robot is localized (requires a loaded map). Non-blocking.
     */
    @SuppressWarnings("UnusedReturnValue")
    public Future<Boolean> ensureLocalizationIfNeeded(QiContext qiContext, Runnable onLocalized, Runnable onFailed) {
        Promise<Boolean> promise = new Promise<>();
        AtomicBoolean completed = new AtomicBoolean(false);

        Runnable completeSuccess = () -> {
            if (completed.compareAndSet(false, true)) {
                try { if (onLocalized != null) onLocalized.run(); } catch (Throwable t) { Log.w(TAG, "onLocalized callback error", t); }
                promise.setValue(true);
            }
        };
        Runnable completeFailure = () -> {
            if (completed.compareAndSet(false, true)) {
                try { if (onFailed != null) onFailed.run(); } catch (Throwable t) { Log.w(TAG, "onFailed callback error", t); }
                promise.setValue(false);
            }
        };
        if (localizationReady) {
            Log.i(TAG, "Localization is already confirmed. Triggering onLocalized callback directly.");
            completeSuccess.run();
            return promise.getFuture();
        }
        if (qiContext == null || cachedMap == null) {
            Log.w(TAG, "Cannot ensure localization: qiContext or cachedMap is null.");
            completeFailure.run();
            return promise.getFuture();
        }
        // Enter localization mode (pauses services and updates UI)
        setNavigationPhase(NavigationPhase.LOCALIZATION_MODE);
        try {
            Localize localize = LocalizeBuilder.with(qiContext).withMap(cachedMap).build();
            this.activeLocalizeAction = localize; // Store reference to the running action

            localize.addOnStatusChangedListener(status -> {
                if (status == LocalizationStatus.LOCALIZED) {
                    localizationReady = true;
                    if (listener != null) {
                        listener.onNavigationStatusUpdate("🗺️ Map: Ready", "🧭 Localization: Localized");
                    }
                    completeSuccess.run();
                }
            });
            Future<Void> fut = localize.async().run();
            this.activeLocalizeFuture = fut; // Store the Future for cancellation
            fut.thenConsume(res -> {
                if (res.hasError()) {
                    Log.e(TAG, "Localization failed", res.getError());
                    localizationReady = false;
                    activeLocalizeAction = null; // Clear reference on failure
                    activeLocalizeFuture = null; // Clear Future reference on failure
                    if (listener != null) {
                        listener.onNavigationStatusUpdate("🗺️ Map: Ready", "🧭 Localization: Failed");
                    }
                    // CRITICAL FIX: Always return to normal operation after localization fails
                    mainHandler.post(() -> setNavigationPhase(NavigationPhase.NORMAL_OPERATION));
                    completeFailure.run();
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start localization", t);
            // CRITICAL FIX: Always return to normal operation if localization fails to start
            mainHandler.post(() -> setNavigationPhase(NavigationPhase.NORMAL_OPERATION));
            completeFailure.run();
        }
        return promise.getFuture();
    }

    /**
     * Stops the currently running Localize action, if any.
     * This is crucial before starting a new LocalizeAndMap action to avoid conflicts.
     * @return a Future that is set when the cancellation is complete.
     */
    public Future<Void> stopCurrentLocalization() {
        if (activeLocalizeAction != null && activeLocalizeFuture != null) {
            Log.i(TAG, "Stopping existing Localize action to allow a new mapping process...");
            localizationReady = false;
            
            // Request cancellation on the Future returned by localize.async().run()
            activeLocalizeFuture.requestCancellation();
            
            // Clear references
            activeLocalizeAction = null;
            Future<Void> futureToReturn = activeLocalizeFuture;
            activeLocalizeFuture = null;

            if (listener != null) {
                listener.onNavigationStatusUpdate(null, "🧭 Localization: Stopped");
            }
            
            return futureToReturn;
        } else {
            Log.d(TAG, "No active Localize action to stop.");
            // Return an immediately-resolved Future to keep API consistent
            Promise<Void> p = new Promise<>();
            p.setValue(null);
            return p.getFuture();
        }
    }
    
    /**
     * Get the cached graphical map bitmap.
     */
    public Bitmap getMapBitmap() {
        return cachedMapBitmap;
    }

    /**
     * Get the cached map's graphical representation for coordinate conversion.
     */
    public MapTopGraphicalRepresentation getMapTopGraphicalRepresentation() {
        return cachedMapGfx;
    }
    
    /**
     * Directly caches a new ExplorationMap and its graphical representation.
     * Useful after creating a new map to avoid reloading it from disk.
     * Uses thread separation to avoid blocking UI/QiSDK threads.
     * @param newMap The newly created ExplorationMap object.
     * @param onCached Optional callback executed after successful caching.
     */
    public void cacheNewMap(ExplorationMap newMap, Runnable onCached) {
        if (newMap == null) {
            Log.w(TAG, "Attempted to cache a null map.");
            return;
        }
        
        // Process bitmap extraction on I/O thread to avoid blocking caller
        OptimizedThreadManager.getInstance().executeIO(() -> {
            try {
                extractAndCacheMapGraphics(newMap);
                
                // Atomic update of cached data
                cachedMap = newMap;
                mapLoaded = true;
                
                Log.i(TAG, "Successfully cached new map with thread separation");
                
                // Execute callback if provided
                if (onCached != null) {
                    try {
                        onCached.run();
                    } catch (Exception e) {
                        Log.e(TAG, "Callback after caching failed", e);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to cache new map", e);
            }
        });
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
        if (!localizationReady) {
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
        
        // Clean up memory-intensive cached data
        if (cachedMapBitmap != null && !cachedMapBitmap.isRecycled()) {
            cachedMapBitmap.recycle();
            cachedMapBitmap = null;
        }
        cachedMapGfx = null;
        cachedMap = null;
        mapLoaded = false;
        
        // Clear references
        listener = null;
        perceptionService = null;
        touchSensorManager = null;
        gestureController = null;
        
        // Force garbage collection after cleanup
        System.gc();
        
        Log.i(TAG, "NavigationServiceManager shutdown complete");
    }

    /**
     * Aggressively clear map-related caches and force GC to free memory before loading big maps.
     */
    public void resetMapCache() {
        try {
            Log.i(TAG, "Resetting map cache aggressively before loading new map");
            // Release bitmap memory
            if (cachedMapBitmap != null && !cachedMapBitmap.isRecycled()) {
                cachedMapBitmap.recycle();
            }
            cachedMapBitmap = null;
            cachedMapGfx = null;
            cachedMap = null;
            mapLoaded = false;
            // Hint GC to reclaim large arrays/objects asap
            System.gc();
            try { Thread.sleep(50); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            System.gc();
            Log.i(TAG, "Map cache reset completed");
        } catch (Exception e) {
            Log.w(TAG, "Error while resetting map cache", e);
        }
    }
}

