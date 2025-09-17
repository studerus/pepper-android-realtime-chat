package io.github.studerus.pepper_android_realtime;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.nio.ByteBuffer;

import com.aldebaran.qi.Future;
import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.object.actuation.Actuation;
import com.aldebaran.qi.sdk.object.human.Human;
import com.aldebaran.qi.sdk.object.humanawareness.HumanAwareness;
import com.aldebaran.qi.sdk.object.image.EncodedImage;
import com.aldebaran.qi.sdk.object.image.EncodedImageHandle;
import com.aldebaran.qi.sdk.object.image.TimestampedImageHandle;
import com.aldebaran.qi.sdk.builder.TakePictureBuilder;
import com.aldebaran.qi.sdk.object.camera.TakePicture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages perception data and services for the robot
 * Handles human detection, face recognition via Azure, and other perception capabilities.
 */
public class PerceptionService {

    private static final String TAG = "PerceptionService";
    private static final long AZURE_ANALYSIS_INTERVAL_MS = 10000; // Analyze every 10 seconds when humans present

    public interface PerceptionListener {
        void onHumansDetected(List<PerceptionData.HumanInfo> humans);
        void onPerceptionError(String error);
        void onServiceStatusChanged(boolean isActive);
    }

    private QiContext qiContext;
    private PerceptionListener listener;
    private boolean isMonitoring = false;

    // QiSDK services
    private HumanAwareness humanAwareness;
    private Actuation actuation;
    private Object robotFrame; // Use reflection-friendly type to avoid hard dependency on geometry classes
    private Future<TakePicture> takePictureAction;

    // External services
    private FaceRecognitionService faceRecognitionService;
    private final AtomicBoolean isAzureAnalysisRunning = new AtomicBoolean(false);
    private long lastAzureAnalysisTime = 0;
    private volatile long azureBackoffUntilMs = 0;
    private volatile int lastHumansCount = 0;
    private volatile boolean triggerAzureNow = false;

    // Threading
    private ScheduledExecutorService scheduler;
    private final Object humansCacheLock = new Object();
    private List<Human> humansCache = new ArrayList<>();
    private final Map<Integer, AzureAttrs> azureCacheById = new HashMap<>();
    
    private static class AzureAttrs {
        Double yaw, pitch, roll, blur;
        String glasses, quality, exposure;
        Boolean masked;
    }
    private long lastUiPushMs = 0L;
    private List<Integer> lastUiIds = new ArrayList<>();

    public PerceptionService() {
        Log.d(TAG, "PerceptionService created");
    }

    /**
     * Set the perception listener for callbacks
     */
    public void setListener(PerceptionListener listener) {
        this.listener = listener;
    }

    /**
     * Initialize the perception service with QiContext
     */
    public void initialize(QiContext qiContext) {
        this.qiContext = qiContext;
        try {
            this.humanAwareness = qiContext.getHumanAwareness();
            this.actuation = qiContext.getActuation();
            this.robotFrame = actuation.robotFrame();
            
            // Build the TakePicture action once for reuse
            this.takePictureAction = TakePictureBuilder.with(qiContext).buildAsync();

            // Initialize Azure Face Recognition Service
            this.faceRecognitionService = new FaceRecognitionService();

            // Event-driven trigger: react to humansAround changes
            try {
                this.humanAwareness.addOnHumansAroundChangedListener(humans -> {
                    try {
                        int count = (humans == null) ? 0 : humans.size();
                        if (count != lastHumansCount) {
                            lastHumansCount = count;
                            // trigger immediately when humans appear/disappear (only if there is at least one human)
                            triggerAzureNow = count > 0;
                        }
                        synchronized (humansCacheLock) {
                            humansCache = (humans == null) ? new ArrayList<>() : new ArrayList<>(humans);
                        }
                    } catch (Exception ignore) { }
                });
            } catch (Exception e) {
                Log.w(TAG, "Failed to attach OnHumansAroundChangedListener", e);
            }

            Log.i(TAG, "PerceptionService initialized: HumanAwareness, Actuation, and FaceRecognition ready");
            if (listener != null) listener.onServiceStatusChanged(true);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize perception services", e);
            if (listener != null) listener.onPerceptionError("Init failed: " + e.getMessage());
        }
    }

