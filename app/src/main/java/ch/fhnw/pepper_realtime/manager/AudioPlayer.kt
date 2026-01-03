package ch.fhnw.pepper_realtime.manager

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.os.Build
import android.os.Process
import android.util.Log
import java.util.Arrays
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

// File-level TAG so it is accessible from nested classes as well.
private const val TAG = "AudioPlayer"

/**
 * Upper bound for queued audio (in ms of audio at current output sample rate).
 * This keeps peak RAM predictable on older devices.
 *
 * Important: This does NOT add constant latency. It only limits how far behind real-time
 * we are allowed to fall during bursty streaming.
 */
private const val MAX_BUFFERED_AUDIO_MS = 8000

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
class AudioPlayer {

    interface Listener {
        fun onPlaybackStarted()
        fun onPlaybackFinished()
    }

    // Performance-optimized buffer using bounded queue for O(1) operations
    // Larger queue to absorb bursty streams (e.g., Google Live API) without dropping chunks.
    // Note: This does not add *constant* latency, but may increase latency only if we fall behind.
    private val audioBuffer = ArrayBlockingQueue<ByteArray>(350)

    // Atomic flags for lock-free operations
    private val _isPlaying = AtomicBoolean(false)
    private val isResponseDone = AtomicBoolean(false)
    private val shouldStop = AtomicBoolean(false)

    private var audioTrack: AudioTrack? = null
    private var listener: Listener? = null
    private var sampleRateHz = 24000
    private val channels = 1
    private val bytesPerSample = 2 // pcm16

    // Memory pool for reducing allocations
    private val memoryPool = ByteArrayPool()

    // Optimized frame alignment buffer
    @Volatile
    private var carryBuffer: ByteArray? = null
    private val carryLength = AtomicInteger(0)

    // Track frames written for precise drain - using AtomicLong for thread safety
    private val totalFramesWritten = AtomicLong(0)

    // Track queued audio bytes to enforce a predictable memory ceiling.
    private val queuedBytes = AtomicInteger(0)

    // Track playback head position at response start to calculate relative position
    @Volatile
    private var responseStartFrames = 0
    private var playThread: Thread? = null

    // Completion tracking for event-driven drain
    private val playbackCompleted = AtomicBoolean(false)

    @Volatile
    private var drainStartTime: Long = 0

    // Rate-limit overflow logs (avoid log spam during bursty streams)
    @Volatile
    private var lastOverflowLogMs: Long = 0

    init {
        initializeAudioTrack()
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun isPlaying(): Boolean = _isPlaying.get()



    /**
     * Optimized chunk addition with memory pooling and contamination prevention
     */
    fun addChunk(data: ByteArray?) {
        if (data == null || data.isEmpty()) return

        // Enforce a predictable byte ceiling for queued audio (avoid unbounded RAM growth).
        // Prefer waiting briefly for the consumer to catch up instead of dropping audio.
        val incomingBytes = data.size
        val maxBytes = maxBufferedBytes()
        if (maxBytes > 0) {
            val startWait = SystemClock.elapsedRealtime()
            while (queuedBytes.get() + incomingBytes > maxBytes && (SystemClock.elapsedRealtime() - startWait) < 80) {
                LockSupport.parkNanos(2_000_000) // 2ms
            }

            // If still above limit, drop the oldest chunks until the new chunk can fit.
            if (queuedBytes.get() + incomingBytes > maxBytes) {
                var droppedCount = 0
                while (queuedBytes.get() + incomingBytes > maxBytes) {
                    val dropped = audioBuffer.poll() ?: break
                    queuedBytes.addAndGet(-dropped.size)
                    memoryPool.release(dropped)
                    droppedCount++
                }
                if (droppedCount > 0) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastOverflowLogMs > 750) {
                        lastOverflowLogMs = now
                        Log.w(TAG, "Audio buffer overflow - dropped $droppedCount chunk(s) to stay within ${maxBytes} bytes")
                    }
                }
            }
        }

