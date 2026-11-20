package ch.fhnw.pepper_realtime.network

import ch.fhnw.pepper_realtime.manager.ApiKeyManager
import ch.fhnw.pepper_realtime.manager.SettingsRepository
import ch.fhnw.pepper_realtime.tools.ToolContext
import ch.fhnw.pepper_realtime.tools.ToolRegistry
import com.google.gson.Gson
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class SessionSetupTest {
    private class Fixture(provider: RealtimeApiProvider = RealtimeApiProvider.OPENAI_DIRECT) {
        val factory = mock<WebSocket.Factory>()
        val settings = mock<SettingsRepository>()
        val manager = RealtimeSessionManager(factory)
        val callbacks = mutableListOf<Pair<Boolean, String?>>()
        val sockets = mutableListOf<WebSocket>()
        val listeners = mutableListOf<WebSocketListener>()
        val messages = mutableListOf<JSONObject>()
        val socket get() = sockets.last()
        val transport get() = listeners.last()
        val listener = mock<RealtimeSessionManager.Listener>()

        init {
            whenever(settings.apiProviderEnum).thenReturn(provider)
            whenever(settings.model).thenReturn(if (provider.isGoogleProvider()) "models/gemini-3.1-flash-live-preview" else "gpt-realtime")
            whenever(settings.voice).thenReturn("ara")
            whenever(settings.systemPrompt).thenReturn("Test instructions")
            whenever(settings.preferredConversationLanguage).thenReturn("en")
            whenever(settings.enabledTools).thenReturn(emptySet())
            val registry = mock<ToolRegistry>()
            whenever(registry.buildToolsDefinitionForAzure(any(), any())).thenReturn(JSONArray())
            manager.setSessionDependencies(registry, mock<ToolContext>(), settings, mock<ApiKeyManager>())
            manager.setSessionConfigCallback { ok, error -> callbacks.add(ok to error) }
            manager.listener = listener
            whenever(factory.newWebSocket(any(), any())).thenAnswer {
                listeners.add(it.getArgument(1))
                mock<WebSocket>().also { ws ->
                    whenever(ws.send(any<String>())).thenAnswer { call -> messages.add(JSONObject(call.getArgument<String>(0))); true }
                    sockets.add(ws)
                }
            }
        }

        fun connect() = manager.connect("https://example.invalid/realtime", null)
        fun open() {
            transport.onOpen(socket, mock<Response>())
            manager.configureInitialSession()
        }
        fun setup() { connect(); open() }
        fun error(eventId: String? = null) = RealtimeEvents.ErrorEvent().apply {
            error = RealtimeEvents.Error().apply { message = "Invalid setup"; this.eventId = eventId }
        }
    }

    @Test fun `OpenAI Azure and xai wait for server acknowledgement before allowing traffic`() {
        for (provider in listOf(RealtimeApiProvider.OPENAI_DIRECT, RealtimeApiProvider.AZURE_OPENAI, RealtimeApiProvider.XAI)) {
            val f = Fixture(provider)
            f.setup()
            assertEquals(RealtimeSessionManager.ConnectionState.CONFIGURING, f.manager.currentConnectionState)
            assertFalse(f.manager.isConnected)
            assertTrue(f.callbacks.isEmpty())
            assertFalse(f.manager.sendUserTextMessage("Too early"))
            assertFalse(f.manager.sendAudioChunk("audio"))
            assertFalse(f.manager.requestResponse())
            assertEquals(1, f.messages.size)
            assertTrue(f.messages.single().getString("event_id").startsWith("setup_"))
            f.manager.onGoogleSetupComplete() // Wrong provider's acknowledgement does not count.
            assertFalse(f.manager.isConnected)
            f.manager.onSessionUpdated()
            assertTrue(f.manager.isConnected)
            assertEquals(listOf(true to null), f.callbacks)
            assertTrue(f.manager.sendUserTextMessage("Ready"))
            f.manager.onSessionUpdated()
            assertEquals(1, f.callbacks.size)
        }
    }

    @Test fun `Google waits for setupComplete and completes only once`() {
        val f = Fixture(RealtimeApiProvider.GOOGLE_GEMINI)
        f.setup()
        assertFalse(f.manager.isConnected)
        f.manager.onSessionUpdated()
        assertTrue(f.callbacks.isEmpty())
        f.manager.onGoogleSetupComplete()
        f.manager.onGoogleSetupComplete()
        assertTrue(f.manager.isConnected)
        assertEquals(listOf(true to null), f.callbacks)
    }

    @Test fun `disabling the idle timeout omits it from server VAD configuration`() {
        for (timeout in listOf(9000, null)) {
            val f = Fixture()
            whenever(f.settings.isUsingRealtimeAudioInput).thenReturn(true)
            whenever(f.settings.turnDetectionType).thenReturn("server_vad")
            whenever(f.settings.transcriptionModel).thenReturn("whisper-1")
            whenever(f.settings.noiseReduction).thenReturn("off")
            whenever(f.settings.idleTimeout).thenReturn(timeout)
            f.setup()
            val vad = f.messages.single().getJSONObject("session").getJSONObject("audio")
                .getJSONObject("input").getJSONObject("turn_detection")
            if (timeout == null) assertFalse(vad.has("idle_timeout_ms")) else assertEquals(timeout, vad.getInt("idle_timeout_ms"))
        }
    }

    @Test fun `mic connection signal stays off until setup and clears on transport failure`() {
        val f = Fixture()
        f.setup()
        assertFalse(f.manager.connectedState.value)
        f.manager.onSessionUpdated()
        assertTrue(f.manager.connectedState.value)
        f.transport.onFailure(f.socket, java.io.IOException("Disconnected"), null)
        assertFalse(f.manager.connectedState.value)
    }

    @Test fun `setup rejection matches nested client event id and closes the socket`() {
        val f = Fixture()
        f.setup()
        val id = f.messages.single().getString("event_id")
        val error = Gson().fromJson("""{"type":"error","event_id":"server-id","error":{"event_id":"$id","message":"Invalid setup"}}""", RealtimeEvents.ErrorEvent::class.java)
        assertFalse(f.manager.onSessionError(f.error("unrelated-client-event")))
        assertTrue(f.callbacks.isEmpty())
        assertTrue(f.manager.onSessionError(error))
        verify(f.socket).cancel()
        assertFalse(f.manager.isConnected)
        f.manager.onSessionUpdated()
        assertFalse(f.manager.onSessionError(error))
        assertEquals(listOf(false to "Invalid setup"), f.callbacks)
    }

    @Test fun `uncorrelated provider setup error also fails while setup is pending`() {
        val f = Fixture(RealtimeApiProvider.GOOGLE_GEMINI)
        f.setup()
        assertTrue(f.manager.onSessionError(f.error()))
        assertEquals(listOf(false to "Invalid setup"), f.callbacks)
        assertFalse(f.manager.isConnected)
    }

    @Test fun `failed setup send cleans up without reporting success`() {
        val f = Fixture()
        f.connect()
        whenever(f.socket.send(any<String>())).thenReturn(false)
        f.open()
        assertEquals(false, f.callbacks.single().first)
        assertEquals(RealtimeSessionManager.ConnectionState.DISCONNECTED, f.manager.currentConnectionState)
        verify(f.socket).cancel()
    }

    @Test fun `settings edits during setup cannot acknowledge the initial configuration`() {
        val f = Fixture()
        f.setup()
        whenever(f.settings.systemPrompt).thenReturn("Changed while connecting")
        f.manager.updateSession()
        f.manager.configureInitialSession()
        assertEquals(1, f.messages.size)
        f.manager.onSessionUpdated()
        assertEquals(2, f.messages.size)
        assertTrue(f.messages.last().getJSONObject("session").getString("instructions").startsWith("Changed while connecting"))
        f.manager.onSessionUpdated()
        assertEquals(1, f.callbacks.size)
    }

    @Test fun `close invalidates setup and late socket callbacks cannot revive it or a replacement`() {
        val f = Fixture()
        f.setup()
        val oldTransport = f.transport
        val oldSocket = f.socket
        f.manager.close(1000, "End chat")
        f.manager.onSessionUpdated()
        assertTrue(f.callbacks.isEmpty())
        f.connect()
        oldTransport.onOpen(oldSocket, mock<Response>())
        oldTransport.onMessage(oldSocket, "old message")
        oldTransport.onFailure(oldSocket, IllegalStateException("old failure"), null)
        oldTransport.onClosed(oldSocket, 1000, "old close")
        verify(f.listener, never()).onTextMessage(any())
        verify(f.listener, never()).onFailure(any(), anyOrNull())
        assertEquals(RealtimeSessionManager.ConnectionState.CONNECTING, f.manager.currentConnectionState)
        f.open()
        f.manager.onSessionUpdated()
        assertEquals(listOf(true to null), f.callbacks)
    }

    @Test fun `synchronous socket creation failure leaves manager reusable`() {
        val f = Fixture()
        whenever(f.factory.newWebSocket(any(), any())).thenThrow(IllegalStateException("Cannot create socket"))
        assertThrows(IllegalStateException::class.java) { f.connect() }
        assertEquals(RealtimeSessionManager.ConnectionState.DISCONNECTED, f.manager.currentConnectionState)
    }
}
