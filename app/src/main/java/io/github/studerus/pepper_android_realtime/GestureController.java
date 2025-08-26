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
        if (running) return;
        running = true;
        gestureExecutor.submit(() -> {
            Random random = new Random();
            while (keepRunning.get()) {
                try {
                    Integer resId = nextResId.get();
                    if (resId == null) break;
                    Future<Animation> animationFuture = AnimationBuilder.with(qiContext)
                            .withResources(resId)
                            .buildAsync();
                    Animation animation = animationFuture.get();
                    Animate animate = AnimateBuilder.with(qiContext)
                            .withAnimation(animation)
                            .build();
                    currentRunFuture = animate.async().run();
                    currentRunFuture.get();
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
