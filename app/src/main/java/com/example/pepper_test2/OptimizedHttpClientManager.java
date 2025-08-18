package com.example.pepper_test2;

import android.util.Log;

import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;

/**
 * Singleton HTTP Client Manager for optimized network performance
 * 
 * Performance optimizations:
 * - Shared connection pool across all services
 * - HTTP/2 support for multiplexing
 * - Optimized timeouts and retry policies
 * - Connection keep-alive and reuse
 * - Reduced resource allocation and improved latency
 */
public class OptimizedHttpClientManager {
    private static final String TAG = "OptimizedHttpClient";
    private static volatile OptimizedHttpClientManager instance;
    
    // Shared clients for different use cases
    private final OkHttpClient webSocketClient;
    private final OkHttpClient apiClient;
    private final OkHttpClient quickApiClient;
    
    private OptimizedHttpClientManager() {
        Log.i(TAG, "Initializing optimized HTTP client manager");
        
        // Shared connection pool for maximum efficiency
        ConnectionPool sharedPool = new ConnectionPool(
            10,  // maxIdleConnections - keep more connections alive
            5,   // keepAliveDuration in minutes
            TimeUnit.MINUTES
        );
        
        // Optimized dispatcher for better concurrency
        Dispatcher optimizedDispatcher = new Dispatcher();
        optimizedDispatcher.setMaxRequests(64);           // Increased from default 64
        optimizedDispatcher.setMaxRequestsPerHost(8);     // Increased from default 5
        
        // WebSocket client - optimized for real-time communication
        this.webSocketClient = new OkHttpClient.Builder()
                .connectionPool(sharedPool)
                .dispatcher(optimizedDispatcher)
                .retryOnConnectionFailure(true)
                .protocols(java.util.Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
                
                // WebSocket optimized timeouts
                .connectTimeout(10, TimeUnit.SECONDS)    // Quick connection for real-time
                .readTimeout(0, TimeUnit.SECONDS)        // No read timeout for persistent connection
                .writeTimeout(10, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)      // Keep-alive for WebSocket
                
                .build();
        
        // API client - optimized for HTTP API calls (Vision, Tools)
        this.apiClient = new OkHttpClient.Builder()
                .connectionPool(sharedPool)
                .dispatcher(optimizedDispatcher)
                .retryOnConnectionFailure(true)
                .protocols(java.util.Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
                
                // API optimized timeouts
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)       // Generous for image analysis
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(90, TimeUnit.SECONDS)       // Total timeout for complex operations
                
                .build();
        
        // Quick API client - optimized for fast API calls (Weather, Search)
        this.quickApiClient = new OkHttpClient.Builder()
                .connectionPool(sharedPool)
                .dispatcher(optimizedDispatcher)
                .retryOnConnectionFailure(true)
                .protocols(java.util.Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
                
                // Quick API optimized timeouts
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                
                .build();
                
        Log.i(TAG, "HTTP clients initialized with shared connection pool");
    }
    
    /**
     * Get singleton instance with lazy initialization
     */
    public static OptimizedHttpClientManager getInstance() {
        if (instance == null) {
            synchronized (OptimizedHttpClientManager.class) {
                if (instance == null) {
                    instance = new OptimizedHttpClientManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * Get WebSocket client optimized for real-time communication
     */
    public OkHttpClient getWebSocketClient() {
        return webSocketClient;
    }
    
    /**
     * Get API client optimized for longer operations (Vision analysis)
     */
    public OkHttpClient getApiClient() {
        return apiClient;
    }
    
    /**
     * Get quick API client optimized for fast operations (Weather, Search)
     */
    public OkHttpClient getQuickApiClient() {
        return quickApiClient;
    }
    
    /**
     * Get connection pool statistics for monitoring
     */
    @SuppressWarnings("unused") // May be used for debugging/monitoring
    public String getConnectionPoolStats() {
        ConnectionPool pool = apiClient.connectionPool();
        return String.format(java.util.Locale.US, "Connections: %d idle, %d total", 
            pool.idleConnectionCount(), pool.connectionCount());
    }
    
    /**
     * Cleanup resources - call on app shutdown
     */
    public void shutdown() {
        try {
            webSocketClient.dispatcher().executorService().shutdown();
            apiClient.dispatcher().executorService().shutdown();
            quickApiClient.dispatcher().executorService().shutdown();
            
            webSocketClient.connectionPool().evictAll();
            
            Log.i(TAG, "HTTP client manager shutdown completed");
        } catch (Exception e) {
            Log.w(TAG, "Error during HTTP client shutdown", e);
        }
    }
}
