package io.github.anonymous.pepper_realtime.manager;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * High-performance AudioPlayer with optimized buffer management and threading
 * 
 * Performance improvements:
 * - O(1) buffer operations using ArrayBlockingQueue
 * - Lock-free atomic operations where possible
 * - Memory pooling for byte arrays
 * - Producer-consumer pattern with proper signaling
 * - Reduced memory allocations and copying
 * - LockSupport.parkNanos() for precise timing instead of Thread.sleep()
 */
public class AudioPlayer {
    public interface Listener {
        void onPlaybackStarted();
        void onPlaybackFinished();
    }

    private static final String TAG = "AudioPlayer";
    
    // Performance-optimized buffer using bounded queue for O(1) operations
    // Increased to 150 to handle GA API's larger/more frequent audio chunks (~6-7 seconds of audio at 24kHz)
    private final ArrayBlockingQueue<byte[]> audioBuffer = new ArrayBlockingQueue<>(150);
    
    // Atomic flags for lock-free operations
    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private final AtomicBoolean isResponseDone = new AtomicBoolean(false);
    private final AtomicBoolean shouldStop = new AtomicBoolean(false);
    
    private AudioTrack audioTrack;
    private Listener listener;
    private int sampleRateHz = 24000;
    private final int channels = 1;
    private final int bytesPerSample = 2; // pcm16

    // Memory pool for reducing allocations
    private final ByteArrayPool memoryPool = new ByteArrayPool();
    
    // Optimized frame alignment buffer
    private volatile byte[] carryBuffer = null;
    private final AtomicInteger carryLength = new AtomicInteger(0);
    
    // Track frames written for precise drain - using AtomicLong for thread safety
    private final AtomicLong totalFramesWritten = new AtomicLong(0);
    // Track playback head position at response start to calculate relative position
    private volatile int responseStartFrames = 0;
    private Thread playThread = null;

    public AudioPlayer() { 
        initializeAudioTrack(); 
    }

    public void setListener(Listener listener) { 
        this.listener = listener; 
    }
    
    public boolean isPlaying() { 
        return isPlaying.get(); 
    }

    public void setSampleRate(int hz) {
        if (hz > 0 && hz != sampleRateHz) {
            sampleRateHz = hz;
            release();
            initializeAudioTrack();
        }
    }

    /**
     * Optimized chunk addition with memory pooling and contamination prevention
     */
    public void addChunk(byte[] data) {
        if (data == null || data.length == 0) return;
        
        // Use memory pool to reduce allocations
        byte[] pooled = memoryPool.acquireClean(data.length);
        System.arraycopy(data, 0, pooled, 0, data.length);
        
        // Non-blocking add with fallback
        if (!audioBuffer.offer(pooled)) {
            // Buffer full - drop oldest chunk to maintain real-time performance
            byte[] dropped = audioBuffer.poll();
            if (dropped != null) {
                memoryPool.release(dropped);
            }
            audioBuffer.offer(pooled);
            Log.w(TAG, "Audio buffer overflow - dropped chunk to maintain real-time performance");
        }
    }

    /**
     * Optimized playback start with better buffering strategy
     */
    public void startIfNeeded() {
        if (isPlaying.get()) return;
        if (!hasSufficientBuffer()) return;
        
        if (isPlaying.compareAndSet(false, true)) {
            shouldStop.set(false);
            if (listener != null) listener.onPlaybackStarted();
            
            Thread t = new Thread(this::optimizedPlayLoop, "optimized-audio-player");
            t.setPriority(Thread.NORM_PRIORITY + 2); // Higher priority for audio
            playThread = t;
            t.start();
        }
    }

    public void markResponseDone() { 
        isResponseDone.set(true);
        // Memory pool cleanup moved to final cleanup() to prevent 
        // contamination during overlapping responses
        // Reset carry buffer to avoid cross-response merge (e.g., repeated syllables)
        resetCarryBuffer();
    }

    /**
     * Called when a new response_id begins streaming. Prevents cross-response merging
     * by clearing any partial-frame carry from the previous response.
     */
    public void onResponseBoundary() {
        resetCarryBuffer();
        // Reset timing tracking for new response to prevent cumulative timing errors
        totalFramesWritten.set(0);
        // Capture current playback position as baseline for this response
        try {
            responseStartFrames = audioTrack != null ? audioTrack.getPlaybackHeadPosition() : 0;
        } catch (Exception e) {
            responseStartFrames = 0; // Fallback if AudioTrack not ready
        }
    }

