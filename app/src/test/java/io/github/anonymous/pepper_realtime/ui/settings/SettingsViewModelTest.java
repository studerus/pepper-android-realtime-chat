package io.github.anonymous.pepper_realtime.ui.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Application;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.Observer;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import io.github.anonymous.pepper_realtime.manager.SettingsRepository;
import io.github.anonymous.pepper_realtime.network.RealtimeApiProvider;

@RunWith(MockitoJUnitRunner.class)
public class SettingsViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    @Mock
    private Application application;

    @Mock
    private SettingsRepository repository;

    private SettingsViewModel viewModel;

    @Before
    public void setUp() {
        // Default mock behavior
        org.mockito.Mockito.lenient().when(repository.getApiProvider())
                .thenReturn(RealtimeApiProvider.AZURE_OPENAI.toString());
        org.mockito.Mockito.lenient().when(repository.getModel()).thenReturn("gpt-4o-realtime-preview");
        org.mockito.Mockito.lenient().when(repository.getVoice()).thenReturn("alloy");
        org.mockito.Mockito.lenient().when(repository.getVolume()).thenReturn(80);

        viewModel = new SettingsViewModel(repository);
    }

    @Test
    public void testInitialState() {
        assertEquals("gpt-4o-realtime-preview", viewModel.getModel());
        assertEquals("alloy", viewModel.getVoice());
        assertEquals(80, viewModel.getVolume());
    }

    @Test
    public void testSetModel_UpdatesRepository() {
        viewModel.setModel("gpt-realtime");
        verify(repository).setModel("gpt-realtime");
    }

    @Test
    public void testSetVoice_UpdatesRepository() {
        viewModel.setVoice("echo");
        verify(repository).setVoice("echo");
    }

    @Test
    public void testSetVolume_UpdatesRepository() {
        viewModel.setVolume(50);
        verify(repository).setVolume(50);
    }

    @Test
    public void testRestartSession_TriggersEvent() {
        // Trigger a change that causes a restart (e.g. model change)
        viewModel.setModel("new-model");

        // Verify LiveData value
        Boolean eventValue = viewModel.getRestartSessionEvent().getValue();
        assertTrue("Restart session event should be true", eventValue != null && eventValue);
    }

    @Test
    public void testConsumeRestartSessionEvent_ResetsEvent() {
        // Trigger a change that causes a restart
        viewModel.setModel("new-model");
        viewModel.consumeRestartSessionEvent();

        Boolean eventValue = viewModel.getRestartSessionEvent().getValue();
        assertFalse("Restart session event should be false after consumption", eventValue != null && eventValue);
    }
}
