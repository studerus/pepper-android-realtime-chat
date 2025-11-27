package io.github.anonymous.pepper_realtime.manager

import io.github.anonymous.pepper_realtime.network.RealtimeApiProvider
import io.github.anonymous.pepper_realtime.ui.settings.LanguageOption
import io.github.anonymous.pepper_realtime.ui.settings.SettingsViewModel

/**
 * Compatibility wrapper for SettingsManager that delegates to SettingsViewModel.
 * Used during the transition from XML-based settings to Compose.
 */
class SettingsManagerCompat(
    private val viewModel: SettingsViewModel
) {
    fun onDrawerClosed() {
        // Settings are now applied via Compose's DisposableEffect
        // No action needed here
    }

    fun getApiProvider(): RealtimeApiProvider {
        return RealtimeApiProvider.fromString(viewModel.getApiProvider())
    }

    fun getVolume(): Int = viewModel.getVolume()

    fun isUsingRealtimeAudioInput(): Boolean = 
        viewModel.getAudioInputMode() == SettingsRepository.MODE_REALTIME_API

    fun getModel(): String = viewModel.getModel()

    companion object {
        const val MODE_REALTIME_API = "realtime_api"
        const val MODE_AZURE_SPEECH = "azure_speech"

        fun getAvailableLanguages(): List<LanguageOption> {
            return listOf(
                // German variants
                LanguageOption("German (Switzerland)", "de-CH"),
                LanguageOption("German (Germany)", "de-DE"),
                LanguageOption("German (Austria)", "de-AT"),

                // English variants
                LanguageOption("English (United States)", "en-US"),
                LanguageOption("English (United Kingdom)", "en-GB"),
                LanguageOption("English (Australia)", "en-AU"),
                LanguageOption("English (Canada)", "en-CA"),

                // French variants
                LanguageOption("French (Switzerland)", "fr-CH"),
                LanguageOption("French (France)", "fr-FR"),
                LanguageOption("French (Canada)", "fr-CA"),

                // Italian variants
                LanguageOption("Italian (Switzerland)", "it-CH"),
                LanguageOption("Italian (Italy)", "it-IT"),

                // Spanish variants
                LanguageOption("Spanish (Spain)", "es-ES"),
                LanguageOption("Spanish (Mexico)", "es-MX"),

                // Other languages
                LanguageOption("Portuguese (Portugal)", "pt-PT"),
                LanguageOption("Portuguese (Brazil)", "pt-BR"),
                LanguageOption("Dutch (Netherlands)", "nl-NL"),
                LanguageOption("Japanese (Japan)", "ja-JP"),
                LanguageOption("Chinese (Mandarin, Simplified)", "zh-CN"),
                LanguageOption("Korean (South Korea)", "ko-KR"),
                LanguageOption("Russian (Russia)", "ru-RU"),
                LanguageOption("Polish (Poland)", "pl-PL"),
                LanguageOption("Turkish (Turkey)", "tr-TR"),
                LanguageOption("Hindi (India)", "hi-IN")
            )
        }
    }
}