    public int getEstimatedPlaybackPositionMs() {
        try {
            if (audioTrack == null) return 0;
            int currentFrames = audioTrack.getPlaybackHeadPosition();
            // Calculate relative position since this response started
            int relativeFrames = Math.max(0, currentFrames - responseStartFrames);
            return (int) Math.round(relativeFrames * 1000.0 / sampleRateHz);
        } catch (Exception e) {
            // Return 0 if AudioTrack not ready or any error occurs
            return 0;
        }
    }

    /**
     * Optimized interrupt with immediate stop and memory cleanup
     */
    public void interruptNow() {
        try {
            shouldStop.set(true);
            isResponseDone.set(true);
            
            // Clean audio buffer and return buffers to pool
            cleanupAudioBuffer();
            resetCarryBuffer();
            
            // Clean memory pool to prevent contamination
            memoryPool.cleanupBetweenResponses();
            
            if (audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                try { audioTrack.pause(); } catch (Exception ignored) {}
                try { audioTrack.flush(); } catch (Exception ignored) {}
                try { audioTrack.stop(); } catch (Exception ignored) {}
            }
            
            // Extended thread join for proper cleanup
            Thread t = playThread;
            if (t != null && t != Thread.currentThread()) {
                try { t.join(200); } catch (InterruptedException ie) { 
                    Thread.currentThread().interrupt(); 
                }
            }
        } catch (Exception ignored) {}
    }
    
    /**
     * Clean audio buffer and return all buffers to pool
     */
    private void cleanupAudioBuffer() {
        byte[] buffer;
        while ((buffer = audioBuffer.poll()) != null) {
            memoryPool.release(buffer);
        }
    }

