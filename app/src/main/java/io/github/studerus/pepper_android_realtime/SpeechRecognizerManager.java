package io.github.studerus.pepper_android_realtime;

import android.util.Log;

import com.microsoft.cognitiveservices.speech.Connection;
import com.microsoft.cognitiveservices.speech.ResultReason;
import com.microsoft.cognitiveservices.speech.SpeechConfig;
import com.microsoft.cognitiveservices.speech.SpeechRecognizer;
import com.microsoft.cognitiveservices.speech.PropertyId;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings("SpellCheckingInspection")
public class SpeechRecognizerManager {
    public interface Listener {
        void onRecognizing(String partialText);
        void onRecognized(String text, double confidence);
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
                    if (listener != null && e != null) {
                        try { listener.onRecognizing(e.getResult().getText()); } catch (Exception ignored) {}
                    }
                });
                recognizer.recognized.addEventListener((s, e) -> {
                    try {
                        if (e.getResult().getReason() == ResultReason.RecognizedSpeech) {
                            String text = e.getResult().getText();
                            if (listener != null && text != null && !text.isEmpty()) {
                                double confidence = extractConfidenceScore(e.getResult());
                                listener.onRecognized(text, confidence);
                            }
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
        
        // Real warmup: Start continuous recognition and wait for session to be ready
        Log.i(TAG, "Starting REAL warmup - establishing actual connection...");
        try {
            // Start continuous recognition to establish connection
            recognizer.startContinuousRecognitionAsync().get();
            
            // Wait briefly for session to stabilize
            Thread.sleep(2000); // Give Azure time to establish session
            
            // Stop it immediately (this was just for connection establishment)
            recognizer.stopContinuousRecognitionAsync().get();
            
            Log.i(TAG, "🎤 STT warmup completed - connection truly established and ready");
            return;
            
        } catch (Exception e) {
            Log.w(TAG, "Real warmup failed, falling back to old method: " + e.getMessage());
            
            // Fallback: Attempt old warmup with retry for first-launch scenarios
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
                        Log.i(TAG, "Retrying after delay... (" + retriesRemaining + " attempts remaining)");
                        try {
                            Thread.sleep(500); // Reduced delay from 1000ms to 500ms
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
