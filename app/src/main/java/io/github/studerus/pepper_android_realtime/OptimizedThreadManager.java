package io.github.studerus.pepper_android_realtime;

import android.util.Log;

import java.util.concurrent.ExecutorService;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optimized Thread Manager for high-performance task distribution
 * 
 * Threading strategy:
 * - Dedicated threads for different task types to prevent blocking
 * - Priority-based task execution for responsive user experience
 * - Proper thread naming and priority assignment
 * - Efficient resource utilization with optimal pool sizes
 */
public class OptimizedThreadManager {
    private static final String TAG = "OptimizedThreadManager";
    private static volatile OptimizedThreadManager instance;
    
    // Specialized thread pools for different task categories
    private final ExecutorService networkExecutor;      // WebSocket, API calls
    private final ExecutorService audioExecutor;        // Speech recognition, audio processing
    private final ExecutorService computationExecutor;  // Tool execution, image processing
    private final ExecutorService ioExecutor;          // File operations, cleanup
    private final ExecutorService realtimeExecutor;    // High-priority real-time tasks
    
    private OptimizedThreadManager() {
        Log.i(TAG, "Initializing optimized thread manager");
        
        // Network Thread Pool - optimized for I/O bound operations
        this.networkExecutor = createThreadPool(
            "network",
            2,  // corePoolSize - allow concurrent WebSocket + API calls
            4,  // maximumPoolSize - scale up for multiple API calls
            30, // keepAliveTime in seconds
            Thread.NORM_PRIORITY
        );
        
        // Audio Thread Pool - dedicated for audio processing
        this.audioExecutor = createThreadPool(
            "audio",
            1,  // corePoolSize - single thread for sequential audio processing
            2,  // maximumPoolSize - allow one extra for speech recognition
            10, // keepAliveTime in seconds
            Thread.NORM_PRIORITY + 1  // Higher priority for audio responsiveness
        );
        
        // Computation Thread Pool - for CPU-intensive tasks  
        // Reduced for memory-constrained Pepper tablet (1GB RAM)
        int cpuCores = Runtime.getRuntime().availableProcessors();
        this.computationExecutor = createThreadPool(
            "computation",
            1,                            // Reduced core pool size to save memory
            Math.min(2, cpuCores),        // Limit max threads for 1GB RAM system
            60,                           // Longer keep-alive for batch processing
            Thread.NORM_PRIORITY
        );
        
        // I/O Thread Pool - for file operations and cleanup
        this.ioExecutor = createThreadPool(
            "io",
            1,  // corePoolSize - single thread for file operations
            2,  // maximumPoolSize - allow concurrent cleanup
            30, // keepAliveTime in seconds  
            Thread.NORM_PRIORITY - 1  // Lower priority for background tasks
        );
        
        // Real-time Thread Pool - highest priority for time-critical tasks
        this.realtimeExecutor = createThreadPool(
            "realtime",
            1,  // corePoolSize - dedicated thread
            1,  // maximumPoolSize - single thread for consistency
            5,  // Short keep-alive for responsiveness
            Thread.NORM_PRIORITY + 2  // Highest priority
        );
        
        Log.i(TAG, String.format("Thread pools initialized: Network(%d), Audio(%d), Compute(%d), IO(%d), Realtime(%d)", 
            2, 2, cpuCores, 2, 1));
    }
    
    /**
     * Create optimized thread pool with custom configuration
     */
    private ExecutorService createThreadPool(String name, int coreSize, int maxSize, 
                                           int keepAliveSeconds, int priority) {
        ThreadFactory factory = new OptimizedThreadFactory(name, priority);
        
        return new ThreadPoolExecutor(
            coreSize,
            maxSize,
            keepAliveSeconds,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(50), // Bounded queue to prevent memory issues
            factory,
            new ThreadPoolExecutor.CallerRunsPolicy() // Backpressure handling
        );
    }
    
