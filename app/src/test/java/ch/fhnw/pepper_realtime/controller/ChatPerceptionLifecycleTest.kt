package ch.fhnw.pepper_realtime.controller

import android.app.Application
import ch.fhnw.pepper_realtime.service.PerceptionService
import ch.fhnw.pepper_realtime.ui.ChatViewModel
import org.junit.Test
import org.mockito.kotlin.*

class ChatPerceptionLifecycleTest {
    @Test fun `returning from background with robot focus restarts perception once`() {
        val application = mock<Application>()
        whenever(application.getString(any())).thenReturn("status")
        val viewModel = mock<ChatViewModel>()
        whenever(viewModel.getApplication<Application>()).thenReturn(application)
        val perception = mock<PerceptionService>()
        whenever(perception.isInitialized).thenReturn(true)
        val focus = mock<RobotFocusManager>()
        val lifecycle = ChatLifecycleController(viewModel, mock(), null, perception,
            null, null, null, null, null, mock())

        lifecycle.onStop()
        verify(perception).stopMonitoring()
        lifecycle.onResume(focus)
        verify(perception, never()).startMonitoring()
        whenever(focus.isFocusAvailable).thenReturn(true)
        lifecycle.onResume(focus)
        lifecycle.onResume(focus)
        verify(perception).startMonitoring()
    }
}
