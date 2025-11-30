package io.github.anonymous.pepper_realtime.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for RealtimeSessionManager.
 * 
 * Note: These tests focus on the logic that can be tested without Android dependencies.
 * The WebSocket connection tests require Android instrumentation tests.
 */
class RealtimeSessionManagerTest {

    private lateinit var sessionManager: RealtimeSessionManager

    @Before
    fun setUp() {
        sessionManager = RealtimeSessionManager()
    }

    // ========== Connection State Tests ==========

    @Test
    fun `isConnected returns false when not connected`() {
        assertFalse(sessionManager.isConnected)
    }

    @Test
    fun `send returns false when not connected`() {
        val result = sessionManager.send("test message")
        assertFalse(result)
    }

    @Test
    fun `sendAudioChunk returns false when not connected`() {
        val result = sessionManager.sendAudioChunk("base64AudioData")
        assertFalse(result)
    }

    @Test
    fun `sendUserTextMessage returns false when not connected`() {
        val result = sessionManager.sendUserTextMessage("Hello")
        assertFalse(result)
    }

    @Test
    fun `sendToolResult returns false when not connected`() {
        val result = sessionManager.sendToolResult("call_123", """{"result": "ok"}""")
        assertFalse(result)
    }

    @Test
    fun `requestResponse returns false when not connected`() {
        val result = sessionManager.requestResponse()
        assertFalse(result)
    }

    // ========== Audio Chunk Tests ==========

    @Test
    fun `sendAudioChunk handles empty audio data without crash`() {
        val result = sessionManager.sendAudioChunk("")
        assertFalse(result) // Not connected, but shouldn't crash
    }

    @Test
    fun `sendAudioChunk handles large audio data without crash`() {
        // Simulate ~100ms of audio at 24kHz = 4800 bytes → ~6400 chars Base64
        val largeBase64 = "A".repeat(6400)
        
        val result = sessionManager.sendAudioChunk(largeBase64)
        assertFalse(result) // Not connected, but shouldn't crash
    }

    @Test
    fun `sendAudioChunk can be called repeatedly without issues`() {
        // Simulate 1 second of audio streaming (10 chunks at 100ms each)
        val testAudio = "SGVsbG9Xb3JsZA=="
        
        repeat(10) {
            sessionManager.sendAudioChunk(testAudio)
        }
        
        // If StringBuilder reuse works correctly, this should not cause issues
        // No assertion needed - test passes if no exception/OutOfMemoryError
    }

    // ========== User Message Tests ==========

    @Test
    fun `sendUserTextMessage handles special characters without crash`() {
        val result = sessionManager.sendUserTextMessage("Hello \"world\" with 'quotes'")
        assertFalse(result)
    }

    @Test
    fun `sendUserTextMessage handles unicode without crash`() {
        val result = sessionManager.sendUserTextMessage("Hallo 你好 مرحبا 🤖")
        assertFalse(result) // Not connected
    }

    // ========== Image Message Tests ==========

    @Test
    fun `sendUserImageMessage returns false when not connected`() {
        val base64Image = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        
        val result = sessionManager.sendUserImageMessage(base64Image, "image/png")
        assertFalse(result)
    }

    @Test
    fun `sendUserImageMessage handles null mime type without crash`() {
        val result = sessionManager.sendUserImageMessage("base64data", null)
        assertFalse(result)
    }

    @Test
    fun `sendUserImageMessage handles empty mime type without crash`() {
        val result = sessionManager.sendUserImageMessage("base64data", "")
        assertFalse(result)
    }

    // ========== Tool Result Tests ==========

    @Test
    fun `sendToolResult handles complex JSON without crash`() {
        val complexResult = """{"locations": ["Kitchen", "Living Room"], "count": 2, "success": true}"""
        
        val result = sessionManager.sendToolResult("call_abc123", complexResult)
        assertFalse(result)
    }

    // ========== Latency Measurement Tests ==========

    @Test
    fun `responseCreateTimestamp is updated on requestResponse`() {
        val beforeTimestamp = System.currentTimeMillis()
        
        sessionManager.requestResponse()
        
        val afterTimestamp = System.currentTimeMillis()
        
        // Timestamp should be set between before and after
        assertTrue(RealtimeSessionManager.responseCreateTimestamp >= beforeTimestamp)
        assertTrue(RealtimeSessionManager.responseCreateTimestamp <= afterTimestamp)
    }

    // ========== Close Tests ==========

    @Test
    fun `close does not throw when not connected`() {
        // Should not throw exception
        sessionManager.close(1000, "Test close")
        
        assertFalse(sessionManager.isConnected)
    }

    @Test
    fun `close with null reason does not throw`() {
        sessionManager.close(1000, null)
        
        assertFalse(sessionManager.isConnected)
    }

    // ========== Session Configuration Tests ==========

    @Test
    fun `configureInitialSession calls callback with failure when not connected`() {
        var callbackResult: Boolean? = null
        var callbackError: String? = null
        
        sessionManager.setSessionConfigCallback { success, error ->
            callbackResult = success
            callbackError = error
        }
        
        sessionManager.configureInitialSession()
        
        assertEquals(false, callbackResult)
        assertEquals("Not connected", callbackError)
    }

    @Test
    fun `updateSession does not throw when not connected`() {
        // Should not throw exception, just log warning
        sessionManager.updateSession()
    }

    @Test
    fun `setSessionConfigCallback accepts null`() {
        sessionManager.setSessionConfigCallback(null)
        // Should not throw
        sessionManager.configureInitialSession()
    }

    // ========== Listener Tests ==========

    @Test
    fun `listener can be set to null`() {
        sessionManager.listener = null
        // Should not throw
    }
}
