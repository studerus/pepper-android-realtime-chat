package io.github.anonymous.pepper_realtime;

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
    
    // Activity-focused callback interface
    public interface ActivityCallbacks {
        void onRecognizedText(String text);
        void onPartialText(String partialText);
        void onError(String errorMessage);
        void onStarted();
        void onStopped();
        void onReady(); // Called when recognizer is fully warmed up and ready to use
    }
    
    // Internal listener interface for Azure SDK


    private static final String TAG = "SpeechRecMgr";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SpeechRecognizer recognizer;
    private ActivityCallbacks activityCallbacks;
    private final Object recognizerLock = new Object();
    private volatile boolean isRunning = false;
    private volatile boolean connectionEstablished = false;
    private volatile boolean sessionActive = false;
    private volatile boolean readySignaled = false;
    
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
        
        initializeRecognizer();
    }
    
    /**
     * Initialize recognizer - simple and clean
     */
    private void initializeRecognizer() {
        Log.i(TAG, "🔧 Initializing recognizer...");
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
                
                // Give Azure Speech SDK time to fully initialize internally
                Thread.sleep(500);

                // Register connection event listener - this tells us when truly ready
                Connection connection = Connection.fromRecognizer(recognizer);
                connection.connected.addEventListener((s, e) -> {
                    Log.i(TAG, "🌐 Connection established - recognizer ready");
                    connectionEstablished = true;
                    signalReadyIfNeeded("connection", false);
                });
                connection.disconnected.addEventListener((s, e) -> {
                    Log.w(TAG, "⚠️ Connection lost");
                    connectionEstablished = false;
                    sessionActive = false;
                    readySignaled = false;
                });

                recognizer.sessionStarted.addEventListener((s, e) -> {
                    Log.i(TAG, "🎙️ sessionStarted event received");
                    sessionActive = true;
                    signalReadyIfNeeded("sessionStarted", false);
                });
                recognizer.sessionStopped.addEventListener((s, e) -> {
                    Log.i(TAG, "🛑 sessionStopped event received");
                    sessionActive = false;
                    readySignaled = false;
                });

                // Register recognition event listeners
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
                
                try {
                    connection.openConnection(true);
                    Log.i(TAG, "Proactively opened recognizer connection after initialization");
                } catch (Exception openEx) {
                    Log.w(TAG, "Unable to proactively open recognizer connection after init", openEx);
                }
                
                Log.i(TAG, "✅ Recognizer initialized for " + currentLanguage + ", silence=" + currentSilenceTimeout + "ms");
                
                // Notify that recognizer is ready - warmup will happen on first use
                if (activityCallbacks != null) {
                    activityCallbacks.onReady();
                }
                
            } catch (Exception ex) {
                Log.e(TAG, "❌ Initialize failed", ex);
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
                    isRunning = true; // Set BEFORE starting, so connection.connected listener can fire onStarted()
                    connectionEstablished = false; // Reset flag
                    sessionActive = false;
                    readySignaled = false;
                    recognizer.startContinuousRecognitionAsync().get();
                    Log.i(TAG, "Continuous recognition started (waiting for connection established event)");
                    
                    // Fallback timer: If connection event doesn't fire within 10 seconds, call onStarted() anyway
                    Thread fallbackThread = new Thread(() -> {
                        try {
                            Thread.sleep(10000); // Wait 10 seconds
                            if (isRunning && !readySignaled) {
                                Log.w(TAG, "⚠️ Connection still pending after 10s - awaiting Azure events");
                            }
                        } catch (InterruptedException ignored) {
                            // Thread was interrupted because connection was established
                        }
                    });
                    fallbackThread.setDaemon(true);
                    fallbackThread.start();
                    // NOTE: onStarted() will be called by the connection.connected event listener
                    // when the connection to Azure Speech is truly established
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
                    sessionActive = false;
                    connectionEstablished = false;
                    readySignaled = false;
                    if (activityCallbacks != null) activityCallbacks.onStopped();
                    Log.i(TAG, "Continuous recognition stop requested (async)");
                }
            } catch (Exception ex) {
                isRunning = false; // Reset flag even on error
                sessionActive = false;
                connectionEstablished = false;
                readySignaled = false;
                Log.e(TAG, "Error stopping speech recognizer (ignored)", ex);
            }
        });
    }
    
    private void handleStartFailure(Exception ex) {
        if (isFirstLaunchSpeechError(ex)) {
            Log.i(TAG, "Detected first-launch speech error, attempting lazy re-initialization");
            try {
                // Synchronous re-initialization (without warmup, as this is a fallback)
                SpeechConfig cfg = SpeechConfig.fromSubscription(currentApiKey, currentRegion);
                cfg.setSpeechRecognitionLanguage(currentLanguage);
                cfg.setProperty("Speech_SegmentationSilenceTimeoutMs", String.valueOf(currentSilenceTimeout));
                cfg.requestWordLevelTimestamps();
                cfg.setOutputFormat(com.microsoft.cognitiveservices.speech.OutputFormat.Detailed);
                
                if (recognizer != null) {
                    try { recognizer.stopContinuousRecognitionAsync().get(); } catch (Exception ignored) {}
                    try { recognizer.close(); } catch (Exception ignored) {}
                }
                recognizer = new SpeechRecognizer(cfg);
                Thread.sleep(1000);
                
                // CRITICAL: Register event listeners for the new recognizer instance
                Connection connection = Connection.fromRecognizer(recognizer);
                connection.connected.addEventListener((s, e) -> {
                    Log.i(TAG, "🌐 Recognizer connection established (lazy init)");
                    connectionEstablished = true;
                    signalReadyIfNeeded("lazy-connection", false);
                });
                connection.disconnected.addEventListener((s, e) -> {
                    Log.w(TAG, "⚠️ Recognizer connection lost (lazy init)");
                    connectionEstablished = false;
                    sessionActive = false;
                    readySignaled = false;
                });

                recognizer.sessionStarted.addEventListener((s, e) -> {
                    Log.i(TAG, "🎙️ sessionStarted (lazy init)");
                    sessionActive = true;
                    signalReadyIfNeeded("lazy-session", false);
                });
                recognizer.sessionStopped.addEventListener((s, e) -> {
                    Log.i(TAG, "🛑 sessionStopped (lazy init)");
                    sessionActive = false;
                    readySignaled = false;
                });
                
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
                    } catch (Exception ex2) {
                        if (activityCallbacks != null) activityCallbacks.onError(ex2.getMessage());
                    }
                });
                
                if (recognizer != null) {
                    isRunning = true;
                    connectionEstablished = false;
                    sessionActive = false;
                    readySignaled = false;
                    recognizer.startContinuousRecognitionAsync().get();
                    Log.i(TAG, "Lazy speech initialization successful (waiting for connection/session)");
                    Thread fallbackThread = new Thread(() -> {
                        try {
                            Thread.sleep(10000);
                            if (isRunning && !readySignaled) {
                                Log.w(TAG, "⚠️ Lazy init: connection still pending after 10s - awaiting Azure events");
                            }
                        } catch (InterruptedException ignored) {
                        }
                    });
                    fallbackThread.setDaemon(true);
                    fallbackThread.start();
                    // onStarted() will be called by session/connection event
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
    
    private void signalReadyIfNeeded(String source, boolean force) {
        if (!isRunning || readySignaled) {
            return;
        }
        if (!force && (!connectionEstablished || !sessionActive)) {
            Log.i(TAG, "⏳ Waiting for full readiness (" + source + ") "
                    + "connection=" + connectionEstablished + ", sessionActive=" + sessionActive);
            return;
        }
        
        if (activityCallbacks != null) {
            readySignaled = true;
            Log.i(TAG, "🟢 Recognizer ready (" + source + ")");
            activityCallbacks.onStarted();
        }
    }
    
    private void signalReadyIfNeeded(String source) {
        signalReadyIfNeeded(source, false);
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
