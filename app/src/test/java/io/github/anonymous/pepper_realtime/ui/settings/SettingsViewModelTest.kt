package io.github.anonymous.pepper_realtime.ui.settings

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import io.github.anonymous.pepper_realtime.manager.SettingsRepository
import io.github.anonymous.pepper_realtime.network.RealtimeApiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.lenient
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class SettingsViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Mock
    private lateinit var repository: SettingsRepository

    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        // Default mock behavior
        lenient().`when`(repository.apiProvider)
            .thenReturn(RealtimeApiProvider.AZURE_OPENAI.toString())
        lenient().`when`(repository.model).thenReturn("gpt-4o-realtime-preview")
        lenient().`when`(repository.voice).thenReturn("alloy")
        lenient().`when`(repository.volume).thenReturn(80)

        viewModel = SettingsViewModel(repository)
    }

    @Test
    fun testInitialState() {
        assertEquals("gpt-4o-realtime-preview", viewModel.getModel())
        assertEquals("alloy", viewModel.getVoice())
        assertEquals(80, viewModel.getVolume())
    }

    @Test
    fun testSetModel_UpdatesRepository() {
        viewModel.setModel("gpt-realtime")
        verify(repository).model = "gpt-realtime"
    }

    @Test
    fun testSetVoice_UpdatesRepository() {
        viewModel.setVoice("echo")
        verify(repository).voice = "echo"
    }

    @Test
    fun testSetVolume_UpdatesRepository() {
        viewModel.setVolume(50)
        verify(repository).volume = 50
    }

    @Test
    fun testRestartSession_TriggersEvent() {
        // Trigger a change that causes a restart (e.g. model change)
        viewModel.setModel("new-model")

        // Verify LiveData value
        val eventValue = viewModel.restartSessionEvent.value
        assertTrue("Restart session event should be true", eventValue == true)
    }

    @Test
    fun testConsumeRestartSessionEvent_ResetsEvent() {
        // Trigger a change that causes a restart
        viewModel.setModel("new-model")
        viewModel.consumeRestartSessionEvent()

        val eventValue = viewModel.restartSessionEvent.value
        assertFalse("Restart session event should be false after consumption", eventValue == true)
    }
}

