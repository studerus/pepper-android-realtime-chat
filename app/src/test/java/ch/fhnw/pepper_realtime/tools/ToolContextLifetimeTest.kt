package ch.fhnw.pepper_realtime.tools

import ch.fhnw.pepper_realtime.tools.interfaces.RealtimeMessageSender
import ch.fhnw.pepper_realtime.tools.interfaces.ToolHost
import okhttp3.Call
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class ToolContextLifetimeTest {
    @Test fun `cancelled media restores its mute state without restarting audio`() {
        val host = mock<ToolHost>()
        val base = ToolContext(host, null, mock(), mock(), mock(), mock(), mock(), mock(), mock(), null)
        val owner = OperationScope()
        val context = base.forOperation(owner)
        context.toolHost.muteMicrophone()
        owner.cancel()
        context.toolHost.unmuteMicrophone() // Late player-close callback.
        verify(host).resetMicrophoneMute()
        verify(host, never()).unmuteMicrophone()
    }

    @Test fun `captured context suppresses queued UI work and delayed messages`() {
        val host = mock<ToolHost>()
        val queue = mutableListOf<Runnable>()
        doAnswer { queue.add(it.getArgument(0)); null }.whenever(host).runOnUiThread(any())
        var sends = 0
        val sender = object : RealtimeMessageSender {
            override fun sendMessageToRealtimeAPI(text: String, requestResponse: Boolean, allowInterrupt: Boolean) { sends++ }
        }
        val base = ToolContext(host, null, mock(), mock(), mock(), mock(), mock(), mock(), mock(), sender)
        val owner = OperationScope()
        val captured = base.forOperation(owner)
        captured.toolHost.runOnUiThread { captured.toolHost.addImageMessage("old-photo") }
        owner.cancel()
        queue.forEach { it.run() }
        captured.sendAsyncUpdate("old-result", true)
        verify(host, never()).addImageMessage(any())
        assertEquals(0, sends)
        assertFalse(captured.isActive)
        assertTrue(base.isActive)
    }

    @Test fun `guard executes nullable-returning work only once`() {
        val base = ToolContext(mock(), null, mock(), mock(), mock(), mock(), mock(), mock(), mock(), null)
        val context = base.forOperation(OperationScope())
        var starts = 0
        context.guard { starts++; null }
        assertEquals(1, starts)
    }

    @Test fun `HTTP call is cancelled while its response is being consumed`() {
        val base = ToolContext(mock(), null, mock(), mock(), mock(), mock(), mock(), mock(), mock(), null)
        val owner = OperationScope()
        val context = base.forOperation(owner)
        val call = mock<Call>()
        val response = mock<Response>()
        whenever(call.execute()).thenReturn(response)
        context.withHttpCall(call) { owner.cancel() }
        verify(call, times(1)).cancel()
        verify(response).close()
    }
}
