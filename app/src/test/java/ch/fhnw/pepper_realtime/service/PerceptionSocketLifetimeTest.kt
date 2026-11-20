package ch.fhnw.pepper_realtime.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.*
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PerceptionSocketLifetimeTest {
    @Test fun `late callbacks cannot replace a new socket or stop its ping and reconnect`() = runTest {
        val sockets = mutableListOf<WebSocket>()
        val listeners = mutableListOf<WebSocketListener>()
        val factory = mock<WebSocket.Factory>()
        whenever(factory.newWebSocket(any(), any())).thenAnswer {
            listeners.add(it.getArgument(1))
            mock<WebSocket>().also(sockets::add)
        }
        val client = PerceptionWebSocketClient(factory, backgroundScope)
        client.connect()
        client.disconnect()
        client.connect()
        listeners[0].onOpen(sockets[0], mock())
        assertEquals(PerceptionWebSocketClient.ConnectionState.CONNECTING, client.connectionState.value)
        listeners[1].onOpen(sockets[1], mock())
        listeners[0].onClosed(sockets[0], 1000, "old")
        listeners[0].onFailure(sockets[0], IOException("old"), null)
        advanceTimeBy(30_001)
        assertEquals(2, sockets.size)
        assertEquals(PerceptionWebSocketClient.ConnectionState.CONNECTED, client.connectionState.value)
        verify(sockets[1]).send("""{"type":"ping"}""")
        listeners[1].onFailure(sockets[1], IOException("current"), null)
        advanceTimeBy(3001)
        assertEquals(3, sockets.size)
        client.disconnect()
    }
}
