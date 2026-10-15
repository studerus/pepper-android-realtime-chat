package ch.fhnw.pepper_realtime.network

import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimeApiProviderTest {

    @Test
    fun `xai explicit voice 3 and blank selection use voice 3`() {
        listOf(XAI_VOICE_3_MODEL, "", "  ").forEach { model ->
            assertEquals("wss://api.x.ai/v1/realtime?model=grok-voice-3.0",
                RealtimeApiProvider.XAI.getWebSocketUrl(null, model))
        }
        assertEquals(XAI_VOICE_3_MODEL, XAI_DEFAULT_MODEL)
    }

    @Test
    fun `xai saved think fast 2 selection remains unchanged`() {
        assertEquals("wss://api.x.ai/v1/realtime?model=grok-voice-think-fast-2.0",
            RealtimeApiProvider.XAI.getWebSocketUrl(null, XAI_THINK_FAST_2_MODEL))
    }

    @Test
    fun `xai websocket url defaults to voice 3 model`() {
        val url = RealtimeApiProvider.XAI.getWebSocketUrl(
            azureEndpoint = null,
            model = null
        )

        assertEquals(
            "wss://api.x.ai/v1/realtime?model=grok-voice-3.0",
            url
        )
    }

    @Test
    fun `xai legacy model label maps to voice 3 model`() {
        val url = RealtimeApiProvider.XAI.getWebSocketUrl(
            azureEndpoint = null,
            model = XAI_LEGACY_MODEL_LABEL
        )

        assertEquals(
            "wss://api.x.ai/v1/realtime?model=grok-voice-3.0",
            url
        )
    }

    @Test
    fun `xai think fast 1 model remains selectable`() {
        val url = RealtimeApiProvider.XAI.getWebSocketUrl(
            azureEndpoint = null,
            model = XAI_THINK_FAST_1_MODEL
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
