package io.github.studerus.pepper_android_realtime;

import android.util.Log;

import com.microsoft.cognitiveservices.speech.Connection;
import com.microsoft.cognitiveservices.speech.ResultReason;
import com.microsoft.cognitiveservices.speech.SpeechConfig;
import com.microsoft.cognitiveservices.speech.SpeechRecognizer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings("SpellCheckingInspection")
public class SpeechRecognizerManager {
    public interface Listener {
        void onRecognizing(String partialText);
        void onRecognized(String text);
        void onError(Exception ex);
    }

    private static final String TAG = "SpeechRecMgr";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SpeechRecognizer recognizer;
    private Listener listener;
    private final Object recognizerLock = new Object();

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void initialize(String key, String region, String language, int silenceTimeoutMs) {
        executor.submit(() -> {
            try {
                SpeechConfig cfg = SpeechConfig.fromSubscription(key, region);
                cfg.setSpeechRecognitionLanguage(language);
                cfg.setProperty("Speech_SegmentationSilenceTimeoutMs", String.valueOf(silenceTimeoutMs));

                if (recognizer != null) {
                    try { recognizer.stopContinuousRecognitionAsync().get(); } catch (Exception ignored) {}
                    try { recognizer.close(); } catch (Exception ignored) {}
                }
                recognizer = new SpeechRecognizer(cfg);
                synchronized (recognizerLock) {
                    recognizerLock.notifyAll();
                }
                
                // Give Azure Speech SDK time to fully initialize internally
                Thread.sleep(500);

                recognizer.recognizing.addEventListener((s, e) -> {
                    if (listener != null && e != null) {
                        try { listener.onRecognizing(e.getResult().getText()); } catch (Exception ignored) {}
                    }
                });
                recognizer.recognized.addEventListener((s, e) -> {
                    try {
                        if (e.getResult().getReason() == ResultReason.RecognizedSpeech) {
                            String text = e.getResult().getText();
                            if (listener != null && text != null && !text.isEmpty()) listener.onRecognized(text);
                        }
                    } catch (Exception ex) {
                        if (listener != null) listener.onError(ex);
                    }
                });
                Log.i(TAG, "Recognizer initialized for " + language + ", silence=" + silenceTimeoutMs + "ms");
            } catch (Exception ex) {
                Log.e(TAG, "Initialize failed", ex);
                if (listener != null) listener.onError(ex);
            }
        });
    }

    public void startContinuous() {
        executor.submit(() -> {
            try { if (recognizer != null) recognizer.startContinuousRecognitionAsync().get(); } catch (Exception ex) { if (listener != null) listener.onError(ex); }
        });
    }

    public void stopContinuous() {
        executor.submit(() -> {
            try { if (recognizer != null) recognizer.stopContinuousRecognitionAsync().get(); } catch (Exception ex) { if (listener != null) listener.onError(ex); }
        });
    }

    public void warmup() throws Exception {
        Log.i(TAG, "Starting warmup - waiting for recognizer initialization...");
        
        // Wait until recognizer is created by the initialize() async task without busy-waiting
        long deadline = System.currentTimeMillis() + 3000; // 3s (reduced from 5s)
        synchronized (recognizerLock) {
            while (recognizer == null) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                try {
                    recognizerLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Warmup interrupted while waiting for recognizer.", e);
                }
            }
        }
        
        if (recognizer == null) {
            Log.w(TAG, "Recognizer initialization timed out - this is expected on first launch");
            throw new RuntimeException("Recognizer not initialized - will retry on first use");
        }
        
        Log.i(TAG, "Recognizer is ready, starting connection warmup...");
        
        // Attempt warmup with retry for first-launch scenarios
        int retriesRemaining = 3; // Increased from 2 to 3 retries
        int attempt = 1;
        while (retriesRemaining > 0) {
            try {
                attemptWarmupConnection(attempt);
                Log.i(TAG, "Recognizer warmup completed successfully" + (attempt > 1 ? " on retry #" + attempt : ""));
                return; // Success!
            } catch (Exception e) {
                Log.w(TAG, "Warmup attempt #" + attempt + " failed: " + e.getMessage());
                
                if (retriesRemaining > 1) { // Retry if not final attempt
                    Log.i(TAG, "Retrying after delay... (" + retriesRemaining + " attempts remaining)");
                    try {
                        Thread.sleep(500); // Reduced delay from 1000ms to 500ms
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Warmup interrupted during retry delay.", ie);
                    }
                } else {
                    throw e; // Re-throw on final attempt
                }
            }
            retriesRemaining--;
            attempt++;
        }
        
        // This should never be reached, but for safety
        throw new RuntimeException("Warmup failed after retries");
    }
    
    private void attemptWarmupConnection(int attemptNumber) throws Exception {
        final Object lock = new Object();
        final Exception[] error = { null };

        Connection connection = Connection.fromRecognizer(recognizer);

        connection.connected.addEventListener((s, e) -> {
            Log.i(TAG, "Recognizer connection established (attempt #" + attemptNumber + ")");
            synchronized (lock) {
                lock.notify();
            }
        });

        connection.disconnected.addEventListener((s, e) -> {
            Log.e(TAG, "Recognizer connection failed - disconnected (attempt #" + attemptNumber + ")");
            error[0] = new RuntimeException("Recognizer disconnected during warmup");
            synchronized (lock) {
                lock.notify();
            }
        });

        Log.i(TAG, "Opening recognizer connection (attempt #" + attemptNumber + ")...");
        connection.openConnection(true);

        synchronized (lock) {
            try {
                lock.wait(4000); // 4 second timeout per attempt (reduced from 8s)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Warmup interrupted during connection", e);
            }
        }

        if (error[0] != null) {
            throw error[0];
        }
        
        // Verify recognizer is truly ready
        try {
            if (recognizer.getProperties() != null) {
                Log.i(TAG, "Recognizer verification passed");
            }
        } catch (Exception e) {
            Log.w(TAG, "Recognizer properties check failed", e);
            throw new RuntimeException("Recognizer not fully ready: " + e.getMessage(), e);
        }
    }
    
    private boolean isFirstLaunchError(Exception e) {
        String msg = e.getMessage();
        return msg != null && (
            msg.contains("0x22") || 
            msg.contains("SPXERR_INVALID_RECOGNIZER") ||
            msg.contains("invalid recognizer")
        );
    }

    public void shutdown() {
        try {
            if (recognizer != null) {
                try { recognizer.stopContinuousRecognitionAsync().get(); } catch (Exception ignored) {}
                try { recognizer.close(); } catch (Exception ignored) {}
                recognizer = null;
            }
        } catch (Exception ignored) {}
        executor.shutdown();
    }
}
