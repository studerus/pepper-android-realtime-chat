package io.github.anonymous.pepper_realtime.manager

import android.util.Log
import io.github.anonymous.pepper_realtime.di.ApplicationScope
import io.github.anonymous.pepper_realtime.di.DefaultDispatcher
import io.github.anonymous.pepper_realtime.di.IoDispatcher
import io.github.anonymous.pepper_realtime.di.MainDispatcher
import kotlinx.coroutines.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thread Manager bridging legacy Java code with modern Kotlin Coroutines.
 *
 * This class provides both:
 * 1. Legacy Runnable-based API for existing Java code (executeNetwork, executeIO, etc.)
 * 2. Modern Coroutine-based API for new Kotlin code (launchNetwork, launchIO, etc.)
 *
 * Migration strategy:
 * - New code should use the coroutine-based methods (launchNetwork, launchIO, etc.)
 * - Existing Java code can continue using executeNetwork, executeIO until migrated
 * - The coroutine dispatchers are injected via Hilt for testability
 */
@Singleton
class ThreadManager @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    @ApplicationScope private val applicationScope: CoroutineScope
) {
    companion object {
        private const val TAG = "ThreadManager"

        // Singleton instance for legacy Java interop
        @Volatile
        private var instance: ThreadManager? = null

        /**
         * Get singleton instance for legacy Java code.
         * New Kotlin code should use Hilt injection instead.
         */
        @JvmStatic
        fun getInstance(): ThreadManager {
            return instance ?: throw IllegalStateException(
                "ThreadManager not initialized. Use Hilt injection or call from Activity/Fragment context."
            )
        }
    }

    // Legacy thread pools for backward compatibility with Java code
    private val networkExecutor: ExecutorService
    private val audioExecutor: ExecutorService
    private val computationExecutor: ExecutorService
    private val ioExecutor: ExecutorService
    private val realtimeExecutor: ExecutorService

    init {
        Log.i(TAG, "Initializing ThreadManager with Coroutine support")

        // Network Thread Pool - optimized for I/O bound operations
        networkExecutor = createThreadPool(
            name = "network",
            coreSize = 2,
            maxSize = 4,
            keepAliveSeconds = 30,
            priority = Thread.NORM_PRIORITY
        )

        // Audio Thread Pool - dedicated for audio processing
        audioExecutor = createThreadPool(
            name = "audio",
            coreSize = 1,
            maxSize = 2,
            keepAliveSeconds = 10,
            priority = Thread.NORM_PRIORITY + 1
        )

        // Computation Thread Pool - for CPU-intensive tasks
        val cpuCores = Runtime.getRuntime().availableProcessors()
        computationExecutor = createThreadPool(
            name = "computation",
            coreSize = 1,
            maxSize = minOf(2, cpuCores),
            keepAliveSeconds = 60,
            priority = Thread.NORM_PRIORITY
        )

        // I/O Thread Pool - for file operations and cleanup
        ioExecutor = createThreadPool(
            name = "io",
            coreSize = 1,
            maxSize = 2,
            keepAliveSeconds = 30,
            priority = Thread.NORM_PRIORITY - 1
        )

        // Real-time Thread Pool - highest priority for time-critical tasks
        realtimeExecutor = createThreadPool(
            name = "realtime",
            coreSize = 1,
            maxSize = 1,
            keepAliveSeconds = 5,
            priority = Thread.NORM_PRIORITY + 2
        )

        Log.i(TAG, "Thread pools initialized: Network(2), Audio(2), Compute($cpuCores), IO(2), Realtime(1)")

        // Set singleton instance for Java interop
        instance = this
    }

    private fun createThreadPool(
        name: String,
        coreSize: Int,
        maxSize: Int,
        keepAliveSeconds: Int,
        priority: Int
    ): ExecutorService {
        val factory = OptimizedThreadFactory(name, priority)

        return ThreadPoolExecutor(
            coreSize,
            maxSize,
            keepAliveSeconds.toLong(),
            TimeUnit.SECONDS,
            LinkedBlockingQueue(50),
            factory,
            ThreadPoolExecutor.DiscardOldestPolicy()
        )
    }

    // ==================== COROUTINE-BASED API (PREFERRED) ====================

    /**
     * Launch a network operation using IO dispatcher.
     * Preferred for new Kotlin code.
     */
    fun launchNetwork(block: suspend CoroutineScope.() -> Unit): Job {
        return applicationScope.launch(ioDispatcher) { block() }
    }

    /**
     * Launch an I/O operation (file, database) using IO dispatcher.
     */
    fun launchIO(block: suspend CoroutineScope.() -> Unit): Job {
        return applicationScope.launch(ioDispatcher) { block() }
    }

    /**
     * Launch a CPU-intensive computation using Default dispatcher.
     */
    fun launchComputation(block: suspend CoroutineScope.() -> Unit): Job {
        return applicationScope.launch(defaultDispatcher) { block() }
    }

    /**
     * Launch a UI update on the Main dispatcher.
     */
    fun launchMain(block: suspend CoroutineScope.() -> Unit): Job {
        return applicationScope.launch(mainDispatcher) { block() }
    }

    /**
     * Run a suspend function on IO dispatcher and return result.
     */
    suspend fun <T> withIO(block: suspend CoroutineScope.() -> T): T {
        return withContext(ioDispatcher) { block() }
    }

    /**
     * Run a suspend function on Default dispatcher and return result.
     */
    suspend fun <T> withComputation(block: suspend CoroutineScope.() -> T): T {
        return withContext(defaultDispatcher) { block() }
    }

    /**
     * Run a suspend function on Main dispatcher and return result.
     */
    suspend fun <T> withMain(block: suspend CoroutineScope.() -> T): T {
        return withContext(mainDispatcher) { block() }
    }

    // ==================== LEGACY RUNNABLE-BASED API ====================

    /**
     * Execute network-related tasks (WebSocket, API calls)
     * @deprecated Use launchNetwork for new Kotlin code
     */
    fun executeNetwork(task: Runnable) {
        networkExecutor.execute(task)
    }

    /**
     * Execute audio-related tasks (speech recognition, audio processing)
     * @deprecated Use launchIO for new Kotlin code
     */
    fun executeAudio(task: Runnable) {
        audioExecutor.execute(task)
    }

    /**
     * Execute computation-intensive tasks (tool execution, image processing)
     * @deprecated Use launchComputation for new Kotlin code
     */
    fun executeComputation(task: Runnable) {
        computationExecutor.execute(task)
    }

    /**
     * Execute I/O tasks (file operations, cleanup)
     * @deprecated Use launchIO for new Kotlin code
     */
    fun executeIO(task: Runnable) {
        ioExecutor.execute(task)
    }

    /**
     * Execute real-time critical tasks (audio chunks, gesture updates)
     * @deprecated Use launchIO with appropriate priority for new Kotlin code
     */
    @Suppress("unused")
    fun executeRealtime(task: Runnable) {
        realtimeExecutor.execute(task)
    }

    /**
     * Get network executor for complex operations
     */
    @Suppress("unused")
    fun getNetworkExecutor(): ExecutorService = networkExecutor

    /**
     * Get audio executor for speech operations
     */
    @Suppress("unused")
    fun getAudioExecutor(): ExecutorService = audioExecutor

    /**
     * Get computation executor for CPU-intensive tasks
     */
    @Suppress("unused")
    fun getComputationExecutor(): ExecutorService = computationExecutor

    /**
     * Get thread pool statistics for monitoring
     */
    @Suppress("unused")
    fun getThreadPoolStats(): String {
        return String.format(
            java.util.Locale.US,
            "ThreadPools - Network: %s, Audio: %s, Compute: %s, IO: %s, Realtime: %s",
            getPoolStats(networkExecutor as ThreadPoolExecutor),
            getPoolStats(audioExecutor as ThreadPoolExecutor),
            getPoolStats(computationExecutor as ThreadPoolExecutor),
            getPoolStats(ioExecutor as ThreadPoolExecutor),
            getPoolStats(realtimeExecutor as ThreadPoolExecutor)
        )
    }

    private fun getPoolStats(pool: ThreadPoolExecutor): String {
        return String.format(java.util.Locale.US, "%d/%d", pool.activeCount, pool.poolSize)
    }

    /**
     * Shutdown all thread pools gracefully
     */
    fun shutdown() {
        try {
            Log.i(TAG, "Shutting down thread manager")

            // Cancel all coroutines in application scope
            applicationScope.cancel("ThreadManager shutdown")

            // Shutdown legacy executors
            shutdownExecutor(networkExecutor, "Network")
            shutdownExecutor(audioExecutor, "Audio")
            shutdownExecutor(computationExecutor, "Computation")
            shutdownExecutor(ioExecutor, "IO")
            shutdownExecutor(realtimeExecutor, "Realtime")

            Log.i(TAG, "Thread manager shutdown completed")
        } finally {
            synchronized(ThreadManager::class.java) {
                instance = null
                Log.i(TAG, "Thread manager instance reset for clean restart")
            }
        }
    }

    private fun shutdownExecutor(executor: ExecutorService, name: String) {
        try {
            executor.shutdown()
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                Log.w(TAG, "$name executor did not terminate gracefully, forcing shutdown")
                executor.shutdownNow()
                if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                    Log.e(TAG, "$name executor did not terminate after forced shutdown")
                }
            } else {
                Log.i(TAG, "$name executor shutdown gracefully")
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "$name executor shutdown interrupted, forcing shutdown")
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Custom thread factory for proper thread naming and priority
     */
    private class OptimizedThreadFactory(
        poolName: String,
        private val priority: Int
    ) : ThreadFactory {
        private val threadNumber = AtomicInteger(1)
        private val namePrefix = "OptThread-$poolName-"

        override fun newThread(r: Runnable): Thread {
            return Thread(r, namePrefix + threadNumber.getAndIncrement()).apply {
                isDaemon = false
                this.priority = this@OptimizedThreadFactory.priority
            }
        }
    }
}

