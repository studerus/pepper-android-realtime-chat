package ch.fhnw.pepper_realtime.ui.settings

/**
 * Helper class representing a language option with a display name and language code.
 */
data class LanguageOption(
    val displayName: String,
    val code: String
) {
    override fun toString(): String = displayName
}

