package io.github.studerus.pepper_android_realtime;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Base64;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages audio input capture for Realtime API
 * Captures PCM16 24kHz mono audio and streams it via WebSocket
 */
public class RealtimeAudioInputManager {
    private static final String TAG = "RealtimeAudioInput";
    
    // Realtime API audio format requirements
    private static final int SAMPLE_RATE = 24000; // 24kHz required by Realtime API
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BYTES_PER_SAMPLE = 2; // 16-bit = 2 bytes
    
    // Buffer size: ~100ms of audio (2400 samples * 2 bytes = 4800 bytes)
    private static final int BUFFER_SIZE_SAMPLES = 2400;
    private static final int BUFFER_SIZE_BYTES = BUFFER_SIZE_SAMPLES * BYTES_PER_SAMPLE;
    
    private final RealtimeSessionManager sessionManager;
    private AudioRecord audioRecord;
    private Thread captureThread;
    private final AtomicBoolean isCapturing = new AtomicBoolean(false);
    
    public RealtimeAudioInputManager(RealtimeSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }
    
    /**
     * Initialize AudioRecord with Realtime API format
     */
    private boolean initializeAudioRecord() {
        try {
            // Calculate minimum buffer size
            int minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            );
            
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Invalid buffer size from AudioRecord.getMinBufferSize()");
                return false;
            }
            
            // Use larger of our preferred size or minimum required
            int bufferSizeInBytes = Math.max(BUFFER_SIZE_BYTES, minBufferSize);
            
            // Create AudioRecord instance
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // Optimized for voice with noise suppression
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSizeInBytes
            );
            
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize");
                audioRecord = null;
                return false;
            }
            
            Log.i(TAG, "AudioRecord initialized: " + SAMPLE_RATE + "Hz, buffer=" + bufferSizeInBytes + " bytes");
            return true;
            
        } catch (SecurityException e) {
            Log.e(TAG, "Missing RECORD_AUDIO permission", e);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize AudioRecord", e);
            return false;
        }
    }
    
    /**
     * Start capturing and streaming audio
     */
    public boolean start() {
        if (isCapturing.get()) {
            Log.w(TAG, "Audio capture already running");
            return true;
        }
        
        if (!sessionManager.isConnected()) {
            Log.e(TAG, "Cannot start audio capture - WebSocket not connected");
            return false;
        }
        
        // Initialize AudioRecord
        if (!initializeAudioRecord()) {
            return false;
        }
        
        // Start recording
        try {
            audioRecord.startRecording();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording", e);
            cleanup();
            return false;
        }
        
        // Start capture thread
        isCapturing.set(true);
        captureThread = new Thread(this::captureLoop, "realtime-audio-capture");
        captureThread.setPriority(Thread.NORM_PRIORITY + 1); // Slightly elevated priority
        captureThread.start();
        
        Log.i(TAG, "Audio capture started");
        return true;
    }
    
    /**
     * Stop capturing audio
     */
    public void stop() {
        if (!isCapturing.get()) {
            return;
        }
        
        Log.i(TAG, "Stopping audio capture...");
        isCapturing.set(false);
        
        // Wait for capture thread to finish
        if (captureThread != null) {
            try {
                captureThread.join(1000); // Wait max 1 second
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            captureThread = null;
        }
        
        cleanup();
        Log.i(TAG, "Audio capture stopped");
    }
    
    /**
     * Audio capture loop - runs in background thread
     */
    private void captureLoop() {
        byte[] buffer = new byte[BUFFER_SIZE_BYTES];
        int consecutiveErrors = 0;
        final int MAX_CONSECUTIVE_ERRORS = 5;
        
        Log.d(TAG, "Capture loop started");
        
        while (isCapturing.get()) {
            try {
                // Read audio data
                int bytesRead = audioRecord.read(buffer, 0, buffer.length);
                
                if (bytesRead > 0) {
                    consecutiveErrors = 0; // Reset error counter on success
                    
                    // Send to Realtime API
                    sendAudioChunk(buffer, bytesRead);
                    
                } else if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                    Log.e(TAG, "AudioRecord not properly initialized");
                    break;
                } else if (bytesRead == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "Invalid parameters in read operation");
                    break;
                } else {
                    consecutiveErrors++;
                    Log.w(TAG, "AudioRecord read returned: " + bytesRead + " (error count: " + consecutiveErrors + ")");
                    
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        Log.e(TAG, "Too many consecutive errors, stopping capture");
                        break;
                    }
                    
                    // Brief pause before retry
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error in capture loop", e);
                consecutiveErrors++;
                
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    break;
                }
            }
        }
        
        Log.d(TAG, "Capture loop ended");
    }
    
    /**
     * Send audio chunk to Realtime API via WebSocket
     */
    private void sendAudioChunk(byte[] buffer, int bytesRead) {
        try {
            // Encode to Base64
            String base64Audio = Base64.encodeToString(buffer, 0, bytesRead, Base64.NO_WRAP);
            
            // Send via session manager
            boolean sent = sessionManager.sendAudioChunk(base64Audio);
            
            if (!sent) {
                Log.w(TAG, "Failed to send audio chunk (" + bytesRead + " bytes)");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error encoding/sending audio chunk", e);
        }
    }
    
    /**
     * Cleanup resources
     */
    private void cleanup() {
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception e) {
                Log.w(TAG, "Error during AudioRecord cleanup", e);
            }
            audioRecord = null;
        }
    }
    
    /**
     * Check if currently capturing
     */
    public boolean isCapturing() {
        return isCapturing.get();
    }
}