    /**
     * Start monitoring for perception data
     */
    public void startMonitoring() {
        if (!isInitialized() || humanAwareness == null) {
            Log.w(TAG, "Cannot start monitoring - service not initialized");
            if (listener != null) listener.onPerceptionError("Service not initialized");
            return;
        }

        if (isMonitoring) {
            Log.i(TAG, "Perception monitoring already active");
            return;
        }

        isMonitoring = true;
        Log.i(TAG, "Perception monitoring started");
        if (listener != null) listener.onServiceStatusChanged(true);

        // Schedule lightweight polling to gather human info without busy-waiting
        scheduler = Executors.newSingleThreadScheduledExecutor();
        final long pollIntervalMs = 1500L;
        scheduler.scheduleAtFixedRate(this::monitorOnce, 0L, pollIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Stop monitoring for perception data
     */
    public void stopMonitoring() {
        isMonitoring = false;
        Log.i(TAG, "Perception monitoring stopped");
        if (listener != null) listener.onServiceStatusChanged(false);
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /**
     * Monitoring loop: periodically polls HumanAwareness and emits structured data
     */
    private void monitorOnce() {
        if (!isMonitoring || humanAwareness == null) return;
        try {
            List<Human> humansSnapshot;
            synchronized (humansCacheLock) {
                humansSnapshot = new ArrayList<>(humansCache);
            }
            if (humansSnapshot.isEmpty()) {
                if (listener != null) listener.onHumansDetected(new ArrayList<>());
                return;
            }

            // Build base list
                    List<PerceptionData.HumanInfo> humanInfoList = new ArrayList<>();
            for (Human h : humansSnapshot) {
                PerceptionData.HumanInfo info = mapHuman(h);
                // Apply cached Azure attrs so UI does not flip back to N/A
                AzureAttrs cached = azureCacheById.get(info.id);
                if (cached != null) {
                    if (cached.yaw != null) info.azureYawDeg = cached.yaw;
                    if (cached.pitch != null) info.azurePitchDeg = cached.pitch;
                    if (cached.roll != null) info.azureRollDeg = cached.roll;
                    if (cached.glasses != null) info.glassesType = cached.glasses;
                    if (cached.masked != null) info.isMasked = cached.masked;
                    if (cached.quality != null) info.imageQuality = cached.quality;
                    if (cached.blur != null) info.blurLevel = cached.blur;
                    if (cached.exposure != null) info.exposureLevel = cached.exposure;
                }
                humanInfoList.add(info);
            }

            int currentCount = humansSnapshot.size();
            boolean timeWindowElapsed = (System.currentTimeMillis() - lastAzureAnalysisTime) >= AZURE_ANALYSIS_INTERVAL_MS;
            boolean shouldRunAzureAnalysis = faceRecognitionService.isConfigured() &&
                                             !isAzureAnalysisRunning.get() &&
                                             (triggerAzureNow || (timeWindowElapsed && currentCount > 0)) &&
                                             (System.currentTimeMillis() >= azureBackoffUntilMs);

            if (shouldRunAzureAnalysis) {
                isAzureAnalysisRunning.set(true);
                lastAzureAnalysisTime = System.currentTimeMillis();
                triggerAzureNow = false;
                // Decouple Qi action start from this scheduler thread
                final List<Human> finalHumansSnapshot = humansSnapshot;
                final List<PerceptionData.HumanInfo> finalHumanInfoList = humanInfoList;
                OptimizedThreadManager.getInstance().executeRealtime(() -> takePictureAndAnalyze(finalHumansSnapshot, finalHumanInfoList));
            } else {
                maybePushUi(humanInfoList);
            }
        } catch (Exception e) {
            Log.e(TAG, "Monitor tick failed", e);
            if (listener != null) listener.onPerceptionError("Monitor failed: " + e.getMessage());
        }
    }

    private void takePictureAndAnalyze(List<Human> pepperHumans, List<PerceptionData.HumanInfo> initialHumanInfo) {
        if (takePictureAction == null) {
            isAzureAnalysisRunning.set(false);
            return;
        }

        takePictureAction.andThenCompose(takePicture -> takePicture.async().run())
            .andThenConsume(timestampedImageHandle -> {
                Bitmap bitmap = convertToBitmap(timestampedImageHandle);
                if (bitmap == null) {
                    // Send initial data if picture fails
                    if (listener != null) listener.onHumansDetected(initialHumanInfo);
                    isAzureAnalysisRunning.set(false);
                    return;
                }

                faceRecognitionService.detectFacesAsync(bitmap, new FaceRecognitionService.FaceCallback() {
                    @Override
                    public void onResult(List<FaceRecognitionService.FaceInfo> azureFaces) {
                        List<PerceptionData.HumanInfo> fusedList = fuseHumanAndFaceData(pepperHumans, initialHumanInfo, azureFaces);
                        maybePushUi(fusedList);
                        isAzureAnalysisRunning.set(false);
                    }

                    @Override
                    public void onError(Exception e) {
                        if (e instanceof FaceRecognitionService.RateLimitException) {
                            azureBackoffUntilMs = System.currentTimeMillis() + ((FaceRecognitionService.RateLimitException) e).retryAfterMs;
                        }
                        Log.e(TAG, "Azure face detection failed", e);
                        // Keep showing last values: do not overwrite initialHumanInfo
                        maybePushUi(initialHumanInfo);
                        isAzureAnalysisRunning.set(false);
                    }
                });
            });
    }

    private List<PerceptionData.HumanInfo> fuseHumanAndFaceData(List<Human> pepperHumans, List<PerceptionData.HumanInfo> humanInfoList, List<FaceRecognitionService.FaceInfo> azureFaces) {
        if (azureFaces == null || azureFaces.isEmpty() || pepperHumans == null || pepperHumans.isEmpty()) {
            return humanInfoList; // Nothing to match, return original list
        }

        // --- Sort Azure faces by horizontal position (left to right) ---
        Collections.sort(azureFaces, (f1, f2) -> Integer.compare(f1.left, f2.left));

        // --- Sort Pepper humans by their angle relative to the robot's front (left to right) ---
        Collections.sort(pepperHumans, (h1, h2) -> {
            try {
                double[] xy1 = getXYTranslationReflect(h1.getHeadFrame(), this.robotFrame);
                double[] xy2 = getXYTranslationReflect(h2.getHeadFrame(), this.robotFrame);
                if (xy1 == null || xy2 == null) return 0;
                Double angle1 = Math.atan2(xy1[1], xy1[0]);
                Double angle2 = Math.atan2(xy2[1], xy2[0]);
                return angle2.compareTo(angle1); // Reverse sort for left-to-right
            } catch (Exception e) {
                return 0; // Cannot compare
            }
        });
        
        // Re-create the humanInfoList in the new sorted order
        List<PerceptionData.HumanInfo> sortedHumanInfoList = new ArrayList<>();
        for (Human sortedHuman : pepperHumans) {
            for (PerceptionData.HumanInfo info : humanInfoList) {
                if (info.id == sortedHuman.hashCode()) { // Using hashCode as a temporary pseudo-ID
                    sortedHumanInfoList.add(info);
                    break;
                }
            }
        }
        if (sortedHumanInfoList.size() != humanInfoList.size()){
             return humanInfoList; // sort failed
        }

        // --- Match sorted lists element by element ---
        int matchCount = Math.min(sortedHumanInfoList.size(), azureFaces.size());
        for (int i = 0; i < matchCount; i++) {
            PerceptionData.HumanInfo humanInfo = sortedHumanInfoList.get(i);
            FaceRecognitionService.FaceInfo azureFace = azureFaces.get(i);

            // --- Enrich HumanInfo with Azure Data (cache last good values) ---
            if (azureFace.yawDeg != null) humanInfo.azureYawDeg = azureFace.yawDeg;
            if (azureFace.pitchDeg != null) humanInfo.azurePitchDeg = azureFace.pitchDeg;
            if (azureFace.rollDeg != null) humanInfo.azureRollDeg = azureFace.rollDeg;
            if (azureFace.glassesType != null) humanInfo.glassesType = azureFace.glassesType;
            if (azureFace.isMasked != null) humanInfo.isMasked = azureFace.isMasked;
            if (azureFace.imageQuality != null) humanInfo.imageQuality = azureFace.imageQuality;
            if (azureFace.blurValue != null) humanInfo.blurLevel = azureFace.blurValue;
            if (azureFace.exposureLevel != null) humanInfo.exposureLevel = azureFace.exposureLevel;

            // Update cache
            AzureAttrs a = azureCacheById.get(humanInfo.id);
            if (a == null) a = new AzureAttrs();
            a.yaw = humanInfo.azureYawDeg;
            a.pitch = humanInfo.azurePitchDeg;
            a.roll = humanInfo.azureRollDeg;
            a.glasses = humanInfo.glassesType;
            a.masked = humanInfo.isMasked;
            a.quality = humanInfo.imageQuality;
            a.blur = humanInfo.blurLevel;
            a.exposure = humanInfo.exposureLevel;
            azureCacheById.put(humanInfo.id, a);
        }
        
        return sortedHumanInfoList;
    }

    private Bitmap convertToBitmap(TimestampedImageHandle timestampedImageHandle) {
        try {
            EncodedImageHandle encodedImageHandle = timestampedImageHandle.getImage();
            EncodedImage encodedImage = encodedImageHandle.getValue();
            ByteBuffer buffer = encodedImage.getData();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            Log.e(TAG, "Failed to convert EncodedImage to Bitmap", e);
            return null;
        }
    }

    /**
     * Map QiSDK Human to UI-friendly HumanInfo
     */
    private PerceptionData.HumanInfo mapHuman(Human human) {
        PerceptionData.HumanInfo info = new PerceptionData.HumanInfo();
        info.id = human.hashCode(); // Use hashCode as a temporary, unstable ID for now
        try {
            // Basic demographics & states
            try { info.estimatedAge = human.getEstimatedAge().getYears(); } catch (Exception ignored) {}
            if (human.getEstimatedGender() != null) {
                info.gender = String.valueOf(human.getEstimatedGender());
            }
            if (human.getEmotion() != null) {
                info.pleasureState = String.valueOf(human.getEmotion().getPleasure());
                info.excitementState = String.valueOf(human.getEmotion().getExcitement());
            }
            if (human.getEngagementIntention() != null) {
                info.engagementState = String.valueOf(human.getEngagementIntention());
            }
            if (human.getFacialExpressions() != null && human.getFacialExpressions().getSmile() != null) {
                info.smileState = String.valueOf(human.getFacialExpressions().getSmile());
            }
            if (human.getAttention() != null) {
                info.attentionState = String.valueOf(human.getAttention());
            }

            // Distance computation (robot frame vs human head frame)
            try {
                Object humanFrame = human.getHeadFrame();
                info.distanceMeters = computeDistanceMetersReflect(humanFrame, robotFrame);
            } catch (Exception distEx) {
                // keep default -1.0 on failure
            }

            // Extract face picture with improved error handling and thread safety
            info.facePicture = extractFacePictureSafely(human, info.id);

            // Compute basic emotion for dashboard
            info.basicEmotion = PerceptionData.HumanInfo.computeBasicEmotion(info.excitementState, info.pleasureState);
        } catch (Exception e) {
            Log.w(TAG, "mapHuman: partial data due to exception", e);
        }
        return info;
    }

    /**
     * Helper: get XY translation between headFrame and base frame using reflection.
     */
    private double[] getXYTranslationReflect(Object headFrame, Object baseFrame) {
        if (headFrame == null || baseFrame == null) return null;
        try {
            java.lang.reflect.Method computeTransform = null;
            for (java.lang.reflect.Method m : headFrame.getClass().getMethods()) {
                if ("computeTransform".equals(m.getName()) && m.getParameterTypes().length == 1) {
                    computeTransform = m; break;
                }
            }
            if (computeTransform == null) return null;
            Object transformTime = computeTransform.invoke(headFrame, baseFrame);
            if (transformTime == null) return null;
            Object transform = transformTime.getClass().getMethod("getTransform").invoke(transformTime);
            if (transform == null) return null;
            Object translation = transform.getClass().getMethod("getTranslation").invoke(transform);
            if (translation == null) return null;
            Object xObj = translation.getClass().getMethod("getX").invoke(translation);
            Object yObj = translation.getClass().getMethod("getY").invoke(translation);
            if (!(xObj instanceof Number) || !(yObj instanceof Number)) return null;
            return new double[] { ((Number) xObj).doubleValue(), ((Number) yObj).doubleValue() };
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Safely extract face picture from human with comprehensive error handling
     * Based on QiSDK tutorial but with improved null checks and exception handling
     */
    private Bitmap extractFacePictureSafely(Human human, int humanId) {
        if (human == null) {
            Log.d(TAG, "Human object is null for ID " + humanId);
            return null;
        }

        try {
            if (human.getFacePicture() == null) {
                Log.d(TAG, "Face picture object is null for human " + humanId);
                return null;
            }
            if (human.getFacePicture().getImage() == null) {
                Log.d(TAG, "Image object is null for human " + humanId);
                return null;
            }
            ByteBuffer facePictureBuffer;
            try {
                facePictureBuffer = human.getFacePicture().getImage().getData();
            } catch (Exception e) {
                Log.d(TAG, "Failed to get image data for human " + humanId + ": " + e.getMessage());
                return null;
            }
            
            if (facePictureBuffer == null) {
                Log.d(TAG, "Face picture buffer is null for human " + humanId);
                return null;
            }
            try {
                facePictureBuffer.rewind();
                int pictureBufferSize = facePictureBuffer.remaining();
                
                if (pictureBufferSize <= 0) {
                    Log.d(TAG, "Face picture buffer empty for human " + humanId + " (size: " + pictureBufferSize + ")");
                    return null;
                }
                
                if (pictureBufferSize > 5 * 1024 * 1024) {
                    Log.w(TAG, "Face picture buffer too large for human " + humanId + ": " + pictureBufferSize + " bytes");
                    return null;
                }

                byte[] facePictureArray = new byte[pictureBufferSize];
                facePictureBuffer.get(facePictureArray);
                
                Bitmap bitmap = BitmapFactory.decodeByteArray(facePictureArray, 0, pictureBufferSize);
                
                if (bitmap != null) {
                    Log.i(TAG, "✅ Face picture extracted for human " + humanId + 
                          " (" + bitmap.getWidth() + "x" + bitmap.getHeight() + ", " + pictureBufferSize + " bytes)");
                    return bitmap;
                } else {
                    Log.d(TAG, "Failed to decode face picture bitmap for human " + humanId + " (invalid image data)");
                    return null;
                }
                
            } catch (OutOfMemoryError oom) {
                Log.w(TAG, "Out of memory processing face picture for human " + humanId);
                return null;
            } catch (Exception bufferEx) {
                Log.w(TAG, "Buffer processing failed for human " + humanId + ": " + bufferEx.getMessage());
                return null;
            }
            
        } catch (Exception ex) {
            Log.w(TAG, "Face picture extraction failed for human " + humanId + ": " + ex.getClass().getSimpleName() + " - " + ex.getMessage());
            return null;
        }
    }

    /**
     * Compute planar distance using reflection to avoid compile-time dependency on geometry types.
     */
    private double computeDistanceMetersReflect(Object humanFrame, Object robotFrame) {
        if (humanFrame == null || robotFrame == null) {
            Log.w(TAG, "computeDistanceMetersReflect: frame null (human=" + (humanFrame!=null) + ", robot=" + (robotFrame!=null) + ")");
            return -1.0;
        }
        try {
            java.lang.reflect.Method computeTransform = null;
            for (java.lang.reflect.Method m : humanFrame.getClass().getMethods()) {
                if ("computeTransform".equals(m.getName()) && m.getParameterTypes().length == 1) {
                    computeTransform = m; break;
                }
            }
            if (computeTransform == null) {
                Log.w(TAG, "computeDistanceMetersReflect: computeTransform not found on " + humanFrame.getClass());
                return -1.0;
            }
            Object transformTime = computeTransform.invoke(humanFrame, robotFrame);
            if (transformTime == null) return -1.0;

            java.lang.reflect.Method getTransform = transformTime.getClass().getMethod("getTransform");
            Object transform = getTransform.invoke(transformTime);
            if (transform == null) return -1.0;

            java.lang.reflect.Method getTranslation = transform.getClass().getMethod("getTranslation");
            Object translation = getTranslation.invoke(transform);
            if (translation == null) return -1.0;

            java.lang.reflect.Method getX = translation.getClass().getMethod("getX");
            java.lang.reflect.Method getY = translation.getClass().getMethod("getY");
            Object xObj = getX.invoke(translation);
            Object yObj = getY.invoke(translation);
            if (!(xObj instanceof Number) || !(yObj instanceof Number)) return -1.0;
            double x = ((Number) xObj).doubleValue();
            double y = ((Number) yObj).doubleValue();
            return Math.sqrt(x * x + y * y);
        } catch (Exception e) {
            Log.w(TAG, "computeDistanceMetersReflect failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return -1.0;
        }
    }

    /**
     * Shutdown and cleanup the perception service
     */
    public void shutdown() {
        stopMonitoring();
        this.qiContext = null;
        this.listener = null;
        try {
            if (this.humanAwareness != null) {
                this.humanAwareness.removeAllOnHumansAroundChangedListeners();
            }
        } catch (Exception e) { Log.w(TAG, "Failed removing humansAround listeners", e); }
        this.humanAwareness = null;
        this.actuation = null;
        this.robotFrame = null;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        Log.i(TAG, "PerceptionService shutdown");
    }

    /**
     * Check if the service is initialized
     */
    public boolean isInitialized() {
        return qiContext != null;
    }

    private void maybePushUi(List<PerceptionData.HumanInfo> list) {
        try {
            long now = System.currentTimeMillis();
            List<Integer> ids = new ArrayList<>();
            for (PerceptionData.HumanInfo hi : list) ids.add(hi.id);
            boolean idsChanged = !ids.equals(lastUiIds);
            boolean timeOk = (now - lastUiPushMs) >= 1000L;
            if (idsChanged || timeOk) {
                if (listener != null) listener.onHumansDetected(list);
                lastUiPushMs = now;
                lastUiIds = ids;
            }
        } catch (Exception e) {
            if (listener != null) listener.onHumansDetected(list);
        }
    }
    
    /**
     * Get the recommended human to approach based on QiSDK HumanAwareness.
     * This is needed for the ApproachHuman tool integration.
     * 
     * @return Human object recommended for approach, or null if none suitable
     */
    public Human getRecommendedHumanToApproach() {
        if (!isInitialized() || humanAwareness == null) {
            Log.w(TAG, "Cannot get recommended human - service not initialized");
            return null;
        }
        
        try {
            return humanAwareness.getRecommendedHumanToApproach();
        } catch (Exception e) {
            Log.w(TAG, "Failed to get recommended human to approach", e);
            return null;
        }
    }
    
    /**
     * Find a specific Human object by its ID (hashCode).
     * Used by ApproachHuman tool to convert HumanInfo ID back to QiSDK Human object.
     * 
     * @param humanId The ID (hashCode) of the human to find
     * @return Human object with matching ID, or null if not found
     */
    public Human getHumanById(int humanId) {
        if (!isInitialized() || humanAwareness == null) {
            Log.w(TAG, "Cannot find human by ID - service not initialized");
            return null;
        }
        
        try {
            List<Human> detectedHumans = humanAwareness.getHumansAround();
            for (Human human : detectedHumans) {
                if (human.hashCode() == humanId) {
                    return human;
                }
            }
            Log.d(TAG, "Human with ID " + humanId + " not found in current detection");
            return null;
        } catch (Exception e) {
            Log.w(TAG, "Failed to find human by ID " + humanId, e);
            return null;
        }
    }
    
    /**
     * Get all currently detected humans as QiSDK Human objects.
     * Provides direct access to the raw Human objects for tools that need them.
     * 
     * @return List of detected Human objects, empty list if none or service not ready
     */
    public List<Human> getDetectedHumans() {
        if (!isInitialized() || humanAwareness == null) {
            Log.w(TAG, "Cannot get detected humans - service not initialized");
            return new ArrayList<>();
        }
        
        try {
            return new ArrayList<>(humanAwareness.getHumansAround());
        } catch (Exception e) {
            Log.w(TAG, "Failed to get detected humans", e);
            return new ArrayList<>();
        }
    }
}