        // Use memory pool to reduce allocations
        val pooled = memoryPool.acquireClean(data.size)
        System.arraycopy(data, 0, pooled, 0, data.size)

        // Non-blocking add with fallback
        var enqueued = audioBuffer.offer(pooled)
        if (!enqueued) {
            // Buffer full - drop only ONE oldest chunk as a last resort.
            // (Aggressive dropping can skip words and is not acceptable for speech UX.)
            val dropped = audioBuffer.poll()
            if (dropped != null) {
                queuedBytes.addAndGet(-dropped.size)
                memoryPool.release(dropped)
            }
            enqueued = audioBuffer.offer(pooled)

            // Rate-limit warning to avoid spamming Logcat during bursts.
            val now = SystemClock.elapsedRealtime()
            if (now - lastOverflowLogMs > 750) {
                lastOverflowLogMs = now
                if (enqueued) {
                    Log.w(TAG, "Audio buffer overflow - dropped 1 chunk (queue capacity reached)")
                } else {
                    Log.w(TAG, "Audio buffer overflow - failed to enqueue chunk even after dropping 1 (queue capacity reached)")
                }
            }
        }

        if (enqueued) {
            queuedBytes.addAndGet(pooled.size)
        } else {
            // If we couldn't enqueue, release to the pool to avoid leaking pooled buffers.
            memoryPool.release(pooled)
        }
    }

    /**
     * Optimized playback start with better buffering strategy
     */
    fun startIfNeeded() {
        if (_isPlaying.get()) return
        if (!hasSufficientBuffer()) return

        if (_isPlaying.compareAndSet(false, true)) {
            shouldStop.set(false)
            listener?.onPlaybackStarted()

            val t = Thread({ optimizedPlayLoop() }, "optimized-audio-player")
            t.priority = Thread.NORM_PRIORITY + 2 // Higher priority for audio
            playThread = t
            t.start()
        }
    }

    fun markResponseDone() {
        isResponseDone.set(true)
        // Memory pool cleanup moved to final cleanup() to prevent
        // contamination during overlapping responses
        // Reset carry buffer to avoid cross-response merge (e.g., repeated syllables)
        resetCarryBuffer()
        // Reset drain timing to prevent stale logs
        drainStartTime = 0
    }

    /**
     * Called when a new response_id begins streaming. Prevents cross-response merging
     * by clearing any partial-frame carry from the previous response.
     */
    fun onResponseBoundary() {
        resetCarryBuffer()
        // Reset timing tracking for new response to prevent cumulative timing errors
        totalFramesWritten.set(0)
        // Capture current playback position as baseline for this response
        try {
            responseStartFrames = audioTrack?.playbackHeadPosition ?: 0
        } catch (e: Exception) {
            responseStartFrames = 0 // Fallback if AudioTrack not ready
        }
        // Reset drain timing to prevent stale logs from previous response
        drainStartTime = 0
        playbackCompleted.set(false)
    }

    fun getEstimatedPlaybackPositionMs(): Int {
        try {
            val track = audioTrack ?: return 0

            // 1. Calculate position based on AudioTrack head
            // Handle potential int overflow/wrap-around correctly by subtraction first
            val currentFrames = track.playbackHeadPosition
            val relativeFrames = maxOf(0, currentFrames - responseStartFrames)
            val playedMs = Math.round(relativeFrames * 1000.0 / sampleRateHz).toInt()

            // 2. Calculate max possible duration based on written data
            val writtenFrames = totalFramesWritten.get()
            val writtenMs = Math.round(writtenFrames * 1000.0 / sampleRateHz).toInt()

            // 3. Clamp result: we cannot have played more than we wrote
            return minOf(playedMs, writtenMs)
        } catch (e: Exception) {
            // Return 0 if AudioTrack not ready or any error occurs
            return 0
        }
    }

    /**
     * Optimized interrupt with immediate stop and memory cleanup
     */
    fun interruptNow() {
        try {
            shouldStop.set(true)
            isResponseDone.set(true)

            // Clean audio buffer and return buffers to pool
            cleanupAudioBuffer()
            resetCarryBuffer()

            // Clean memory pool to prevent contamination
            memoryPool.cleanupBetweenResponses()

            audioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    try { track.pause() } catch (ignored: Exception) {}
                    try { track.flush() } catch (ignored: Exception) {}
                    try { track.stop() } catch (ignored: Exception) {}
                }
            }

            // Extended thread join for proper cleanup
            val t = playThread
            if (t != null && t !== Thread.currentThread()) {
                try {
                    t.join(200)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        } catch (ignored: Exception) {}
    }

    /**
     * Clean audio buffer and return all buffers to pool
     */
    private fun cleanupAudioBuffer() {
        var buffer: ByteArray?
        while (audioBuffer.poll().also { buffer = it } != null) {
            queuedBytes.addAndGet(-buffer!!.size)
            memoryPool.release(buffer!!)
        }
        queuedBytes.set(0)
    }

    fun release() {
        try {
            shouldStop.set(true)
            audioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    try { track.stop() } catch (ignored: Exception) {}
                }
                track.release()
            }
            audioTrack = null
            // Clean up memory pool
            memoryPool.cleanup()
        } catch (ignored: Exception) {}
    }

    private fun initializeAudioTrack() {
        try {
            val internalBufferMs = 100
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioTrack.getMinBufferSize(sampleRateHz, channelConfig, audioFormat)
            val bufferSize = maxOf(minBuf,
                (sampleRateHz * bytesPerSample * channels * (internalBufferMs / 1000.0)).toInt())

            // Build audio attributes with conditional low latency flag (API 24+)
            val attributesBuilder = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                @Suppress("DEPRECATION")
                attributesBuilder.setFlags(AudioAttributes.FLAG_LOW_LATENCY)
            }

            audioTrack = AudioTrack(
                attributesBuilder.build(),
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(channelConfig)
                    .build(),
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            // Set up completion callback for event-driven drain
            setupPlaybackCompletionListener()

            Log.i(TAG, "Optimized AudioTrack initialized. rate=${sampleRateHz}Hz, buf=${bufferSize}ms=$internalBufferMs")
            resetCarryBuffer()
            totalFramesWritten.set(0)
            responseStartFrames = 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack", e)
        }
    }

    private fun setupPlaybackCompletionListener() {
        val track = audioTrack ?: return

        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: AudioTrack) {
                val start = drainStartTime
                val drainDuration = if (start > 0) SystemClock.elapsedRealtime() - start else -1L
                if (drainDuration >= 0) {
                    Log.d(TAG, "Playback marker reached - drain took ${drainDuration}ms")
                } else {
                    // Defensive: marker may fire if an old marker is behind the current head position.
                    Log.d(TAG, "Playback marker reached (drainStartTime not set)")
                }
                playbackCompleted.set(true)
            }

            override fun onPeriodicNotification(track: AudioTrack) {
                // Not used
            }
        })
    }

    private fun hasSufficientBuffer(): Boolean {
        // Start only when we have enough audio to avoid initial underflow.
        // Using bytes instead of chunk count because chunk sizes vary by provider:
        // - OpenAI: ~10ms chunks (240 bytes)
        // - x.ai: larger chunks (potentially 200-500ms)
        // - Google: variable chunk sizes
        // Target: ~60ms of audio = 60 * 24 * 2 = 2880 bytes at 24kHz mono PCM16
        val minBufferBytes = 60 * sampleRateHz * bytesPerSample * channels / 1000
        return queuedBytes.get() >= minBufferBytes
    }

    /**
     * Ensures AudioTrack is initialized and ready for playback.
     * Reinitializes if it was released or is in an invalid state.
     */
    @Synchronized
    private fun ensureAudioTrackReady() {
        val track = audioTrack
        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
            Log.i(TAG, "Reinitializing AudioTrack (was released or invalid)")
            shouldStop.set(false)
            initializeAudioTrack()
        }
    }

    /**
     * Highly optimized playback loop with reduced allocations and better timing
     */
    private fun optimizedPlayLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        // Auto-reinitialize if needed
        ensureAudioTrackReady()

        val track = audioTrack
        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack not ready after reinitialization attempt.")
            _isPlaying.set(false)
            return
        }

        try {
            track.play()
            // Larger write granularity reduces overhead and helps absorb bursty streams (e.g., Live API).
            // We still keep carry handling for leftover bytes < frameBytes.
            var frameBytes = frameBytes10ms() * 4 // ~40ms
            if (frameBytes <= 0) frameBytes = 480 // safety for 24kHz mono pcm16

            while (!shouldStop.get()) {
                val data = audioBuffer.poll() // O(1) operation

                if (data != null) {
                    processAudioChunk(data, frameBytes)
                    queuedBytes.addAndGet(-data.size)
                    memoryPool.release(data) // Return to pool
                } else {
                    if (isResponseDone.get()) break

                    // More efficient waiting using LockSupport
                    LockSupport.parkNanos(5_000_000) // 5ms in nanoseconds
                }
            }

            // Process any remaining carry buffer
            flushCarryBuffer()
            drainAudioTrack()

        } catch (e: Exception) {
            Log.e(TAG, "Optimized playback error", e)
        } finally {
            cleanup()
        }
    }

    /**
     * Process audio chunk with optimized memory management
     */
    private fun processAudioChunk(data: ByteArray, frameBytes: Int) {
        val toWrite = combineWithCarry(data)
        val needsRelease = (toWrite !== data) // If combined, we need to release it
        val len = toWrite.size
        var offset = 0

        val track = audioTrack ?: return

        // Write in aligned frames for optimal performance
        while (len - offset >= frameBytes && !shouldStop.get()) {
            val written = track.write(toWrite, offset, frameBytes, AudioTrack.WRITE_BLOCKING)
            if (written < 0) {
                // Brief park for audio underflow recovery
                LockSupport.parkNanos(2_000_000) // 2ms
            } else {
                offset += written
                totalFramesWritten.addAndGet((written / (bytesPerSample * channels)).toLong())
            }
        }

        // Handle remaining bytes efficiently
        updateCarryBuffer(toWrite, offset, len)

        // Release combined buffer back to pool if it was created
        if (needsRelease) {
            memoryPool.release(toWrite)
        }
    }

    /**
     * Efficient carry buffer management with clean buffer acquisition
     */
    private fun combineWithCarry(data: ByteArray): ByteArray {
        val carryLen = carryLength.get()
        if (carryLen == 0) {
            return data
        }

        val carry = carryBuffer ?: return data
        val combined = memoryPool.acquireClean(carryLen + data.size)
        System.arraycopy(carry, 0, combined, 0, carryLen)
        System.arraycopy(data, 0, combined, carryLen, data.size)

        return combined
    }

    private fun updateCarryBuffer(data: ByteArray, offset: Int, totalLen: Int) {
        val remaining = totalLen - offset
        if (remaining > 0) {
            val carry = carryBuffer
            if (carry == null || carry.size < remaining) {
                carryBuffer = ByteArray(remaining)
            }
            System.arraycopy(data, offset, carryBuffer!!, 0, remaining)
            carryLength.set(remaining)
        } else {
            carryLength.set(0)
        }
    }

    private fun resetCarryBuffer() {
        carryLength.set(0)
    }

    private fun flushCarryBuffer() {
        val carryLen = carryLength.get()
        val carry = carryBuffer
        val track = audioTrack
        if (carryLen > 0 && carry != null && track != null && !shouldStop.get()) {
            val written = track.write(carry, 0, carryLen, AudioTrack.WRITE_BLOCKING)
            if (written > 0) {
                totalFramesWritten.addAndGet((written / (bytesPerSample * channels)).toLong())
            }
            resetCarryBuffer()
        }
    }

    private fun drainAudioTrack() {
        try {
            val totalFrames = totalFramesWritten.get()
            val targetFrames = if (totalFrames > Int.MAX_VALUE) Int.MAX_VALUE else totalFrames.toInt()

            drainStartTime = SystemClock.elapsedRealtime()
            playbackCompleted.set(false)

            // Set notification marker for event-driven completion
            val track = audioTrack
            if (track != null && targetFrames > 0) {
                // AudioTrack marker is absolute (frames since track start), not relative to this response.
                // We therefore add the baseline captured at the response boundary.
                val absoluteMarker = responseStartFrames.toLong() + targetFrames.toLong()
                val markerFrames = when {
                    absoluteMarker <= 0L -> 0
                    absoluteMarker > Int.MAX_VALUE.toLong() -> Int.MAX_VALUE
                    else -> absoluteMarker.toInt()
                }
                if (markerFrames > 0) {
                    track.setNotificationMarkerPosition(markerFrames)
                    Log.d(TAG, "Set playback marker at frame $markerFrames (responseStart=$responseStartFrames, written=$targetFrames)")
                }
            }

            // Wait for completion callback with timeout
            val startWait = System.nanoTime()
            while (!playbackCompleted.get() && !shouldStop.get()) {
                LockSupport.parkNanos(2_000_000) // 2ms - just for timeout check

                // Safety timeout
                if ((System.nanoTime() - startWait) > 1_500_000_000L) { // 1.5 seconds
                    Log.w(TAG, "Drain timeout - forcing completion")
                    break
                }
            }

            audioTrack?.stop()
            val totalDrainTime = SystemClock.elapsedRealtime() - drainStartTime
            Log.d(TAG, "Total drain time: ${totalDrainTime}ms")
        } catch (e: Exception) {
            Log.w(TAG, "Drain wait interrupted", e)
        }
    }

    private fun cleanup() {
        _isPlaying.set(false)
        isResponseDone.set(false)

        // Properly clean audio buffer and return to pool
        cleanupAudioBuffer()
        resetCarryBuffer()
        totalFramesWritten.set(0)

        // Clean memory pool between responses
        memoryPool.cleanupBetweenResponses()

        listener?.onPlaybackFinished()
    }

    private fun frameBytes10ms(): Int {
        val samplesPer10ms = sampleRateHz / 100
        return samplesPer10ms * bytesPerSample * channels
    }

    private fun maxBufferedBytes(): Int {
        val bytesPerSecond = sampleRateHz * bytesPerSample * channels
        val bytes = (bytesPerSecond.toLong() * MAX_BUFFERED_AUDIO_MS.toLong()) / 1000L
        return when {
            bytes <= 0L -> 0
            bytes > Int.MAX_VALUE.toLong() -> Int.MAX_VALUE
            else -> bytes.toInt()
        }
    }

    /**
     * Enhanced memory pool for byte arrays with contamination prevention
     */
    private class ByteArrayPool {
        private val pool = ArrayBlockingQueue<ByteArray>(50)

        /**
         * Acquire a buffer and ensure it's clean (all zeros)
         */
        fun acquireClean(size: Int): ByteArray {
            // Always return an array with EXACT length 'size' to avoid writing stale tail bytes
            val array = pool.poll()
            if (array == null || array.size != size) {
                return ByteArray(size)
            }
            Arrays.fill(array, 0.toByte())
            return array
        }

        fun release(array: ByteArray?) {
            if (array == null) return
            // Only pool arrays of common sizes to improve reuse while avoiding size mismatches
            if (array.size >= 256) {
                pool.offer(array)
            }
        }

        /**
         * Clean up pool between responses to prevent audio contamination
         */
        fun cleanupBetweenResponses() {
            // Clear all pooled buffers to prevent contamination between responses
            var buffer: ByteArray?
            while (pool.poll().also { buffer = it } != null) {
                // Explicitly zero out buffer before discarding
                Arrays.fill(buffer!!, 0.toByte())
            }
            Log.d(TAG, "Memory pool cleaned between responses")
        }

        fun cleanup() {
            cleanupBetweenResponses()
        }
    }

}

