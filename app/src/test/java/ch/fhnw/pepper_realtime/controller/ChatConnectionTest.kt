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