    /**
     * Get singleton instance with lazy initialization
     */
    public static OptimizedThreadManager getInstance() {
        if (instance == null) {
            synchronized (OptimizedThreadManager.class) {
                if (instance == null) {
                    instance = new OptimizedThreadManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * Execute network-related tasks (WebSocket, API calls)
     */
    public void executeNetwork(Runnable task) {
        networkExecutor.execute(task);
    }
    
    /**
     * Execute audio-related tasks (speech recognition, audio processing)
     */
    public void executeAudio(Runnable task) {
        audioExecutor.execute(task);
    }
    
    /**
     * Execute computation-intensive tasks (tool execution, image processing)
     */
    public void executeComputation(Runnable task) {
        computationExecutor.execute(task);
    }
    
    /**
     * Execute I/O tasks (file operations, cleanup)
     */
    public void executeIO(Runnable task) {
        ioExecutor.execute(task);
    }
    
    /**
     * Execute real-time critical tasks (audio chunks, gesture updates)
     */
    @SuppressWarnings("unused")
    public void executeRealtime(Runnable task) {
        realtimeExecutor.execute(task);
    }
    
    /**
     * Get network executor for complex operations
     */
    @SuppressWarnings("unused")
    public ExecutorService getNetworkExecutor() {
        return networkExecutor;
    }
    
    /**
     * Get audio executor for speech operations
     */
    @SuppressWarnings("unused")
    public ExecutorService getAudioExecutor() {
        return audioExecutor;
    }
    
    /**
     * Get computation executor for CPU-intensive tasks
     */
    @SuppressWarnings("unused")
    public ExecutorService getComputationExecutor() {
        return computationExecutor;
    }
    
    /**
     * Get thread pool statistics for monitoring
     */
    @SuppressWarnings("unused")
    public String getThreadPoolStats() {
        return String.format(java.util.Locale.US,
            "ThreadPools - Network: %s, Audio: %s, Compute: %s, IO: %s, Realtime: %s",
            getPoolStats((ThreadPoolExecutor) networkExecutor),
            getPoolStats((ThreadPoolExecutor) audioExecutor),
            getPoolStats((ThreadPoolExecutor) computationExecutor),
            getPoolStats((ThreadPoolExecutor) ioExecutor),
            getPoolStats((ThreadPoolExecutor) realtimeExecutor)
        );
    }
    
    private String getPoolStats(ThreadPoolExecutor pool) {
        return String.format(java.util.Locale.US, "%d/%d", 
            pool.getActiveCount(), pool.getPoolSize());
    }
    
    /**
     * Shutdown all thread pools gracefully
     */
    public void shutdown() {
        Log.i(TAG, "Shutting down thread manager");
        
        shutdownExecutor(networkExecutor, "Network");
        shutdownExecutor(audioExecutor, "Audio");
        shutdownExecutor(computationExecutor, "Computation");
        shutdownExecutor(ioExecutor, "IO");
        shutdownExecutor(realtimeExecutor, "Realtime");
        
        Log.i(TAG, "Thread manager shutdown completed");
    }
    
    private void shutdownExecutor(ExecutorService executor, String name) {
        try {
            executor.shutdown();
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                Log.w(TAG, name + " executor did not terminate gracefully, forcing shutdown");
                executor.shutdownNow();
                if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                    Log.e(TAG, name + " executor did not terminate after forced shutdown");
                }
            } else {
                Log.i(TAG, name + " executor shutdown gracefully");
            }
        } catch (InterruptedException e) {
            Log.w(TAG, name + " executor shutdown interrupted, forcing shutdown");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Custom thread factory for proper thread naming and priority
     */
    private static class OptimizedThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;
        private final int priority;
        
        OptimizedThreadFactory(String poolName, int priority) {
            this.namePrefix = "OptThread-" + poolName + "-";
            this.priority = priority;
        }
        
        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, namePrefix + threadNumber.getAndIncrement());
            thread.setDaemon(false); // Keep app alive if needed
            thread.setPriority(priority);
            return thread;
        }
    }
}
