package io.github.studerus.pepper_android_realtime;

import android.util.Log;

import com.microsoft.cognitiveservices.speech.Connection;
import com.microsoft.cognitiveservices.speech.ResultReason;
import com.microsoft.cognitiveservices.speech.SpeechConfig;
import com.microsoft.cognitiveservices.speech.SpeechRecognizer;
import com.microsoft.cognitiveservices.speech.PropertyId;
import com.microsoft.cognitiveservices.speech.CancellationReason;
import com.microsoft.cognitiveservices.speech.SessionEventArgs;
import com.microsoft.cognitiveservices.speech.SpeechRecognitionCanceledEventArgs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("SpellCheckingInspection")
public class SpeechRecognizerManager {
    
    // Activity-focused callback interface
    public interface ActivityCallbacks {
        void onRecognizedText(String text);
        void onPartialText(String partialText);
        void onError(String errorMessage);
        void onStarted();
        void onStopped();
    }
    
    // Internal listener interface for Azure SDK


    private static final String TAG = "SpeechRecMgr";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SpeechRecognizer recognizer;
    private ActivityCallbacks activityCallbacks;
    private final Object recognizerLock = new Object();
    private volatile boolean isRunning = false;
    
    // Configuration for current session
    private String currentApiKey;
    private String currentRegion;
    private String currentLanguage;
    private int currentSilenceTimeout;
    private double confidenceThreshold = 0.7; // Default 70%

    public void setCallbacks(ActivityCallbacks callbacks) {
        this.activityCallbacks = callbacks;
    }

    public void configure(String key, String region, String language, int silenceTimeoutMs, double confidenceThreshold) {
        // Store configuration for potential re-initialization
        this.currentApiKey = key;
        this.currentRegion = region;
        this.currentLanguage = language;
        this.currentSilenceTimeout = silenceTimeoutMs;
        this.confidenceThreshold = confidenceThreshold;
        
        initialize();
    }
    
