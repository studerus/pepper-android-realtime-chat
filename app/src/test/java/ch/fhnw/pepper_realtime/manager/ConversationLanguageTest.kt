package ch.fhnw.pepper_realtime.manager

import org.junit.Assert.*
import org.junit.Test

class ConversationLanguageTest {
    @Test fun `English is the default for missing and invalid preferences`() {
        for (code in listOf(null, "", "invalid")) {
            assertEquals(ConversationLanguage.ENGLISH, ConversationLanguage.fromCode(code))
        }
        assertEquals(16, ConversationLanguage.entries.size)
        assertEquals(16, ConversationLanguage.entries.map { it.code }.toSet().size)
    }

    @Test fun `all preferences preserve the custom prompt and append the policy once`() {
        val base = "Custom role\nKeep this text unchanged."
        for (language in ConversationLanguage.entries) {
            val result = ConversationLanguage.composePrompt(base, language.code)
            assertTrue(result.startsWith("$base\n\n# Conversation Language Setting"))
            assertEquals(1, Regex("# Conversation Language Setting").findAll(result).count())
            assertTrue(result.contains("These language rules override earlier language instructions."))
            assertTrue(result.contains("clarification"))
            assertTrue(result.contains("tool results"))
            assertTrue(result.contains("your own previous replies"))
            assertTrue(ConversationLanguage.composePrompt("", language.code).length < 650)
        }
    }

    @Test fun `no preference does not impose English and Swiss German is distinct`() {
        val none = ConversationLanguage.composePrompt("", "none")
        assertTrue(none.contains("Follow the language of the user's clearly understood speech"))
        assertFalse(none.contains("Change language only"))
        assertFalse(none.contains("English"))
        assertTrue(ConversationLanguage.composePrompt("", "gsw").contains("not Standard German"))
        assertTrue(ConversationLanguage.composePrompt("", "de").contains("Speak German throughout the conversation"))
    }

    @Test fun `every selected language requires an explicit request to switch`() {
        for (language in ConversationLanguage.entries.filter { it != ConversationLanguage.NONE }) {
            val prompt = ConversationLanguage.composePrompt("", language.code)
            assertTrue(prompt.contains("including clarification questions"))
            assertTrue(prompt.contains("Change language only if the user explicitly asks you to"))
            assertTrue(prompt.contains("then keep the requested language"))
            assertFalse(prompt.contains("Follow the language of the user's clearly understood speech"))
        }
    }

    @Test fun `transcription hints are independent from output dialect and optional`() {
        assertNull(ConversationLanguage.NONE.transcriptionHint)
        assertEquals("de-CH", ConversationLanguage.SWISS_GERMAN.transcriptionHint)
        assertEquals("de", ConversationLanguage.GERMAN.transcriptionHint)
        assertEquals("en", ConversationLanguage.ENGLISH.transcriptionHint)
        assertEquals("es", ConversationLanguage.SPANISH.transcriptionHint)
    }
}
