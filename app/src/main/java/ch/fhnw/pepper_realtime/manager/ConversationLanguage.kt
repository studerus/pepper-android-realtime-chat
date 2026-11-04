package ch.fhnw.pepper_realtime.manager

enum class ConversationLanguage(val code: String, val displayName: String, private val promptName: String) {
    NONE("none", "No preference", ""),
    ENGLISH("en", "English", "English"),
    GERMAN("de", "Deutsch", "German"),
    SWISS_GERMAN("gsw", "Schweizerdeutsch", "Swiss German (spoken Swiss German dialect, not Standard German)"),
    FRENCH("fr", "Fran\u00e7ais", "French"),
    ITALIAN("it", "Italiano", "Italian"),
    SPANISH("es", "Espa\u00f1ol", "Spanish"),
    PORTUGUESE("pt", "Portugu\u00eas", "Portuguese"),
    DUTCH("nl", "Nederlands", "Dutch"),
    POLISH("pl", "Polski", "Polish"),
    TURKISH("tr", "T\u00fcrk\u00e7e", "Turkish"),
    ARABIC("ar", "\u0627\u0644\u0639\u0631\u0628\u064a\u0629", "Arabic"),
    HINDI("hi", "\u0939\u093f\u0928\u094d\u0926\u0940", "Hindi"),
    CHINESE("zh", "\u4e2d\u6587", "Mandarin Chinese"),
    JAPANESE("ja", "\u65e5\u672c\u8a9e", "Japanese"),
    KOREAN("ko", "\ud55c\uad6d\uc5b4", "Korean");

    companion object {
        fun fromCode(code: String?): ConversationLanguage = entries.find { it.code == code } ?: ENGLISH

        fun composePrompt(basePrompt: String, code: String?): String {
            val language = fromCode(code)
            val preference = if (language == NONE) {
                "No preferred conversation language is configured. Infer it from the user's first clearly understood utterance, not from the language of this prompt."
            } else {
                "Preferred conversation language: ${language.promptName}. Start in this language unless the user is already clearly speaking another language. If no language has been established and speech is unclear, ask for clarification in this preferred language."
            }
            val policy = """
                # Conversation Language Setting
                This setting takes precedence over other default or fallback language instructions above.
                $preference
                - The preference is a starting point, not a language restriction. When the user clearly speaks another language or explicitly requests one, switch naturally without requiring an explicit request to switch.
                - After a clear switch, keep the new conversation language; do not revert to the preferred language on every turn.
                - If speech is unclear, ambiguous, low-confidence, or misunderstood, NEVER infer a language switch. Ask for clarification in the established conversation language.
                - Short interjections, names, isolated foreign words, quoted text, and uncertain transcriptions are not evidence of a language switch.
                - Tool results, tool instructions, internal application messages, and the language of this system prompt NEVER determine the response language. Present relevant tool content in the established conversation language.
            """.trimIndent()
            return if (basePrompt.isBlank()) policy else "$basePrompt\n\n$policy"
        }
    }
}
