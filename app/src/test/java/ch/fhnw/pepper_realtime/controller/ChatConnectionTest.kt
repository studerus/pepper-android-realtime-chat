package ch.fhnw.pepper_realtime.controller

import android.app.Application
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.manager.*
import ch.fhnw.pepper_realtime.network.*
import ch.fhnw.pepper_realtime.tools.ChatWorkRegistry
import ch.fhnw.pepper_realtime.ui.ChatViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Answers.RETURNS_DEEP_STUBS
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatConnectionTest {
    private class Result : WebSocketConnectionCallback {
        var successes = 0
        val errors = mutableListOf<Throwable>()
        override fun onSuccess() { successes++ }
        override fun onError(error: Throwable) { errors.add(error) }
    }

    private class Fixture(scope: TestScope) {
        val viewModel = mock<ChatViewModel>(defaultAnswer = RETURNS_DEEP_STUBS)
        val network = mock<RealtimeSessionManager>()
        val settings = mock<SettingsRepository>()
        val keys = mock<ApiKeyManager>()
        val audio = mock<AudioInputController>()
        val turn = mock<TurnManager>()
        val clock = mock<SystemTimeRecovery>()
        val registry = ChatWorkRegistry()
        val listeners = mutableListOf<RealtimeSessionManager.Listener>()
        val configurations = mutableListOf<RealtimeSessionManager.SessionConfigCallback>()
        val controller: ChatSessionController
        var ready = false
        init {
            val app = mock<Application>()
            whenever(viewModel.getApplication<Application>()).thenReturn(app)
            whenever(app.getString(any())).thenAnswer { "resource:${it.getArgument<Int>(0)}" }
            whenever(settings.apiProviderEnum).thenReturn(RealtimeApiProvider.OPENAI_DIRECT)
            whenever(settings.model).thenReturn("gpt-realtime")
            whenever(settings.isUsingRealtimeAudioInput).thenReturn(true)
            whenever(keys.azureOpenAiEndpoint).thenReturn("https://example.invalid")
            whenever(keys.azureOpenAiKey).thenReturn("")
            whenever(keys.openAiApiKey).thenReturn("test-key")
            whenever(keys.xaiApiKey).thenReturn("")
            whenever(network.isConnected).thenAnswer { ready }
            doAnswer { ready = false; null }.whenever(network).close(any(), anyOrNull())
            doAnswer { listeners.add(it.getArgument(0)); null }.whenever(network).listener = any()
            doAnswer { configurations.add(it.getArgument(0)); null }.whenever(network).setSessionConfigCallback(any())
            controller = ChatSessionController(viewModel, network, settings, keys, audio,
                StandardTestDispatcher(scope.testScheduler), scope, mock(), turn, mock(), mock(), mock(), mock(), registry, mock(), clock)
        }
        fun acknowledge() {
            ready = true
            configurations.last().onSessionConfigured(true, null)
        }
    }

    @Test fun `valid clock opens the connection immediately without asynchronous preparation`() = runTest {
        val f = Fixture(this)
        val result = Result()
        f.controller.connectWebSocket(result)
        // No scheduler advance: a plausible clock retains the synchronous connection fast path.
        verify(f.network, times(1)).connect(any(), anyOrNull())
        verify(f.clock, never()).awaitValidClock(any())
        assertEquals(0L, testScheduler.currentTime)
        f.acknowledge()
        f.controller.disconnectWebSocketGracefully()
    }

    @Test fun `normal server close marks an established chat disconnected once without an error message`() = runTest {
        val f = Fixture(this)
        f.controller.connectWebSocket(null)
        f.acknowledge()
        val listener = f.listeners.last()
        listener.onClosing(1000, "Session ended")
        listener.onClosed(1000, "Session ended")
        verify(f.viewModel).setStatusText("resource:${R.string.error_disconnected}")
        verify(f.audio).cleanupForRestart()
        verify(f.viewModel, never()).addMessage(any())
        assertNull(f.registry.current)
    }

    @Test fun `normal close callback after local disconnect does not overwrite local status`() = runTest {
        val f = Fixture(this)
        f.controller.connectWebSocket(null)
        f.acknowledge()
        val listener = f.listeners.last()
        f.controller.disconnectWebSocket()
        listener.onClosed(1000, "User initiated disconnect")
        verify(f.viewModel, never()).setStatusText("resource:${R.string.error_disconnected}")
    }

    @Test fun `cancelled tool no longer prevents Gemini resume on a normal server close`() = runTest {
        val f = Fixture(this)
        whenever(f.settings.apiProviderEnum).thenReturn(RealtimeApiProvider.GOOGLE_GEMINI)
        whenever(f.settings.model).thenReturn("models/gemini-3.1-flash-live-preview")
        f.controller.connectWebSocket(null)
        f.acknowledge()
        val owner = f.registry.current!!
        owner.beginCall("cancelled")
        owner.cancelCall("cancelled")
        f.controller.updateGoogleCheckpoint(GoogleLiveEvents.SessionResumptionUpdate("saved", true))
        f.listeners.last().onClosing(1000, "Server rotation")
        advanceTimeBy(1000); runCurrent()
        verify(f.network).resumeGoogleSession(any(), anyOrNull(), eq("saved"))
        f.acknowledge()
        assertSame(owner, f.registry.current)
        verify(f.viewModel, never()).setStatusText("resource:${R.string.error_disconnected}")
    }

    @Test fun `clock preparation is shared and the handshake timeout starts only afterwards`() = runTest {
        val f = Fixture(this)
        val ready = CompletableDeferred<Boolean>()
        whenever(f.clock.needsRecovery()).thenReturn(true)
        whenever(f.clock.awaitValidClock(any())).doSuspendableAnswer {
            it.getArgument<(ClockRecoveryStage) -> Unit>(0)(ClockRecoveryStage.WAITING_FOR_NETWORK)
            ready.await()
        }
        val first = Result(); val second = Result()
        f.controller.connectWebSocket(first)
        f.controller.connectWebSocket(second)
        runCurrent()
        advanceTimeBy(60000); runCurrent()
        verify(f.network, never()).connect(any(), anyOrNull())
        verify(f.viewModel).setStatusText("resource:${R.string.status_waiting_for_network}")
        assertTrue(first.errors.isEmpty() && second.errors.isEmpty())
        ready.complete(true)
        runCurrent()
        verify(f.clock, times(1)).awaitValidClock(any())
        verify(f.network, times(1)).connect(any(), anyOrNull())
        advanceTimeBy(ChatSessionController.CONNECTION_TIMEOUT_MS - 1); runCurrent()
        assertTrue(first.errors.isEmpty())
        f.acknowledge()
        advanceUntilIdle()
        assertEquals(1, first.successes)
        assertEquals(1, second.successes)
        f.controller.disconnectWebSocketGracefully()
    }

    @Test fun `failed clock recovery fails all waiters without opening a socket`() = runTest {
        val f = Fixture(this)
        whenever(f.clock.needsRecovery()).thenReturn(true)
        whenever(f.clock.awaitValidClock(any())).thenReturn(false)
        val first = Result(); val second = Result()
        f.controller.connectWebSocket(first)
        f.controller.connectWebSocket(second)
        advanceUntilIdle()
        assertEquals(1, first.errors.size)
        assertEquals(1, second.errors.size)
        assertEquals("resource:${R.string.error_clock_sync_failed}", first.errors.single().message)
        verify(f.network, never()).connect(any(), anyOrNull())
        verify(f.viewModel).setStatusText("resource:${R.string.status_clock_sync_failed}")
        assertNull(f.registry.current)
    }

    @Test fun `disconnect during clock recovery cannot open a delayed connection`() = runTest {
        val f = Fixture(this)
        val ready = CompletableDeferred<Boolean>()
        whenever(f.clock.needsRecovery()).thenReturn(true)
        whenever(f.clock.awaitValidClock(any())).doSuspendableAnswer { ready.await() }
        val result = Result()
        f.controller.connectWebSocket(result)
        runCurrent()
        f.controller.disconnectWebSocketGracefully()
        ready.complete(true)
        advanceUntilIdle()
        verify(f.network, never()).connect(any(), anyOrNull())
        assertTrue(result.errors.isEmpty())
        assertEquals(0, result.successes)
        assertNull(f.registry.current)
    }

    @Test fun `new connection supersedes a cancelled clock preparation`() = runTest {
        val f = Fixture(this)
        val oldReady = CompletableDeferred<Boolean>()
        whenever(f.clock.needsRecovery()).thenReturn(true)
        whenever(f.clock.awaitValidClock(any())).doSuspendableAnswer { oldReady.await() }
        val oldResult = Result()
        f.controller.connectWebSocket(oldResult)
        runCurrent()
        f.controller.disconnectWebSocketGracefully()
        whenever(f.clock.needsRecovery()).thenReturn(false)
        val retry = Result()
        f.controller.connectWebSocket(retry)
        oldReady.complete(true)
        runCurrent()
        verify(f.network, times(1)).connect(any(), anyOrNull())
        f.acknowledge()
        advanceUntilIdle()
        assertEquals(0, oldResult.successes)
        assertTrue(oldResult.errors.isEmpty())
        assertEquals(1, retry.successes)
        f.controller.disconnectWebSocketGracefully()
    }

    @Test fun `Gemini close and late callbacks resume once using newest checkpoint without replacing chat`() = runTest {
        val f = Fixture(this)
        whenever(f.settings.apiProviderEnum).thenReturn(RealtimeApiProvider.GOOGLE_GEMINI)
        whenever(f.settings.model).thenReturn("models/gemini-3.1-flash-live-preview")
        f.controller.connectWebSocket(null)
        f.acknowledge()
        val owner = f.registry.current
        val old = f.listeners.last()
        f.controller.updateGoogleCheckpoint(GoogleLiveEvents.SessionResumptionUpdate("first", true))
        f.controller.updateGoogleCheckpoint(GoogleLiveEvents.SessionResumptionUpdate("latest", true))
        old.onClosing(1001, "server rotation")
        old.onClosed(1001, "server rotation")
        advanceTimeBy(1000); runCurrent()
        verify(f.network).resumeGoogleSession(any(), anyOrNull(), eq("latest"))
        f.acknowledge()
        assertSame(owner, f.registry.current)
        verify(f.viewModel, never()).clearMessages()
        verify(f.turn).setState(TurnManager.State.LISTENING)
        f.listeners.last().onFailure(java.io.IOException("lost again before new checkpoint"), null)
        advanceUntilIdle()
        verify(f.network, times(1)).resumeGoogleSession(any(), anyOrNull(), any())
    }

    @Test fun `Gemini client inactivity close resumes but a different policy violation does not`() = runTest {
        val f = Fixture(this)
        whenever(f.settings.apiProviderEnum).thenReturn(RealtimeApiProvider.GOOGLE_GEMINI)
        whenever(f.settings.model).thenReturn("models/gemini-3.1-flash-live-preview")
        f.controller.connectWebSocket(null)
        f.acknowledge()
        f.controller.updateGoogleCheckpoint(GoogleLiveEvents.SessionResumptionUpdate("before-idle", true))
        f.listeners.last().onClosing(1008, "Session closed due to client inactivity.")
        advanceTimeBy(1000); runCurrent()
        verify(f.network).resumeGoogleSession(any(), anyOrNull(), eq("before-idle"))
        f.acknowledge()
        f.controller.updateGoogleCheckpoint(GoogleLiveEvents.SessionResumptionUpdate("after-idle", true))
        f.listeners.last().onClosing(1008, "Permission denied")
        advanceUntilIdle()
        verify(f.network, times(1)).resumeGoogleSession(any(), anyOrNull(), any())
    }

    @Test fun `consecutive idle closes resume the same quiet chat and deliver a touch during reconnect once`() = runTest {
        val f = Fixture(this)
        whenever(f.settings.apiProviderEnum).thenReturn(RealtimeApiProvider.GOOGLE_GEMINI)
        whenever(f.settings.model).thenReturn("models/gemini-3.1-flash-live-preview")
        whenever(f.viewModel.isResponseGenerating).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(false))
        whenever(f.viewModel.isAudioPlaying).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(false))
        whenever(f.network.sendGoogleTextMessage("touch", true)).thenReturn(true)
        f.controller.connectWebSocket(null)
        f.acknowledge()
        val owner = f.registry.current
        f.controller.updateGoogleCheckpoint(GoogleLiveEvents.SessionResumptionUpdate("saved", true))
        fun idleClose() = f.listeners.last().onClosing(1008, "Session closed due to client inactivity.")
        idleClose()
        advanceTimeBy(1000); runCurrent(); f.acknowledge()
        idleClose()
        f.controller.sendMessageToRealtimeAPI("touch", true, true)
        advanceTimeBy(1000); runCurrent()
        verify(f.network, times(2)).resumeGoogleSession(any(), anyOrNull(), eq("saved"))
        verify(f.network, never()).sendGoogleTextMessage("touch", true)
        f.acknowledge(); runCurrent()
        assertSame(owner, f.registry.current)
        verify(f.network).sendGoogleTextMessage("touch", true)
        whenever(f.network.hasSentConversationInput).thenReturn(true)
        idleClose(); advanceUntilIdle()
        verify(f.network, times(2)).resumeGoogleSession(any(), anyOrNull(), any())
    }

    @Test fun `nonresumable Gemini update invalidates the old checkpoint`() = runTest {
        val f = Fixture(this)
        whenever(f.settings.apiProviderEnum).thenReturn(RealtimeApiProvider.GOOGLE_GEMINI)
        whenever(f.settings.model).thenReturn("models/gemini-3.1-flash-live-preview")
        f.controller.connectWebSocket(null)
        f.acknowledge()
        f.controller.updateGoogleCheckpoint(GoogleLiveEvents.SessionResumptionUpdate("old", true))
        f.controller.updateGoogleCheckpoint(GoogleLiveEvents.SessionResumptionUpdate(null, false))
        f.listeners.last().onClosing(1001, "going away")
        advanceUntilIdle()
        verify(f.network, never()).resumeGoogleSession(any(), anyOrNull(), any())
    }

    @Test fun `failed new chat reports its error and a subsequent restart can succeed`() = runTest {
        val f = Fixture(this)
        f.controller.startNewSession()
        advanceTimeBy(500); runCurrent()
        val failedListener = f.listeners.last()
        failedListener.onFailure(IllegalStateException("No network"), null)
        verify(f.viewModel).setStatusText("resource:${R.string.error_connection_failed_short}")
        verify(f.viewModel, times(1)).addMessage(any())
        assertNull(f.registry.current)
        f.controller.startNewSession()
        advanceTimeBy(500); runCurrent()
        failedListener.onClosed(1006, "Old socket")
        f.acknowledge()
        advanceUntilIdle()
        verify(f.turn).setState(TurnManager.State.LISTENING)
        verify(f.viewModel, times(1)).addMessage(any())
        assertTrue(f.registry.current!!.lifetime.isActive)
        f.controller.disconnectWebSocketGracefully()
    }

    @Test fun `concurrent connects share one attempt and complete once after acknowledgement`() = runTest {
        val f = Fixture(this)
        val first = Result(); val second = Result()
        f.controller.connectWebSocket(first)
        f.listeners.last().onOpen(mock())
        f.controller.connectWebSocket(second)
        verify(f.network, times(1)).connect(any(), anyOrNull())
        assertEquals(0, first.successes + second.successes)
        f.acknowledge()
        f.acknowledge()
        advanceUntilIdle() // A completed attempt must not time out later.
        assertEquals(1, first.successes)
        assertEquals(1, second.successes)
        assertTrue(first.errors.isEmpty() && second.errors.isEmpty())
        assertNotNull(f.registry.current)
        f.controller.disconnectWebSocketGracefully()
    }

    @Test fun `handshake timeout fails once and old events cannot complete the retry`() = runTest {
        val f = Fixture(this)
        val first = Result()
        f.controller.connectWebSocket(first)
        val oldConfig = f.configurations.last()
        val oldListener = f.listeners.last()
        advanceTimeBy(ChatSessionController.CONNECTION_TIMEOUT_MS); runCurrent()
        assertEquals(1, first.errors.size)
        assertTrue(first.errors.single().message!!.contains("Timed out"))
        assertNull(f.registry.current)
        verify(f.network).close(1000, "Session setup failed")
        val retry = Result()
        f.controller.connectWebSocket(retry)
        oldConfig.onSessionConfigured(true, null)
        oldConfig.onSessionConfigured(false, "Late failure")
        oldListener.onOpen(mock())
        oldListener.onFailure(IllegalStateException("Late close"), null)
        assertEquals(0, retry.successes)
        assertTrue(retry.errors.isEmpty())
        verify(f.network, never()).configureInitialSession()
        f.acknowledge()
        advanceUntilIdle()
        assertEquals(1, retry.successes)
        assertEquals(0, first.successes)
        f.controller.disconnectWebSocketGracefully()
    }

    @Test fun `open socket without setup acknowledgement times out and resets audio`() = runTest {
        val f = Fixture(this)
        val result = Result()
        f.controller.connectWebSocket(result)
        f.listeners.last().onOpen(mock())
        advanceUntilIdle()
        assertEquals(1, result.errors.size)
        assertEquals(0, result.successes)
        verify(f.audio).cleanupForRestart()
        verify(f.viewModel).setWarmingUp(false)
        verify(f.turn).setState(TurnManager.State.IDLE)
        assertNull(f.registry.current)
    }

    @Test fun `setup rejection reaches all waiters once and cancels timeout`() = runTest {
        val f = Fixture(this)
        val first = Result(); val second = Result()
        f.controller.connectWebSocket(first)
        f.controller.connectWebSocket(second)
        f.configurations.last().onSessionConfigured(false, "Bad configuration")
        f.configurations.last().onSessionConfigured(false, "Duplicate error")
        advanceUntilIdle()
        assertEquals(1, first.errors.size)
        assertEquals(1, second.errors.size)
        assertEquals(0, first.successes + second.successes)
        assertNull(f.registry.current)
    }

    @Test fun `intentional disconnect cancels the attempt without later error or success`() = runTest {
        val f = Fixture(this)
        val result = Result()
        f.controller.connectWebSocket(result)
        val oldConfig = f.configurations.last()
        f.controller.disconnectWebSocketGracefully()
        oldConfig.onSessionConfigured(true, null)
        advanceUntilIdle()
        assertTrue(result.errors.isEmpty())
        assertEquals(0, result.successes)
        verify(f.viewModel, never()).addMessage(any())
    }

    @Test fun `remote close during setup fails immediately even with a normal close code`() = runTest {
        val f = Fixture(this)
        val result = Result()
        f.controller.connectWebSocket(result)
        f.listeners.last().onClosing(1000, "Setup rejected")
        assertEquals(1, result.errors.size)
        f.listeners.last().onClosed(1000, "Setup rejected")
        advanceUntilIdle()
        assertEquals(1, result.errors.size)
    }

    @Test fun `synchronous connect failure cleans up before callback can start another attempt`() = runTest {
        val f = Fixture(this)
        doThrow(IllegalStateException("Invalid request")).doNothing().whenever(f.network).connect(any(), anyOrNull())
        val retry = Result()
        val failure = mock<WebSocketConnectionCallback>()
        doAnswer { f.controller.connectWebSocket(retry); null }.whenever(failure).onError(any())
        f.controller.connectWebSocket(failure)
        verify(failure, times(1)).onError(any())
        f.acknowledge()
        advanceUntilIdle()
        assertEquals(1, retry.successes)
        assertTrue(retry.errors.isEmpty())
        f.controller.disconnectWebSocketGracefully()
    }

    @Test fun `restart preparation error is reported and does not leave a pending reconnect`() = runTest {
        val f = Fixture(this)
        doThrow(IllegalStateException("Audio failed")).doNothing().whenever(f.audio).cleanupForRestart()
        f.controller.startNewSession()
        advanceUntilIdle()
        verify(f.network, never()).connect(any(), anyOrNull())
        verify(f.viewModel).setStatusText("resource:${R.string.error_connection_failed_short}")
        assertNull(f.registry.current)
        f.controller.startNewSession()
        advanceTimeBy(500); runCurrent()
        f.acknowledge()
        advanceUntilIdle()
        verify(f.turn).setState(TurnManager.State.LISTENING)
        f.controller.disconnectWebSocketGracefully()
    }
}
