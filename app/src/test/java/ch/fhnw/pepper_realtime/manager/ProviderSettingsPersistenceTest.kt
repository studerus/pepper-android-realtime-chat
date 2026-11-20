package ch.fhnw.pepper_realtime.manager

import android.content.Context
import android.content.SharedPreferences
import ch.fhnw.pepper_realtime.network.RealtimeApiProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.*

class ProviderSettingsPersistenceTest {
    private val prefs = mock<SharedPreferences>()
    private val context = mock<Context>()
    private val editor = mock<SharedPreferences.Editor>()
    private val values = mutableMapOf<String, String>()

    private fun repository(): SettingsRepository {
        whenever(context.getSharedPreferences("PepperDialogPrefs", Context.MODE_PRIVATE)).thenReturn(prefs)
        whenever(prefs.edit()).thenReturn(editor)
        whenever(prefs.contains(any())).thenAnswer { values.containsKey(it.getArgument<String>(0)) }
        whenever(prefs.getString(any(), anyOrNull())).thenAnswer { values[it.getArgument<String>(0)] ?: it.getArgument<String?>(1) }
        whenever(editor.putString(any(), any())).thenAnswer { values[it.getArgument(0)] = it.getArgument(1); editor }
        whenever(editor.remove(any())).thenAnswer { values.remove(it.getArgument<String>(0)); editor }
        return SettingsRepository(context, mock())
    }

    @Test fun `reset preserves credentials and unrelated preferences`() {
        val keep = mapOf("OPENAI_API_KEY" to "test-only", "AZURE_SPEECH_REGION" to "eastus", "unrelated" to "value")
        values.putAll(keep)
        values.putAll(mapOf("systemPrompt" to "custom", "model_GOOGLE_GEMINI" to "custom", "voice_XAI" to "custom"))
        repository().resetToDefaults()
        verify(editor, never()).clear()
        verify(editor).apply()
        assertEquals(keep, values)
    }

    @Test fun `clearing a saved idle timeout remains disabled after reopening settings`() {
        val repo = repository()
        whenever(prefs.getInt(any(), any())).thenAnswer { values[it.getArgument<String>(0)]?.toInt() ?: it.getArgument<Int>(1) }
        whenever(editor.putInt(any(), any())).thenAnswer { values[it.getArgument(0)] = it.getArgument<Int>(1).toString(); editor }
        repo.idleTimeout = 9000
        assertEquals(9000, repo.idleTimeout)
        repo.idleTimeout = null
        assertEquals(null, SettingsRepository(context, mock()).idleTimeout)
        values["idleTimeout"] = "0"
        assertEquals(null, repo.idleTimeout)
    }

    @Test fun `provider switch restores scoped choices and migrates legacy values only once`() {
        val repo = repository()
        whenever(context.getString(ch.fhnw.pepper_realtime.R.string.openai_default_model)).thenReturn("default-model")
        values.putAll(mapOf("model" to "legacy-model", "voice" to "legacy-voice",
            "model_GOOGLE_GEMINI" to "google-model", "voice_GOOGLE_GEMINI" to "google-voice"))
        repo.apiProvider = RealtimeApiProvider.GOOGLE_GEMINI.name
        assertEquals("google-model", repo.model)
        assertEquals("google-voice", repo.voice)
        repo.apiProvider = RealtimeApiProvider.OPENAI_DIRECT.name
        assertEquals("legacy-model", repo.model)
        assertEquals("legacy-voice", repo.voice)
    }
}
