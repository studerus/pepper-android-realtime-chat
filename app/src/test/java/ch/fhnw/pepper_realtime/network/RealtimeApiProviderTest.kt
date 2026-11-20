package ch.fhnw.pepper_realtime.network

import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimeApiProviderTest {

    @Test fun `Azure endpoint accepts host or secure base URL for GA and preview`() {
        val host = "example.openai.azure.com"
        for (input in listOf(host, "$host/", "https://$host/", "wss://$host", "  HTTPS://$host//  ")) {
            assertEquals("wss://$host/openai/v1/realtime?model=gpt-realtime",
                RealtimeApiProvider.AZURE_OPENAI.getWebSocketUrl(input, "gpt-realtime"))
            assertEquals("wss://$host/openai/realtime?api-version=2025-04-01-preview&deployment=gpt-4o-realtime-preview",
                RealtimeApiProvider.AZURE_OPENAI.getWebSocketUrl(input, "gpt-4o-realtime-preview"))
        }
    }

    @Test fun `short configured Azure speech regions win over stored values`() {
        fun region(config: String?, stored: String?) =
            ch.fhnw.pepper_realtime.manager.ApiKeyManager.resolveSpeechRegion(config, stored)
        assertEquals("eastus", region("eastus", "westeurope"))
        assertEquals("westeurope", region("westeurope", null))
        assertEquals("eastus2", region("your_region", "eastus2"))
        assertEquals("switzerlandnorth", region("", null))
    }

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
