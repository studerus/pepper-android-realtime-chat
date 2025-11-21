package io.github.anonymous.pepper_realtime.controller;

import android.util.Log;

import com.aldebaran.qi.Future;
import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.builder.AnimateBuilder;
import com.aldebaran.qi.sdk.builder.AnimationBuilder;
import com.aldebaran.qi.sdk.object.actuation.Animate;
import com.aldebaran.qi.sdk.object.actuation.Animation;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GestureController {
    public interface BoolSupplier {
        boolean get();
    }

    public interface IntSupplier {
        Integer get();
    }

    private static final String TAG = "GestureController";
    private static final int BASE_DELAY_MS = 350;
    private static final int MAX_DELAY_MS = 2000; // Max backoff delay
    private ExecutorService gestureExecutor;
    private volatile boolean running = false;
    private volatile Future<Void> currentRunFuture = null;
    private int consecutiveFailures = 0;
    private static final int LOG_FAILURE_THRESHOLD = 3; // Only log after 3 consecutive failures

    public GestureController() {
        gestureExecutor = Executors.newSingleThreadExecutor();
    }

    public void start(Object robotContext, BoolSupplier keepRunning, IntSupplier nextResId) {
        if (robotContext == null) {
            Log.w(TAG, "GestureController: No robot context available.");
            return;
        }
        QiContext qiContext = (QiContext) robotContext;
        if (running) {
            Log.d(TAG, "GestureController already running, skipping start");
            return;
        }

        // Recreate executor if it was terminated (e.g., after shutdown)
        if (gestureExecutor == null || gestureExecutor.isTerminated() || gestureExecutor.isShutdown()) {
            Log.i(TAG, "Recreating gesture executor (was terminated/shutdown)");
            gestureExecutor = Executors.newSingleThreadExecutor();
        }

        // Reset failure counter when starting new gesture session (e.g., new speech
        // output)
        // This prevents accumulated backoff delays from affecting new speech sessions
        consecutiveFailures = 0;

        running = true;
        Log.i(TAG, "GestureController starting with qiContext: " + (qiContext != null));
        gestureExecutor.submit(() -> {
            Log.i(TAG, "Gesture thread started, entering main loop");
            runNextGestureNonBlocking(qiContext, keepRunning, nextResId);
        });
    }

    private void runNextGestureNonBlocking(QiContext qiContext, BoolSupplier keepRunning, IntSupplier nextResId) {
        if (!keepRunning.get()) {
            running = false;
            return;
        }
        Integer resId = null;
        try {
            resId = nextResId.get();
        } catch (Exception e) {
            Log.w(TAG, "Failed to get next animation resource id", e);
        }
        if (resId == null) {
            Log.d(TAG, "No animation resource provided, stopping gesture loop");
            running = false;
            return;
        }
        Log.d(TAG, "Building animation with resource ID: " + resId);
        AnimationBuilder.with(qiContext)
                .withResources(resId)
                .buildAsync()
                .thenConsume(animationFuture -> {
                    if (animationFuture.hasError()) {
                        Log.w(TAG, "Animation build failed", animationFuture.getError());
                        scheduleNextAfterDelay(qiContext, keepRunning, nextResId);
                        return;
                    }
                    Animation animation = animationFuture.getValue();
                    if (animation == null) {
                        Log.w(TAG, "Animation build returned null");
                        scheduleNextAfterDelay(qiContext, keepRunning, nextResId);
                        return;
                    }
                    Log.d(TAG, "Animation built successfully, starting animate");
                    AnimateBuilder.with(qiContext)
                            .withAnimation(animation)
                            .buildAsync()
                            .thenConsume(animateFuture -> {
                                if (animateFuture.hasError()) {
                                    Log.w(TAG, "Failed to build animate action", animateFuture.getError());
                                    scheduleNextAfterDelay(qiContext, keepRunning, nextResId);
                                    return;
                                }
                                Animate animate = animateFuture.getValue();
                                currentRunFuture = animate.async().run();
                                Log.d(TAG, "Animation started, awaiting completion");
                                currentRunFuture.thenConsume(runFuture -> {
                                    try {
                                        if (runFuture.isSuccess()) {
                                            consecutiveFailures = 0; // Reset counter on success
                                            Log.d(TAG, "Animation completed successfully");
                                        } else if (runFuture.isCancelled()) {
                                            consecutiveFailures = 0; // Reset on cancel (intentional)
                                            Log.d(TAG, "Animation cancelled");
                                        } else if (runFuture.hasError()) {
                                            consecutiveFailures++;
                                            // Only log after threshold to reduce noise
                                            if (consecutiveFailures >= LOG_FAILURE_THRESHOLD) {
                                                Log.w(TAG,
                                                        "Animation failed " + consecutiveFailures
                                                                + " times in a row (resources busy)",
                                                        runFuture.getError());
                                            } else {
                                                Log.d(TAG, "Animation failed (resources busy, attempt "
                                                        + consecutiveFailures + ")");
                                            }
                                        }
                                    } finally {
                                        currentRunFuture = null;
                                        // Wait AFTER animation completes, then schedule next
                                        scheduleNextAfterDelay(qiContext, keepRunning, nextResId);
                                    }
                                });
                            });
                });
    }

    private void scheduleNextAfterDelay(QiContext qiContext, BoolSupplier keepRunning, IntSupplier nextResId) {
        gestureExecutor.submit(() -> {
            try {
                // Exponential backoff: wait longer after repeated failures
                int delayMs = BASE_DELAY_MS;
                if (consecutiveFailures > 0) {
                    // 350ms, 700ms, 1400ms, 2000ms (capped)
                    delayMs = Math.min(MAX_DELAY_MS, BASE_DELAY_MS * (1 << consecutiveFailures));
                    Log.d(TAG, "Backing off " + delayMs + "ms after " + consecutiveFailures + " failures");
                }
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Check if we're still supposed to be running before starting next gesture
            if (running) {
                runNextGestureNonBlocking(qiContext, keepRunning, nextResId);
            } else {
                Log.d(TAG, "GestureController stopped, not scheduling next animation");
            }
        });
    }

    public void stopNow() {
        running = false;
        consecutiveFailures = 0; // Reset failure counter when stopping
        try {
            Future<Void> f = currentRunFuture;
            if (f != null && !f.isDone()) {
                try {
                    f.requestCancellation();
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Pause gesture controller (stop current gestures)
     * Called when app goes to background
     */
    public void pause() {
        Log.i(TAG, "GestureController paused");
        stopNow();
    }

    /**
     * Resume gesture controller
     * Called when app comes back from background
     * Note: Gestures will automatically restart when conditions are met
     */
    public void resume() {
        Log.d(TAG, "GestureController resumed (will restart when speaking)");
    }

    public void shutdown() {
        stopNow();
        try {
            gestureExecutor.shutdownNow();
        } catch (Exception ignored) {
        }
    }

    public Integer getRandomExplainAnimationResId() {
        int[] ids = new int[] {
                io.github.anonymous.pepper_realtime.R.raw.explain_01,
                io.github.anonymous.pepper_realtime.R.raw.explain_02,
                io.github.anonymous.pepper_realtime.R.raw.explain_03,
                io.github.anonymous.pepper_realtime.R.raw.explain_04,
                io.github.anonymous.pepper_realtime.R.raw.explain_05,
                io.github.anonymous.pepper_realtime.R.raw.explain_06,
                io.github.anonymous.pepper_realtime.R.raw.explain_07,
                io.github.anonymous.pepper_realtime.R.raw.explain_08,
                io.github.anonymous.pepper_realtime.R.raw.explain_09,
                io.github.anonymous.pepper_realtime.R.raw.explain_10,
                io.github.anonymous.pepper_realtime.R.raw.explain_11
        };
        return ids[new java.util.Random().nextInt(ids.length)];
    }
}
