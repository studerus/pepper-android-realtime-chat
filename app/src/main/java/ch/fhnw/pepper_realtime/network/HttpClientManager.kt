package ch.fhnw.pepper_realtime.network

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Singleton HTTP Client Manager for optimized network performance
 *
 * Performance optimizations:
 * - Shared connection pool across all services
 * - HTTP/2 support for multiplexing
 * - Optimized timeouts and retry policies
 * - Connection keep-alive and reuse
 * - Reduced resource allocation and improved latency
 * - Coroutine-based suspend functions for async operations
 */
@Singleton
class HttpClientManager @Inject constructor() {

    companion object {
        private const val TAG = "OptimizedHttpClient"
        
        // Singleton instance for Java interop (legacy support)
        @Volatile
        private var instance: HttpClientManager? = null
        
        fun getInstance(): HttpClientManager {
            return instance ?: synchronized(this) {
                instance ?: HttpClientManager().also { instance = it }
            }
        }
    }

    // Shared clients for different use cases
    private val webSocketClient: OkHttpClient
    private val apiClient: OkHttpClient
    private val quickApiClient: OkHttpClient

    init {
        Log.i(TAG, "Initializing optimized HTTP client manager")

        // Shared connection pool for maximum efficiency
        val sharedPool = ConnectionPool(
            10,  // maxIdleConnections - keep more connections alive
            5,   // keepAliveDuration in minutes
            TimeUnit.MINUTES
        )

        // Optimized dispatcher for better concurrency
        val optimizedDispatcher = Dispatcher().apply {
            maxRequests = 64           // Increased from default 64
            maxRequestsPerHost = 8     // Increased from default 5
        }

        // WebSocket client - optimized for real-time communication
        webSocketClient = OkHttpClient.Builder()
            .connectionPool(sharedPool)
            .dispatcher(optimizedDispatcher)
            .retryOnConnectionFailure(true)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            // WebSocket optimized timeouts
            .connectTimeout(10, TimeUnit.SECONDS)    // Quick connection for real-time
            .readTimeout(0, TimeUnit.SECONDS)        // No read timeout for persistent connection
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)      // Keep-alive for WebSocket
            .build()

        // API client - optimized for HTTP API calls (Vision, Tools)
        apiClient = OkHttpClient.Builder()
            .connectionPool(sharedPool)
            .dispatcher(optimizedDispatcher)
            .retryOnConnectionFailure(true)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            // API optimized timeouts
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)       // Generous for image analysis
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)       // Total timeout for complex operations
            .build()

        // Quick API client - optimized for fast API calls (Weather, Search)
        quickApiClient = OkHttpClient.Builder()
            .connectionPool(sharedPool)
            .dispatcher(optimizedDispatcher)
            .retryOnConnectionFailure(true)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            // Quick API optimized timeouts
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        Log.i(TAG, "HTTP clients initialized with shared connection pool")
        
        // Set singleton instance for Java interop
        instance = this
    }

    /**
     * Get WebSocket client optimized for real-time communication
     */
    fun getWebSocketClient(): OkHttpClient = webSocketClient

    /**
     * Get API client optimized for longer operations (Vision analysis)
     */
    fun getApiClient(): OkHttpClient = apiClient

    /**
     * Get quick API client optimized for fast operations (Weather, Search)
     */
    fun getQuickApiClient(): OkHttpClient = quickApiClient

    // ==================== COROUTINE-BASED API ====================

    /**
     * Execute a request using the quick API client as a suspend function.
     */
    suspend fun executeQuickApiRequest(request: Request): Response {
        return quickApiClient.executeAsync(request)
    }

    /**
     * Extension function to convert OkHttp's callback-based API to a suspend function.
     */
    private suspend fun OkHttpClient.executeAsync(request: Request): Response {
        return suspendCancellableCoroutine { continuation ->
            val call = this.newCall(request)
            
            continuation.invokeOnCancellation {
                call.cancel()
            }
            
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) {
                        continuation.resume(response)
                    }
                }
            })
        }
    }

    // ==================== MONITORING & LIFECYCLE ====================

    /**
     * Get connection pool statistics for monitoring
     */
    @Suppress("unused") // May be used for debugging/monitoring
    fun getConnectionPoolStats(): String {
        val pool = apiClient.connectionPool
        return String.format(
            java.util.Locale.US,
            "Connections: %d idle, %d total",
            pool.idleConnectionCount(),
            pool.connectionCount()
        )
    }

}


