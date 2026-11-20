package ch.fhnw.pepper_realtime.controller

import ch.fhnw.pepper_realtime.manager.*
import ch.fhnw.pepper_realtime.network.*
import ch.fhnw.pepper_realtime.ui.ChatViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class AudioInputControllerTest {
    private fun fixture(scope: TestScope, realtime: Boolean = true): AudioInputController {
        val settings = mock<SettingsRepository>()
        val network = mock<RealtimeSessionManager>()
        whenever(settings.isUsingRealtimeAudioInput).thenReturn(realtime)
        whenever(settings.apiProviderEnum).thenReturn(RealtimeApiProvider.OPENAI_DIRECT)
        whenever(network.isConnected).thenReturn(true)
        return AudioInputController(mock<ChatViewModel>(), settings, mock(), network,
            StandardTestDispatcher(scope.testScheduler), scope)
    }
    private fun AudioInputController.inject(name: String, value: Any) {
        javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    @Test fun `stop cancels capture start still waiting on the dispatcher`() = runTest {
        val controller = fixture(this)
        val input = mock<RealtimeAudioInputManager>()
        controller.inject("realtimeAudioInput", input)
        controller.startContinuousRecognition(); controller.stopContinuousRecognition(); runCurrent()
        verify(input, never()).start()
        verify(input).stop()
    }

    @Test fun `old queued stop only closes the original input manager`() = runTest {
        val controller = fixture(this)
        val old = mock<RealtimeAudioInputManager>(); val next = mock<RealtimeAudioInputManager>()
        controller.inject("realtimeAudioInput", old)
        controller.stopContinuousRecognition()
        controller.inject("realtimeAudioInput", next)
        runCurrent()
        verify(old).stop(); verifyNoInteractions(next)
    }

    @Test fun `restart cleanup cancels a pending realtime start`() = runTest {
        val controller = fixture(this)
        val input = mock<RealtimeAudioInputManager>()
        controller.inject("realtimeAudioInput", input)
        controller.startContinuousRecognition(); controller.cleanupForRestart(); runCurrent()
        verify(input, never()).start(); verify(input).stop()
    }

    @Test fun `shutdown prevents a queued Azure start`() = runTest {
        val controller = fixture(this, realtime = false)
        val speech = mock<SpeechRecognizerManager>()
        controller.inject("sttManager", speech)
        controller.startContinuousRecognition(); controller.shutdown(); runCurrent()
        verify(speech, never()).start(); verify(speech).shutdown()
    }

    @Test fun `Azure capture indicator follows recognizer callbacks`() = runTest {
        val controller = fixture(this, realtime = false)
        controller.setSttRunning(true)
        org.junit.Assert.assertTrue(controller.captureState.value)
        controller.setSttRunning(false)
        org.junit.Assert.assertFalse(controller.captureState.value)
    }
}
