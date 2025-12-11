package ch.fhnw.pepper_realtime.ui.settings

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import ch.fhnw.pepper_realtime.manager.SettingsRepository
import ch.fhnw.pepper_realtime.network.RealtimeApiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(MockitoJUnitRunner::class)
class SettingsViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Mock
    private lateinit var repository: SettingsRepository

    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        // Setup all required repository properties for SettingsState initialization
        whenever(repository.systemPrompt).thenReturn("Test system prompt")
        whenever(repository.model).thenReturn("gpt-4o-realtime-preview")
        whenever(repository.voice).thenReturn("alloy")
        whenever(repository.speedProgress).thenReturn(100)
        whenever(repository.language).thenReturn("en-US")
        whenever(repository.apiProvider).thenReturn(RealtimeApiProvider.AZURE_OPENAI.name)
        whenever(repository.audioInputMode).thenReturn("realtime_api")
        whenever(repository.temperatureProgress).thenReturn(33)
        whenever(repository.volume).thenReturn(80)
        whenever(repository.silenceTimeout).thenReturn(500)
        whenever(repository.confidenceThreshold).thenReturn(0.7f)
        whenever(repository.enabledTools).thenReturn(emptySet())
        whenever(repository.transcriptionModel).thenReturn("whisper-1")
        whenever(repository.transcriptionLanguage).thenReturn("")
        whenever(repository.transcriptionPrompt).thenReturn("")
        whenever(repository.turnDetectionType).thenReturn("server_vad")
        whenever(repository.vadThreshold).thenReturn(0.5f)
        whenever(repository.prefixPadding).thenReturn(300)
        whenever(repository.silenceDuration).thenReturn(500)
        whenever(repository.idleTimeout).thenReturn(0)
        whenever(repository.eagerness).thenReturn("auto")
        whenever(repository.noiseReduction).thenReturn("off")

        viewModel = SettingsViewModel(repository)
    }

    // ========== Initial State Tests ==========

    @Test
    fun `initial state loads from repository`() {
        val state = viewModel.settingsState.value
        
        assertEquals("gpt-4o-realtime-preview", state.model)
        assertEquals("alloy", state.voice)
        assertEquals(80, state.volume)
        assertEquals("en-US", state.language)
    }

    @Test
    fun `initial events are false`() {
        assertFalse(viewModel.restartSessionEvent.value)
        assertFalse(viewModel.updateSessionEvent.value)
        assertFalse(viewModel.restartRecognizerEvent.value)
    }

    // ========== Model Change Tests ==========

    @Test
    fun `setModel updates repository`() {
        viewModel.setModel("gpt-realtime")
        
        verify(repository).model = "gpt-realtime"
    }

    @Test
    fun `setModel updates state`() {
        viewModel.setModel("gpt-realtime")
        
        assertEquals("gpt-realtime", viewModel.settingsState.value.model)
    }

    @Test
    fun `setModel triggers restart session event`() {
        viewModel.setModel("new-model")
        
        assertTrue("Restart session event should be true", viewModel.restartSessionEvent.value)
    }

    @Test
    fun `setModel with same value does not trigger event`() {
        // Model is already "gpt-4o-realtime-preview" from setup
        viewModel.setModel("gpt-4o-realtime-preview")
        
        assertFalse("Restart session event should be false", viewModel.restartSessionEvent.value)
    }

    // ========== Voice Change Tests ==========

    @Test
    fun `setVoice updates repository`() {
        viewModel.setVoice("echo")
        
        verify(repository).voice = "echo"
    }

    @Test
    fun `setVoice updates state`() {
        viewModel.setVoice("echo")
        
        assertEquals("echo", viewModel.settingsState.value.voice)
    }

    // ========== Volume Change Tests ==========

    @Test
    fun `setVolume updates repository`() {
        viewModel.setVolume(50)
        
        verify(repository).volume = 50
    }

    @Test
    fun `setVolume updates state`() {
        viewModel.setVolume(50)
        
        assertEquals(50, viewModel.settingsState.value.volume)
    }

    @Test
    fun `setVolume triggers volume change event`() {
        viewModel.setVolume(60)
        
        assertEquals(60, viewModel.volumeChangeEvent.value)
    }

    // ========== Event Consumption Tests ==========

    @Test
    fun `consumeRestartSessionEvent resets event`() {
        viewModel.setModel("new-model")
        assertTrue(viewModel.restartSessionEvent.value)
        
        viewModel.consumeRestartSessionEvent()
        
        assertFalse("Restart session event should be false after consumption", viewModel.restartSessionEvent.value)
    }

    @Test
    fun `consumeUpdateSessionEvent resets event`() {
        viewModel.setSystemPrompt("New prompt")
        assertTrue(viewModel.updateSessionEvent.value)
        
        viewModel.consumeUpdateSessionEvent()
        
        assertFalse("Update session event should be false after consumption", viewModel.updateSessionEvent.value)
    }

    // ========== Batch Mode Tests ==========

    @Test
    fun `beginEditing enables batch mode and defers events`() {
        viewModel.beginEditing()
        viewModel.setModel("new-model")
        
        // Event should not trigger yet in batch mode
        assertFalse("Restart event should not trigger during batch mode", viewModel.restartSessionEvent.value)
    }

    @Test
    fun `commitChanges triggers deferred restart event`() {
        viewModel.beginEditing()
        viewModel.setModel("new-model")
        assertFalse(viewModel.restartSessionEvent.value)
        
        viewModel.commitChanges()
        
        assertTrue("Restart event should trigger after commit", viewModel.restartSessionEvent.value)
    }

    @Test
    fun `commitChanges triggers deferred update event when no restart needed`() {
        viewModel.beginEditing()
        viewModel.setSystemPrompt("New prompt")
        assertFalse(viewModel.updateSessionEvent.value)
        
        viewModel.commitChanges()
        
        assertTrue("Update event should trigger after commit", viewModel.updateSessionEvent.value)
    }

    @Test
    fun `restart takes priority over update in batch mode`() {
        viewModel.beginEditing()
        viewModel.setSystemPrompt("New prompt") // Would trigger update
        viewModel.setModel("new-model")          // Would trigger restart
        
        viewModel.commitChanges()
        
        assertTrue("Restart event should trigger", viewModel.restartSessionEvent.value)
        assertFalse("Update event should not trigger when restart is pending", viewModel.updateSessionEvent.value)
    }

    // ========== System Prompt Tests ==========

    @Test
    fun `setSystemPrompt updates repository`() {
        viewModel.setSystemPrompt("New system prompt")
        
        verify(repository).systemPrompt = "New system prompt"
    }

    @Test
    fun `setSystemPrompt triggers update session event`() {
        viewModel.setSystemPrompt("New system prompt")
        
        assertTrue("Update session event should be true", viewModel.updateSessionEvent.value)
    }

    // ========== Language Tests ==========

    @Test
    fun `setLanguage updates repository`() {
        viewModel.setLanguage("de-DE")
        
        verify(repository).language = "de-DE"
    }

    @Test
    fun `setLanguage triggers recognizer restart event`() {
        viewModel.setLanguage("de-DE")
        
        assertTrue("Recognizer restart event should be true", viewModel.restartRecognizerEvent.value)
    }
}
