package ch.fhnw.pepper_realtime.controller

import ch.fhnw.pepper_realtime.manager.AudioPlayer
import ch.fhnw.pepper_realtime.manager.SettingsRepository
import ch.fhnw.pepper_realtime.network.GoogleLiveEvents
import ch.fhnw.pepper_realtime.network.RealtimeEvents
import ch.fhnw.pepper_realtime.network.RealtimeSessionManager
import ch.fhnw.pepper_realtime.tools.*
import ch.fhnw.pepper_realtime.ui.ChatMessage
import ch.fhnw.pepper_realtime.ui.ChatViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class ChatToolLifetimeTest {
    private class Fixture(scope: CoroutineScope, dispatcher: CoroutineDispatcher) {
        val registry = ChatWorkRegistry().apply { begin() }
        val tools = mock<ToolRegistry>()
        val context = mock<ToolContext>()
        val callContext = mock<ToolContext>()
        val viewModel = mock<ChatViewModel>()
        val network = mock<RealtimeSessionManager>()
        val controller = mock<ChatSessionController>()
        val ui = mutableListOf<() -> Unit>()
        val handler = ChatRealtimeHandler(viewModel, mock<AudioPlayer>(), null, dispatcher, scope,
            tools, context, network, mock<SettingsRepository>(), registry) { ui.add(it) }
        init {
            whenever(context.forOperation(any())).thenReturn(callContext)
            handler.sessionController = controller
        }
        fun openAi(id: String = "call", name: String = "test_tool") {
            handler.onResponseDone(RealtimeEvents.ResponseDone().apply {
                response = RealtimeEvents.Response().apply {
                    output = listOf(RealtimeEvents.Item().apply {
                        type = "function_call"; callId = id; this.name = name; arguments = "{}"
                    })
                }
            })
        }
    }

    @Test fun `queued result cannot update the next chat`() = runTest {
        val f = Fixture(this, StandardTestDispatcher(testScheduler))
        whenever(f.tools.executeTool(any(), any(), any())).thenReturn("done")
        f.openAi()
        runCurrent()
        assertEquals(1, f.ui.size)
        f.registry.begin()
        f.ui.forEach { it() }
        verify(f.viewModel, never()).updateFunctionCallResult(any(), any())
        verify(f.tools).executeTool(eq("test_tool"), any(), same(f.callContext))
    }

    @Test fun `results update their own cards even with multiple calls`() = runTest {
        val f = Fixture(this, StandardTestDispatcher(testScheduler))
        whenever(f.tools.executeTool(eq("first"), any(), any())).thenReturn("first-result")
        whenever(f.tools.executeTool(eq("second"), any(), any())).thenReturn("second-result")
        f.openAi("one", "first")
        f.openAi("two", "second")
        runCurrent()
        val cards = argumentCaptor<ChatMessage>()
        verify(f.viewModel, times(2)).addMessage(cards.capture())
        f.ui.reversed().forEach { it() }
        verify(f.viewModel).updateFunctionCallResult(cards.allValues[0].uuid, "first-result")
        verify(f.viewModel).updateFunctionCallResult(cards.allValues[1].uuid, "second-result")
    }

    @Test fun `google cancellation stops queued execution`() = runTest {
        val f = Fixture(this, StandardTestDispatcher(testScheduler))
        f.handler.onGoogleToolCall(listOf(GoogleLiveEvents.FunctionCall("call", "test_tool")))
        f.handler.onGoogleToolCallCancellation(listOf("call"))
        runCurrent()
        verify(f.tools, never()).executeTool(any(), any(), any())
        verify(f.network, never()).sendGoogleToolResult(any(), any(), any(), anyOrNull(), anyOrNull())
    }

    @Test fun `noncooperative old tool cannot send a result after reconnection`() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val f = Fixture(parent, Dispatchers.IO)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        whenever(f.tools.executeTool(any(), any(), any())).thenAnswer {
            started.countDown()
            try {
                while (release.count > 0) {
                    try { release.await() } catch (_: InterruptedException) { /* Deliberately ignores cancellation. */ }
                }
                "old-result"
            } finally { returned.countDown() }
        }
        try {
            f.openAi()
            assertTrue(started.await(3, TimeUnit.SECONDS))
            f.registry.begin()
            release.countDown()
            assertTrue(returned.await(3, TimeUnit.SECONDS))
            withTimeout(3000) { parent.coroutineContext.job.children.toList().joinAll() }
            verify(f.controller, never()).sendToolResult(any(), any(), anyOrNull(), any())
            assertTrue(f.ui.isEmpty())
            assertNotNull(f.registry.current!!.beginCall("call"))
        } finally { release.countDown(); parent.cancel() }
    }
}
