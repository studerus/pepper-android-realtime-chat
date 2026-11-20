package ch.fhnw.pepper_realtime.controller

import android.app.Application
import ch.fhnw.pepper_realtime.manager.*
import ch.fhnw.pepper_realtime.network.*
import ch.fhnw.pepper_realtime.tools.ChatWorkRegistry
import ch.fhnw.pepper_realtime.ui.ChatViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Answers.RETURNS_DEEP_STUBS
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionLifetimeTest {
    @Test fun `events from an old connection cannot reach the new session`() = runTest {
        val viewModel = mock<ChatViewModel>(defaultAnswer = RETURNS_DEEP_STUBS)
        val app = mock<Application>()
        whenever(viewModel.getApplication<Application>()).thenReturn(app)
        whenever(app.getString(any())).thenReturn("status")
        val network = mock<RealtimeSessionManager>()
        val settings = mock<SettingsRepository>()
        val keys = mock<ApiKeyManager>()
        whenever(settings.apiProviderEnum).thenReturn(RealtimeApiProvider.OPENAI_DIRECT)
        whenever(settings.model).thenReturn("test-model")
        whenever(keys.azureOpenAiEndpoint).thenReturn("https://example.invalid")
        whenever(keys.azureOpenAiKey).thenReturn("")
        whenever(keys.openAiApiKey).thenReturn("test-key")
        whenever(keys.xaiApiKey).thenReturn("")
        val registry = ChatWorkRegistry()
        val events = mock<RealtimeEventHandler>()
        val listeners = mutableListOf<RealtimeSessionManager.Listener>()
        doAnswer { listeners.add(it.getArgument(0)); null }.whenever(network).listener = any()
        val controller = ChatSessionController(viewModel, network, settings, keys, mock(),
            StandardTestDispatcher(testScheduler), this, mock(), null, mock(), mock(), events, mock(), registry, mock(), mock())
        controller.connectWebSocket(null)
        val old = listeners.last()
        controller.disconnectWebSocketGracefully()
        controller.connectWebSocket(null)
        val current = registry.current
        old.onTextMessage("old-event")
        old.onFailure(IllegalStateException("late disconnect"), null)
        verify(events, never()).handle(any())
        assertSame(current, registry.current)
        listeners.last().onTextMessage("current-event")
        verify(events).handle("current-event")
        controller.disconnectWebSocketGracefully()
    }

    @Test fun `disconnect cancels chat work and a pending restart cannot reconnect`() = runTest {
        val viewModel = mock<ChatViewModel>(defaultAnswer = RETURNS_DEEP_STUBS)
        val app = mock<Application>()
        whenever(viewModel.getApplication<Application>()).thenReturn(app)
        whenever(app.getString(any())).thenReturn("status")
        val network = mock<RealtimeSessionManager>()
        val navigation = mock<NavigationServiceManager>()
        val registry = ChatWorkRegistry().apply { begin() }
        val call = registry.current!!.beginCall("call")!!
        var stopped = 0
        call.onCancel { stopped++ }
        val controller = ChatSessionController(viewModel, network, mock(), mock(), mock(),
            StandardTestDispatcher(testScheduler), this, mock(), null, mock(), mock(), mock(), mock(), registry, navigation, mock())
        controller.startNewSession()
        assertEquals(1, stopped)
        runCurrent() // Restart is now waiting for its delay.
        controller.disconnectWebSocketGracefully()
        advanceUntilIdle()
        verify(network, never()).connect(any(), anyOrNull())
        verify(navigation, atLeastOnce()).cancelAllActions()
        assertNull(registry.current)
    }
}
