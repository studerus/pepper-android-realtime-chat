package ch.fhnw.pepper_realtime.controller

import android.app.Application
import ch.fhnw.pepper_realtime.manager.*
import ch.fhnw.pepper_realtime.network.*
import ch.fhnw.pepper_realtime.tools.ChatWorkRegistry
import ch.fhnw.pepper_realtime.tools.OperationScope
import ch.fhnw.pepper_realtime.ui.ChatViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatMessageSchedulingTest {
    private class Fixture(scope: TestScope, provider: RealtimeApiProvider = RealtimeApiProvider.OPENAI_DIRECT) {
        val generating = MutableStateFlow(false)
        val playing = MutableStateFlow(false)
        val viewModel = mock<ChatViewModel>()
        val network = mock<RealtimeSessionManager>()
        val player = mock<AudioPlayer>()
        val interrupt = mock<ChatInterruptController>()
        val settings = mock<SettingsRepository>()
        val registry = ChatWorkRegistry().apply { begin() }
        val turn = TurnManager(null)
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val handler = ChatRealtimeHandler(viewModel, player, turn, dispatcher, scope,
            mock(), null, network, settings, registry) { it() }
        val events = RealtimeEventHandler(handler)
        val controller: ChatSessionController

        init {
            whenever(viewModel.isResponseGenerating).thenReturn(generating)
            whenever(viewModel.isAudioPlaying).thenReturn(playing)
            doAnswer { generating.value = it.getArgument(0); null }.whenever(viewModel).setResponseGenerating(any())
            doAnswer { playing.value = it.getArgument(0); null }.whenever(viewModel).setAudioPlaying(any())
            whenever(player.isPlaying()).thenAnswer { playing.value }
            whenever(settings.apiProviderEnum).thenReturn(provider)
            whenever(network.isConnected).thenReturn(true)
            whenever(network.sendUserTextMessage(any())).thenReturn(true)
            whenever(network.sendGoogleTextMessage(any(), any())).thenReturn(true)
            whenever(network.requestResponse()).thenReturn(true)
            val app = mock<Application>()
            whenever(viewModel.getApplication<Application>()).thenReturn(app)
            whenever(app.getString(any())).thenReturn("message")
            doAnswer { generating.value = false; playing.value = false; null }.whenever(interrupt).interruptSpeech()
            controller = ChatSessionController(viewModel, network, settings, mock(), mock(), dispatcher,
                scope, mock(), turn, interrupt, player, events, mock(), registry, mock(), mock())
            handler.sessionController = controller
            events.isGoogleProvider = provider.isGoogleProvider()
        }

        fun speaking(stillGenerating: Boolean) {
            playing.value = true
            generating.value = stillGenerating
            turn.setState(TurnManager.State.SPEAKING)
        }

        fun send(text: String = "event", respond: Boolean = true, allowInterrupt: Boolean = false) {
            controller.sendMessageToRealtimeAPI(text, respond, allowInterrupt)
        }

        fun responseDone() {
            events.handle("""{"type":"response.done","response":{"id":"old","status":"completed","output":[]}}""")
        }

        fun assertPlaybackPreserved() {
            assertTrue(playing.value)
            assertEquals(TurnManager.State.SPEAKING, turn.state)
            verify(interrupt, never()).interruptSpeech()
            verify(player, never()).interruptNow()
        }
    }

    @Test fun `completed generation permits a new response while old audio still plays`() = runTest {
        val f = Fixture(this)
        f.speaking(stillGenerating = false)
        f.send(); runCurrent()
        inOrder(f.network) {
            verify(f.network).sendUserTextMessage("event")
            verify(f.network).requestResponse()
        }
        assertTrue(f.generating.value)
        f.assertPlaybackPreserved()
    }

    @Test fun `noninterrupting request waits for response done but not audio playback completion`() = runTest {
        val f = Fixture(this)
        f.speaking(stillGenerating = true)
        f.send(); runCurrent()
        verify(f.network).sendUserTextMessage("event")
        verify(f.network, never()).requestResponse()
        f.events.handle("""{"type":"response.output_audio.done"}""")
        runCurrent()
        verify(f.network, never()).requestResponse()
        f.responseDone(); runCurrent()
        verify(f.network, times(1)).requestResponse()
        f.assertPlaybackPreserved()
    }

    @Test fun `context only message neither waits nor interrupts active generation`() = runTest {
        val f = Fixture(this)
        f.speaking(stillGenerating = true)
        f.send(respond = false, allowInterrupt = true); runCurrent()
        verify(f.network).sendUserTextMessage("event")
        verify(f.network, never()).requestResponse()
        assertTrue(f.generating.value)
        f.assertPlaybackPreserved()
    }

    @Test fun `allowed interruption precedes the new message and response request`() = runTest {
        val f = Fixture(this)
        f.speaking(stillGenerating = true)
        f.send(allowInterrupt = true); runCurrent()
        verify(f.network, never()).requestResponse()
        advanceTimeBy(50); runCurrent()
        inOrder(f.interrupt, f.network) {
            verify(f.interrupt).interruptSpeech()
            verify(f.network).sendUserTextMessage("event")
            verify(f.network).requestResponse()
        }
        verify(f.interrupt, times(1)).interruptSpeech()
    }

    @Test fun `allowed interruption also stops already generated audio without waiting`() = runTest {
        val f = Fixture(this)
        f.speaking(stillGenerating = false)
        f.send(allowInterrupt = true); runCurrent()
        verify(f.interrupt, times(1)).interruptSpeech()
        verify(f.network).requestResponse()
        assertFalse(f.playing.value)
    }

    @Test fun `waiting event cannot occupy the gap between an allowed cancel and its response request`() = runTest {
        val f = Fixture(this)
        f.speaking(stillGenerating = true)
        f.send("waiting event"); runCurrent()
        f.send("interrupting message", allowInterrupt = true); runCurrent()
        verify(f.network, never()).requestResponse()
        advanceTimeBy(50); runCurrent()
        verify(f.network, times(1)).requestResponse()
        f.responseDone(); runCurrent()
        verify(f.network, times(2)).requestResponse()
    }

    @Test fun `google waits before sending response triggering text and preserves queued audio`() = runTest {
        val f = Fixture(this, RealtimeApiProvider.GOOGLE_GEMINI)
        f.speaking(stillGenerating = false)
        f.handler.onGoogleModelTurnStarted()
        assertTrue(f.generating.value)
        f.send(); runCurrent()
        verify(f.network, never()).sendGoogleTextMessage(any(), any())
        f.events.handle("""{"serverContent":{"turnComplete":true}}""")
        runCurrent()
        verify(f.network).sendGoogleTextMessage("event", true)
        verify(f.network, never()).requestResponse()
        f.assertPlaybackPreserved()
    }

    @Test fun `google allowed interruption happens before text can start a new response`() = runTest {
        val f = Fixture(this, RealtimeApiProvider.GOOGLE_GEMINI)
        f.speaking(stillGenerating = true)
        whenever(f.network.sendGoogleTextMessage(any(), eq(true))).thenAnswer {
            f.handler.onGoogleModelTurnStarted()
            true
        }
        f.send(allowInterrupt = true); advanceUntilIdle()
        inOrder(f.interrupt, f.network) {
            verify(f.interrupt).interruptSpeech()
            verify(f.network).sendGoogleTextMessage("event", true)
        }
        verify(f.interrupt, times(1)).interruptSpeech()
        assertTrue(f.generating.value)
    }

    @Test fun `google context only updates do not wait for generation`() = runTest {
        val f = Fixture(this, RealtimeApiProvider.GOOGLE_GEMINI)
        f.speaking(stillGenerating = true)
        f.send(respond = false); runCurrent()
        verify(f.network).sendGoogleTextMessage("event", false)
        f.assertPlaybackPreserved()
    }

    @Test fun `cancelling an operation discards its waiting response request`() = runTest {
        val f = Fixture(this)
        val operation = OperationScope()
        f.generating.value = true
        f.controller.sendScopedMessage("event", true, false, operation); runCurrent()
        operation.cancel()
        f.responseDone(); runCurrent()
        verify(f.network, never()).requestResponse()
    }

    @Test fun `replacing a chat also cancels waits owned by an independent operation`() = runTest {
        val f = Fixture(this)
        val operation = OperationScope()
        f.generating.value = true
        f.controller.sendScopedMessage("old event", true, false, operation); runCurrent()
        f.registry.begin()
        f.generating.value = false
        runCurrent()
        assertTrue(operation.isActive)
        verify(f.network, never()).requestResponse()
        f.send("new event"); runCurrent()
        verify(f.network, times(1)).requestResponse()
    }

    @Test fun `disconnect cancels google text waiting for the current turn`() = runTest {
        val f = Fixture(this, RealtimeApiProvider.GOOGLE_GEMINI)
        f.generating.value = true
        f.send(); runCurrent()
        f.controller.disconnectWebSocket()
        f.generating.value = false
        runCurrent()
        verify(f.network, never()).sendGoogleTextMessage(any(), any())
    }

    @Test fun `multiple waiting messages reserve generation one at a time`() = runTest {
        val f = Fixture(this)
        f.generating.value = true
        f.send("first"); f.send("second"); runCurrent()
        verify(f.network).sendUserTextMessage("first")
        verify(f.network).sendUserTextMessage("second")
        f.responseDone(); runCurrent()
        verify(f.network, times(1)).requestResponse()
        f.responseDone(); runCurrent()
        verify(f.network, times(2)).requestResponse()
        f.responseDone(); runCurrent()
        verify(f.network, times(2)).requestResponse()
    }

    @Test fun `failed response send releases generation and permits a later request`() = runTest {
        val f = Fixture(this)
        whenever(f.network.requestResponse()).thenReturn(false, true)
        f.send(); runCurrent()
        assertFalse(f.generating.value)
        verify(f.viewModel).addMessage(any())
        f.send("retry"); runCurrent()
        verify(f.network, times(2)).requestResponse()
        assertTrue(f.generating.value)
    }

    @Test fun `failed context send leaves the current response and playback intact`() = runTest {
        val f = Fixture(this)
        f.speaking(stillGenerating = true)
        whenever(f.network.sendUserTextMessage(any())).thenReturn(false)
        f.send(); runCurrent()
        verify(f.viewModel).addMessage(any())
        verify(f.network, never()).requestResponse()
        assertTrue(f.generating.value)
        f.assertPlaybackPreserved()
    }
}
