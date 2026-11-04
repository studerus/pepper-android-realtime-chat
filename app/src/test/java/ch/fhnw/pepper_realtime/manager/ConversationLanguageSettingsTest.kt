package ch.fhnw.pepper_realtime.manager

import android.content.Context
import android.content.SharedPreferences
import ch.fhnw.pepper_realtime.tools.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.*

class ConversationLanguageSettingsTest {
    @Test fun `existing installations without the setting default to English`() {
        val preferences = mock<SharedPreferences>()
        val context = mock<Context>()
        whenever(context.getSharedPreferences(any(), any())).thenReturn(preferences)
        val repository = SettingsRepository(context, mock<ToolRegistry>())
        assertEquals("en", repository.preferredConversationLanguage)
        whenever(preferences.getString("preferredConversationLanguage", null)).thenReturn("gsw")
        assertEquals("gsw", repository.preferredConversationLanguage)
        whenever(preferences.getString("preferredConversationLanguage", null)).thenReturn("unsupported")
        assertEquals("en", repository.preferredConversationLanguage)
    }

    @Test fun `saving preference does not overwrite prompt or speech recognition language`() {
        val preferences = mock<SharedPreferences>()
        val editor = mock<SharedPreferences.Editor>()
        val context = mock<Context>()
        whenever(context.getSharedPreferences(any(), any())).thenReturn(preferences)
        whenever(preferences.edit()).thenReturn(editor)
        whenever(editor.putString(any(), any())).thenReturn(editor)
        val repository = SettingsRepository(context, mock<ToolRegistry>())
        repository.preferredConversationLanguage = "none"
        verify(editor).putString("preferredConversationLanguage", "none")
        verify(editor).apply()
        verifyNoMoreInteractions(editor)
    }
}
