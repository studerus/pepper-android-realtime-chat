package ch.fhnw.pepper_realtime.controller

import android.app.Application
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.ui.ChatViewModel
import org.junit.Test
import org.mockito.kotlin.*

class ChatTurnListenerTest {
    @Test fun `thinking stops direct audio even when Azure recognition is not running`() {
        val viewModel = mock<ChatViewModel>()
        val app = mock<Application>()
        whenever(viewModel.getApplication<Application>()).thenReturn(app)
        whenever(app.getString(R.string.status_thinking)).thenReturn("Thinking")
        val audio = mock<AudioInputController>()
        whenever(audio.isSttRunning).thenReturn(false)
        whenever(audio.isCapturing).thenReturn(true)
        ChatTurnListener(viewModel, mock(), audio, mock(), null, null).onEnterThinking()
        verify(audio).stopContinuousRecognition()
        verify(viewModel).setStatusText("Thinking")
    }
}
