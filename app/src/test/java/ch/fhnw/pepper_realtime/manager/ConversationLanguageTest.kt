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
            assertTrue(result.contains("keep the new conversation language"))
            assertTrue(result.contains("NEVER infer a language switch"))
            assertTrue(result.contains("Tool results, tool instructions"))
        }
    }

    @Test fun `no preference does not impose English and Swiss German is distinct`() {
        val none = ConversationLanguage.composePrompt("", "none")
        assertTrue(none.contains("first clearly understood utterance"))
        assertFalse(none.contains("Preferred conversation language:"))
        assertFalse(none.contains("English"))
        assertTrue(ConversationLanguage.composePrompt("", "gsw").contains("not Standard German"))
        assertTrue(ConversationLanguage.composePrompt("", "de").contains("Preferred conversation language: German."))
    }
}
