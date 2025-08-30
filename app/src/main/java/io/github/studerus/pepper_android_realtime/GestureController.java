package io.github.studerus.pepper_android_realtime;

import android.util.Log;

import com.aldebaran.qi.Future;
import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.builder.AnimateBuilder;
import com.aldebaran.qi.sdk.builder.AnimationBuilder;
import com.aldebaran.qi.sdk.object.actuation.Animate;
import com.aldebaran.qi.sdk.object.actuation.Animation;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GestureController {
    public interface BoolSupplier { boolean get(); }
    public interface IntSupplier { Integer get(); }

    private static final String TAG = "GestureController";
    private final ExecutorService gestureExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean running = false;
    private volatile Future<Void> currentRunFuture = null;

    public void start(QiContext qiContext, BoolSupplier keepRunning, IntSupplier nextResId) {
        if (running) {
            Log.d(TAG, "GestureController already running, skipping start");
            return;
        }
        running = true;
        Log.i(TAG, "GestureController starting with qiContext: " + (qiContext != null));
        gestureExecutor.submit(() -> {
            Random random = new Random();
            Log.i(TAG, "Gesture thread started, entering main loop");
            while (keepRunning.get()) {
                try {
                    Integer resId = nextResId.get();
                    if (resId == null) {
                        Log.d(TAG, "No animation resource provided, breaking loop");
                        break;
                    }
                    Log.d(TAG, "Building animation with resource ID: " + resId);
                    Future<Animation> animationFuture = AnimationBuilder.with(qiContext)
                            .withResources(resId)
                            .buildAsync();
                    Animation animation = animationFuture.get();
                    Log.d(TAG, "Animation built successfully, starting animate");
                    Animate animate = AnimateBuilder.with(qiContext)
                            .withAnimation(animation)
                            .build();
                    currentRunFuture = animate.async().run();
                    Log.d(TAG, "Animation started, waiting for completion");
                    currentRunFuture.get();
                    Log.d(TAG, "Animation completed successfully");
                } catch (Exception e) {
                    Log.w(TAG, "Gesture run failed", e);
                } finally {
                    currentRunFuture = null;
                }
                // Necessary delay between gestures for natural timing
                try { 
                    //noinspection BusyWait
                    Thread.sleep(250 + random.nextInt(500)); 
                } catch (InterruptedException e) { 
                    Thread.currentThread().interrupt(); 
                    break; 
                }
            }
            running = false;
        });
    }

    public void stopNow() {
        running = false;
        try {
            Future<Void> f = currentRunFuture;
            if (f != null && !f.isDone()) {
                try { f.cancel(true); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    public void shutdown() {
        stopNow();
        try { gestureExecutor.shutdownNow(); } catch (Exception ignored) {}
    }
}
