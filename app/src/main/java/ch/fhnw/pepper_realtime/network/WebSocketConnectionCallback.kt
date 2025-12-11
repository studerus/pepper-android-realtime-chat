package ch.fhnw.pepper_realtime.network

/**
 * Callback interface for WebSocket connection events
 */
interface WebSocketConnectionCallback {
    fun onSuccess()
    fun onError(error: Throwable)
}

