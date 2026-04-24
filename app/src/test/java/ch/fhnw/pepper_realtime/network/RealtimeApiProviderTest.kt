package ch.fhnw.pepper_realtime.network

import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimeApiProviderTest {

    @Test
    fun `xai websocket url includes think fast model`() {
        val url = RealtimeApiProvider.XAI.getWebSocketUrl(
            azureEndpoint = null,
            model = XAI_THINK_FAST_MODEL
        )

        assertEquals(
            "wss://api.x.ai/v1/realtime?model=grok-voice-think-fast-1.0",
            url
        )
    }

    @Test
    fun `xai legacy model label maps to think fast model`() {
        val url = RealtimeApiProvider.XAI.getWebSocketUrl(
            azureEndpoint = null,
            model = XAI_LEGACY_MODEL_LABEL
        )

        assertEquals(
            "wss://api.x.ai/v1/realtime?model=grok-voice-think-fast-1.0",
            url
        )
    }

    @Test
    fun `xai old fast model remains selectable`() {
        val url = RealtimeApiProvider.XAI.getWebSocketUrl(
            azureEndpoint = null,
            model = XAI_FAST_MODEL
        )

        assertEquals(
            "wss://api.x.ai/v1/realtime?model=grok-voice-fast-1.0",
            url
        )
    }
}