    private void initialize() {
        executor.submit(() -> {
            try {
                SpeechConfig cfg = SpeechConfig.fromSubscription(currentApiKey, currentRegion);
                cfg.setSpeechRecognitionLanguage(currentLanguage);
                cfg.setProperty("Speech_SegmentationSilenceTimeoutMs", String.valueOf(currentSilenceTimeout));
                
                // Enable detailed results for confidence scores
                cfg.requestWordLevelTimestamps();
                cfg.setOutputFormat(com.microsoft.cognitiveservices.speech.OutputFormat.Detailed);

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
                    if (activityCallbacks != null && e != null) {
                        try { 
                            activityCallbacks.onPartialText(e.getResult().getText());
                        } catch (Exception ignored) {}
                    }
                });
                recognizer.recognized.addEventListener((s, e) -> {
                    try {
                        if (e.getResult().getReason() == ResultReason.RecognizedSpeech) {
                            String text = e.getResult().getText();
                            if (activityCallbacks != null && text != null && !text.isEmpty()) {
                                double confidence = extractConfidenceScore(e.getResult());
                                String finalText = addConfidenceWarningIfNeeded(text, confidence);
                                activityCallbacks.onRecognizedText(finalText);
                            }
                        }
                    } catch (Exception ex) {
                        if (activityCallbacks != null) activityCallbacks.onError(ex.getMessage());
                    }
                });
                Log.i(TAG, "Recognizer initialized for " + currentLanguage + ", silence=" + currentSilenceTimeout + "ms");
            } catch (Exception ex) {
                Log.e(TAG, "Initialize failed", ex);
                if (activityCallbacks != null) activityCallbacks.onError("Initialization failed: " + ex.getMessage());
            }
        });
    }

    public void start() {
        if (isRunning) {
            Log.i(TAG, "STT already running, skipping duplicate start call");
            return;
        }
        
        if (executor.isShutdown()) {
            Log.w(TAG, "Cannot start STT - executor already shutdown");
            return;
        }
        
        executor.submit(() -> {
            try {
                if (recognizer != null) {
                    recognizer.startContinuousRecognitionAsync().get();
                    isRunning = true;
                    if (activityCallbacks != null) activityCallbacks.onStarted();
                    Log.i(TAG, "Continuous recognition started");
                } else {
                    handleStartFailure(new IllegalStateException("Recognizer not initialized"));
                }
            } catch (Exception ex) {
                handleStartFailure(ex);
            }
        });
    }

    public void stop() {
        if (executor.isShutdown()) {
            Log.w(TAG, "Executor already shutdown, stopping STT directly (non-blocking)");
            try {
                if (recognizer != null) {
                    recognizer.stopContinuousRecognitionAsync(); // fire-and-forget
                    isRunning = false;
                    if (activityCallbacks != null) activityCallbacks.onStopped();
                    Log.i(TAG, "Continuous recognition stop requested");
                }
            } catch (Exception ex) {
                isRunning = false; // Reset flag even on error
                Log.e(TAG, "Error stopping speech recognizer (ignored)", ex);
            }
            return;
        }
        
        executor.submit(() -> {
            try {
                if (recognizer != null) {
                    recognizer.stopContinuousRecognitionAsync(); // do not block on get()
                    isRunning = false;
                    if (activityCallbacks != null) activityCallbacks.onStopped();
                    Log.i(TAG, "Continuous recognition stop requested (async)");
                }
            } catch (Exception ex) {
                isRunning = false; // Reset flag even on error
                Log.e(TAG, "Error stopping speech recognizer (ignored)", ex);
            }
        });
    }
    
    private void handleStartFailure(Exception ex) {
        if (isFirstLaunchSpeechError(ex)) {
            Log.i(TAG, "Detected first-launch speech error, attempting lazy re-initialization");
            try {
                initialize(); // Re-initialize
                Thread.sleep(1000);
                if (recognizer != null) {
                    recognizer.startContinuousRecognitionAsync().get();
                    isRunning = true;
                    if (activityCallbacks != null) activityCallbacks.onStarted();
                    Log.i(TAG, "Lazy speech initialization successful");
                    return;
                }
            } catch (Exception retryEx) {
                Log.e(TAG, "Retry initialization failed", retryEx);
            }
        }
        
        if (activityCallbacks != null) {
            activityCallbacks.onError("Speech recognition failed to start: " + ex.getMessage());
        }
    }
    
    /**
     * Add confidence warning to text if below threshold
     */
    private String addConfidenceWarningIfNeeded(String text, double confidence) {
        if (confidence < confidenceThreshold) {
            return text + " [Low confidence: " + Math.round(confidence * 100) + "%]";
        }
        return text;
    }
    
    /**
     * Check if error indicates first-launch speech initialization failure
     */
    private boolean isFirstLaunchSpeechError(Exception ex) {
        String msg = ex.getMessage();
        return msg != null && (
            msg.contains("0x22") || 
            msg.contains("SPXERR_INVALID_RECOGNIZER") ||
            msg.contains("invalid recognizer") ||
            msg.contains("not initialized")
        );
    }

    public void warmup() throws Exception {
        Log.i(TAG, "Starting warmup - waiting for recognizer initialization...");

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
        Log.i(TAG, "Starting REAL warmup - establishing actual connection...");

        long warmupStart = System.currentTimeMillis();
        CountDownLatch sessionLatch = new CountDownLatch(1);
        AtomicBoolean sessionStarted = new AtomicBoolean(false);
        AtomicReference<String> sessionError = new AtomicReference<>(null);

        recognizer.sessionStarted.addEventListener((s, e) -> {
            sessionStarted.set(true);
            Log.d(TAG, "Warmup session.started received after " + (System.currentTimeMillis() - warmupStart) + "ms");
            sessionLatch.countDown();
        });
        recognizer.sessionStopped.addEventListener((s, e) -> {
            if (!sessionStarted.get()) {
                sessionError.compareAndSet(null, "Session stopped before start");
                sessionLatch.countDown();
            }
        });
        recognizer.canceled.addEventListener((s, e) -> {
            String details = e.getErrorDetails();
            CancellationReason reason = e.getReason();
            if (details == null || details.isEmpty()) {
                details = "Session canceled" + (reason != null ? " (" + reason + ")" : "");
            }
            sessionError.set(details);
            sessionLatch.countDown();
        });

        boolean started = false;
        try {
            long startCallTs = System.currentTimeMillis();
            recognizer.startContinuousRecognitionAsync().get();
            long startDuration = System.currentTimeMillis() - startCallTs;
            Log.d(TAG, "startContinuousRecognitionAsync().get() finished in " + startDuration + "ms");
            started = true;

            boolean signaled = sessionLatch.await(3000, TimeUnit.MILLISECONDS);
            Log.d(TAG, "Warmup latch await result=" + signaled + ", sessionStarted=" + sessionStarted.get() + ", elapsed=" + (System.currentTimeMillis() - warmupStart) + "ms");
            if (!signaled) {
                throw new RuntimeException("Warmup timed out waiting for session start");
            }
            if (!sessionStarted.get()) {
                throw new RuntimeException(sessionError.get() != null ? sessionError.get() : "Session failed to start");
            }

            Log.i(TAG, "STT warmup completed in " + (System.currentTimeMillis() - warmupStart) + "ms");
            return;
        } catch (Exception e) {
            Log.w(TAG, "Real warmup failed, falling back: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        } finally {
            if (started) {
                try {
                    recognizer.stopContinuousRecognitionAsync().get();
                } catch (Exception stopEx) {
                    Log.w(TAG, "Warmup stop failed", stopEx);
                }
            }
        }

        int retriesRemaining = 3;
        int attempt = 1;
        while (retriesRemaining > 0) {
            try {
                attemptWarmupConnection(attempt);
                Log.i(TAG, "Recognizer warmup completed successfully" + (attempt > 1 ? " on retry #" + attempt : ""));
                return; // Success!
            } catch (Exception fallbackE) {
                Log.w(TAG, "Warmup attempt #" + attempt + " failed: " + fallbackE.getMessage());

                if (retriesRemaining > 1) { // Retry if not final attempt
                    Log.i(TAG, "Retrying after short delay... (" + retriesRemaining + " attempts remaining)");
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Warmup interrupted during retry delay.", ie);
                    }
                } else {
                    throw fallbackE; // Re-throw on final attempt
                }
            }
            retriesRemaining--;
            attempt++;
        }

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
                lock.wait(2000); // 2 second timeout per attempt (event-driven retry)
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
    
    

    public void shutdown() {
        try {
            if (recognizer != null) {
                try { recognizer.stopContinuousRecognitionAsync(); } catch (Exception ignored) {}
                try { recognizer.close(); } catch (Exception ignored) {}
                recognizer = null;
            }
        } catch (Exception ignored) {}
        executor.shutdown();
    }
    
    /**
     * Extract confidence score from Azure Speech recognition result
     * @param result The SpeechRecognitionResult
     * @return Confidence score (0.0-1.0), or 1.0 if extraction fails
     */
    private double extractConfidenceScore(com.microsoft.cognitiveservices.speech.SpeechRecognitionResult result) {
        try {
            // Try to get detailed results JSON via official PropertyId
            String detailedJson = result.getProperties().getProperty(PropertyId.SpeechServiceResponse_JsonResult);
            if (detailedJson != null && !detailedJson.isEmpty()) {
                JSONObject jsonResult = new JSONObject(detailedJson);
                
                // Get the NBest array (N-best recognition results)
                JSONArray nBestArray = jsonResult.optJSONArray("NBest");
                if (nBestArray != null && nBestArray.length() > 0) {
                    // Get the first (best) result
                    JSONObject bestResult = nBestArray.getJSONObject(0);
                    double confidence = bestResult.optDouble("Confidence", 1.0);
                    
                    Log.d(TAG, "Extracted confidence score: " + confidence + " for text: " + result.getText());
                    return confidence;
                }
                // If structure unexpected, log for diagnostics
                Log.w(TAG, "JsonResult present but NBest missing or empty: " + detailedJson);
            }
            
            // No usable JSON payload found; fall through to default
            
        } catch (Exception e) {
            Log.w(TAG, "Failed to extract confidence score: " + e.getMessage());
        }
        
        // Default to high confidence if extraction fails
        return 1.0;
    }
}
