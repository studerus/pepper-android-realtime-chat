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
    public interface BoolSupplier { boolean get(); }
    public interface IntSupplier { Integer get(); }

    private static final String TAG = "GestureController";
    private static final int NEXT_DELAY_MS = 350;
    private ExecutorService gestureExecutor;
    private volatile boolean running = false;
    private volatile Future<Void> currentRunFuture = null;
    
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
                        Log.d(TAG, "Animation started, awaiting completion (non-blocking)");
                        currentRunFuture.thenConsume(runFuture -> {
                            try {
                                if (runFuture.isSuccess()) {
                                    Log.d(TAG, "Animation completed successfully");
                                } else if (runFuture.isCancelled()) {
                                    Log.w(TAG, "Animation cancelled");
                                } else if (runFuture.hasError()) {
                                    Log.w(TAG, "Animation failed", runFuture.getError());
                                }
                            } finally {
                                currentRunFuture = null;
                                scheduleNextAfterDelay(qiContext, keepRunning, nextResId);
                            }
                        });
                    });
            });
    }

    private void scheduleNextAfterDelay(QiContext qiContext, BoolSupplier keepRunning, IntSupplier nextResId) {
        gestureExecutor.submit(() -> {
            try {
                Thread.sleep(NEXT_DELAY_MS);
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
        try {
            Future<Void> f = currentRunFuture;
            if (f != null && !f.isDone()) {
                try { f.requestCancellation(); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
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
        try { gestureExecutor.shutdownNow(); } catch (Exception ignored) {}
    }
}
