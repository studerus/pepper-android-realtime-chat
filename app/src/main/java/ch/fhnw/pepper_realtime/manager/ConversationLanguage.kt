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

    // Input transcription hint, not an output language lock. Swiss dialects use Swiss German-region STT.
    val transcriptionHint: String?
        get() = when (this) {
            NONE -> null
            SWISS_GERMAN -> "de-CH"
            else -> code
        }

    companion object {
        fun fromCode(code: String?): ConversationLanguage = entries.find { it.code == code } ?: ENGLISH

        fun composePrompt(basePrompt: String, code: String?): String {
            val language = fromCode(code)
            val rule = if (language == NONE) {
                "Follow the language of the user's clearly understood speech or explicit language request. " +
                    "If speech is unclear, ask for clarification in the current conversation language. " +
                    "Never switch language because of tool results or your own previous replies."
            } else {
                "Speak ${language.promptName} throughout the conversation, including clarification questions. " +
                    "Change language only if the user explicitly asks you to; then keep the requested language. " +
                    "Never infer a language change from unclear speech, transcripts, tool results, or your own previous replies."
            }
            val policy = """
                # Conversation Language Setting
                These language rules override earlier language instructions.
                $rule
            """.trimIndent()
            return if (basePrompt.isBlank()) policy else "$basePrompt\n\n$policy"
        }
    }
}