    public void release() {
        try {
            shouldStop.set(true);
            if (audioTrack != null) {
                if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    try { audioTrack.stop(); } catch (Exception ignored) {}
                }
                audioTrack.release();
                audioTrack = null;
            }
            // Clean up memory pool
            memoryPool.cleanup();
        } catch (Exception ignored) {}
    }

    private void initializeAudioTrack() {
        try {
            int internalBufferMs = 300; // Increased for GA API compatibility (was 200ms)
            int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
            int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
            int minBuf = AudioTrack.getMinBufferSize(sampleRateHz, channelConfig, audioFormat);
            int bufferSize = Math.max(minBuf, (int)(sampleRateHz * bytesPerSample * channels * (internalBufferMs / 1000.0)));
            
            // Build audio attributes with conditional low latency flag (API 24+)
            AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                attributesBuilder.setFlags(AudioAttributes.FLAG_LOW_LATENCY);
            }
            
            audioTrack = new AudioTrack(
                    attributesBuilder.build(),
                    new AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRateHz)
                            .setChannelMask(channelConfig)
                            .build(),
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
                    
            Log.i(TAG, "Optimized AudioTrack initialized. rate=" + sampleRateHz + "Hz, buf=" + bufferSize);
            resetCarryBuffer();
            totalFramesWritten.set(0);
            responseStartFrames = 0;
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize AudioTrack", e);
        }
    }

    private boolean hasSufficientBuffer() {
        // Start only when we have a small headroom of chunks to avoid initial underflow
        int minChunks = 6; // ~60ms at 10ms frames
        return audioBuffer.size() >= minChunks;
    }

    /**
     * Highly optimized playback loop with reduced allocations and better timing
     */
    private void optimizedPlayLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        
        if (audioTrack == null || audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack not ready.");
            isPlaying.set(false);
            return;
        }
        
        try {
            audioTrack.play();
            int frameBytes = frameBytes10ms();
            if (frameBytes <= 0) frameBytes = 480; // safety for 24kHz mono pcm16
            
            while (!shouldStop.get()) {
                byte[] data = audioBuffer.poll(); // O(1) operation
                
                if (data != null) {
                    processAudioChunk(data, frameBytes);
                    memoryPool.release(data); // Return to pool
                } else {
                    if (isResponseDone.get()) break;
                    
                    // More efficient waiting using LockSupport
                    LockSupport.parkNanos(5_000_000); // 5ms in nanoseconds
                }
            }
            
            // Process any remaining carry buffer
            flushCarryBuffer();
            drainAudioTrack();
            
        } catch (Exception e) {
            Log.e(TAG, "Optimized playback error", e);
        } finally {
            cleanup();
        }
    }

    /**
     * Process audio chunk with optimized memory management
     */
    private void processAudioChunk(byte[] data, int frameBytes) {
        byte[] toWrite = combineWithCarry(data);
        boolean needsRelease = (toWrite != data); // If combined, we need to release it
        int len = toWrite.length;
        int offset = 0;
        
        // Write in aligned frames for optimal performance
        while (len - offset >= frameBytes && !shouldStop.get()) {
            int written = audioTrack.write(toWrite, offset, frameBytes, AudioTrack.WRITE_BLOCKING);
            if (written < 0) {
                // Brief park for audio underflow recovery
                LockSupport.parkNanos(2_000_000); // 2ms
            } else {
                offset += written;
                totalFramesWritten.addAndGet(written / (long)(bytesPerSample * channels));
            }
        }
        
        // Handle remaining bytes efficiently
        updateCarryBuffer(toWrite, offset, len);
        
        // Release combined buffer back to pool if it was created
        if (needsRelease) {
            memoryPool.release(toWrite);
        }
    }

    /**
     * Efficient carry buffer management with clean buffer acquisition
     */
    private byte[] combineWithCarry(byte[] data) {
        int carryLen = carryLength.get();
        if (carryLen == 0) {
            return data;
        }
        
        byte[] combined = memoryPool.acquireClean(carryLen + data.length);
        System.arraycopy(carryBuffer, 0, combined, 0, carryLen);
        System.arraycopy(data, 0, combined, carryLen, data.length);
        
        return combined;
    }

    private void updateCarryBuffer(byte[] data, int offset, int totalLen) {
        int remaining = totalLen - offset;
        if (remaining > 0) {
            if (carryBuffer == null || carryBuffer.length < remaining) {
                carryBuffer = new byte[remaining];
            }
            System.arraycopy(data, offset, carryBuffer, 0, remaining);
            carryLength.set(remaining);
        } else {
            carryLength.set(0);
        }
    }

    private void resetCarryBuffer() {
        carryLength.set(0);
    }

    private void flushCarryBuffer() {
        int carryLen = carryLength.get();
        if (carryLen > 0 && carryBuffer != null && !shouldStop.get()) {
            int written = audioTrack.write(carryBuffer, 0, carryLen, AudioTrack.WRITE_BLOCKING);
            if (written > 0) {
                totalFramesWritten.addAndGet(written / (long)(bytesPerSample * channels));
            }
            resetCarryBuffer();
        }
    }

    private void drainAudioTrack() {
        try {
            long totalFrames = totalFramesWritten.get();
            int targetFrames = (totalFrames > Integer.MAX_VALUE) ? 
                Integer.MAX_VALUE : (int) totalFrames;
            long startWait = System.nanoTime();
            
            while (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING
                    && audioTrack.getPlaybackHeadPosition() < targetFrames
                    && !shouldStop.get()) {
                LockSupport.parkNanos(5_000_000); // 5ms
                
                // Safety timeout
                if ((System.nanoTime() - startWait) > 1_500_000_000L) { // 1.5 seconds
                    break;
                }
            }
            audioTrack.stop();
        } catch (Exception e) {
            Log.w(TAG, "Drain wait interrupted", e);
        }
    }

    private void cleanup() {
        isPlaying.set(false);
        isResponseDone.set(false);
        
        // Properly clean audio buffer and return to pool
        cleanupAudioBuffer();
        resetCarryBuffer();
        totalFramesWritten.set(0);
        
        // Clean memory pool between responses
        memoryPool.cleanupBetweenResponses();
        
        if (listener != null) {
            listener.onPlaybackFinished();
        }
    }

    private int frameBytes10ms() {
        int samplesPer10ms = sampleRateHz / 100;
        return samplesPer10ms * bytesPerSample * channels;
    }

    /**
     * Enhanced memory pool for byte arrays with contamination prevention
     */
    private static class ByteArrayPool {
        private final ArrayBlockingQueue<byte[]> pool = new ArrayBlockingQueue<>(50);
        
        /**
         * Acquire a buffer and ensure it's clean (all zeros)
         */
        byte[] acquireClean(int size) {
            // Always return an array with EXACT length 'size' to avoid writing stale tail bytes
            byte[] array = pool.poll();
            if (array == null || array.length != size) {
                return new byte[size];
            }
            java.util.Arrays.fill(array, (byte) 0);
            return array;
        }
        
        void release(byte[] array) {
            if (array == null) return;
            // Only pool arrays of common sizes to improve reuse while avoiding size mismatches
            if (array.length >= 256) {
                pool.offer(array);
            }
        }
        
        /**
         * Clean up pool between responses to prevent audio contamination
         */
        void cleanupBetweenResponses() {
            // Clear all pooled buffers to prevent contamination between responses
            byte[] buffer;
            while ((buffer = pool.poll()) != null) {
                // Explicitly zero out buffer before discarding
                java.util.Arrays.fill(buffer, (byte) 0);
            }
            Log.d(TAG, "Memory pool cleaned between responses");
        }
        
        void cleanup() {
            cleanupBetweenResponses();
        }
    }
}
