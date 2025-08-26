package io.github.studerus.pepper_android_realtime;

import android.util.Log;

import com.aldebaran.qi.Future;
import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.object.actuation.Actuation;
import com.aldebaran.qi.sdk.object.human.Human;
import com.aldebaran.qi.sdk.object.humanawareness.HumanAwareness;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages perception data and services for the robot
 * Handles human detection and other perception capabilities
 */
public class PerceptionService {

    private static final String TAG = "PerceptionService";

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

    // Threading
    private ScheduledExecutorService scheduler;

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
            Log.i(TAG, "PerceptionService initialized: HumanAwareness and Actuation ready");
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
        final long pollIntervalMs = 500L;
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
            Future<List<Human>> future = humanAwareness.async().getHumansAround();
            future.andThenConsume(humans -> {
                try {
                    List<PerceptionData.HumanInfo> humanInfoList = new ArrayList<>();
                    if (humans != null) {
                        for (Human h : humans) {
                            humanInfoList.add(mapHuman(h));
                        }
                    }
                    if (listener != null) listener.onHumansDetected(humanInfoList);
                } catch (Exception mapEx) {
                    Log.e(TAG, "Mapping humans failed", mapEx);
                    if (listener != null) listener.onPerceptionError("Mapping failed: " + mapEx.getMessage());
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Polling humans failed", e);
            if (listener != null) listener.onPerceptionError("Polling failed: " + e.getMessage());
        }
    }

    /**
     * Map QiSDK Human to UI-friendly HumanInfo
     */
    private PerceptionData.HumanInfo mapHuman(Human human) {
        PerceptionData.HumanInfo info = new PerceptionData.HumanInfo();
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
            // Distance
            try {
                Object humanFrame = human.getHeadFrame();
                info.distanceMeters = computeDistanceMetersReflect(humanFrame, robotFrame);
            } catch (Exception distEx) {
                // keep default -1.0 on failure
            }

            // Compute basic emotion for dashboard
            info.basicEmotion = PerceptionData.HumanInfo.computeBasicEmotion(info.excitementState, info.pleasureState);
        } catch (Exception e) {
            Log.w(TAG, "mapHuman: partial data due to exception", e);
        }
        return info;
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
            // Find computeTransform(Frame) by name to avoid hard class dependency
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

            // Transform t = tt.getTransform()
            java.lang.reflect.Method getTransform = transformTime.getClass().getMethod("getTransform");
            Object transform = getTransform.invoke(transformTime);
            if (transform == null) return -1.0;

            // Vector3 v = t.getTranslation()
            java.lang.reflect.Method getTranslation = transform.getClass().getMethod("getTranslation");
            Object translation = getTranslation.invoke(transform);
            if (translation == null) return -1.0;

            // double x = v.getX(); double y = v.getY(); (null-safe)
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
}
