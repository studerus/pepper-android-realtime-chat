package io.github.anonymous.pepper_realtime.manager

import android.view.View
import io.github.anonymous.pepper_realtime.network.RealtimeApiProvider
import io.github.anonymous.pepper_realtime.ui.ChatActivity
import io.github.anonymous.pepper_realtime.ui.settings.AzureSettingsUiController
import io.github.anonymous.pepper_realtime.ui.settings.GeneralSettingsUiController
import io.github.anonymous.pepper_realtime.ui.settings.LanguageOption
import io.github.anonymous.pepper_realtime.ui.settings.RealtimeSettingsUiController
import io.github.anonymous.pepper_realtime.ui.settings.SettingsViewModel
import io.github.anonymous.pepper_realtime.ui.settings.ToolsSettingsUiController

@Suppress("SpellCheckingInspection")
class SettingsManager(
    activity: ChatActivity,
    settingsView: View,
    private val viewModel: SettingsViewModel
) {
    private val generalSettings = GeneralSettingsUiController(activity, settingsView, viewModel)
    private val realtimeSettings = RealtimeSettingsUiController(activity, settingsView, viewModel)
    private val azureSettings = AzureSettingsUiController(activity, settingsView, viewModel)
    private val toolsSettings = ToolsSettingsUiController(activity, settingsView, viewModel)

    init {
        // Register callback to update visibility when audio input mode changes
        generalSettings.setVisibilityUpdateCallback { updateVisibility() }

        // Initial visibility setup
        updateVisibility()
    }

    fun onDrawerClosed() {
        // Apply changes from all controllers
        generalSettings.applyChanges()
        realtimeSettings.applyChanges()
        azureSettings.applyChanges()
        toolsSettings.applyChanges()

        // Update visibility based on new settings
        updateVisibility()

        // Note: The ViewModel events will trigger the actual logic in ChatActivity.
        // The listener here is kept if we want to manually trigger things,
        // but ideally ChatActivity should observe the ViewModel.
        // However, to maintain backward compatibility during refactoring,
        // we might want to trigger listener methods if we can detect changes here,
        // OR we rely on ChatActivity observing ViewModel.
        // Given the plan, we should rely on ViewModel events.
        // But ChatActivity still expects this listener to be called for some things?
        // Let's assume ChatActivity will be updated to observe ViewModel events.
    }

    private fun updateVisibility() {
        val isRealtime = generalSettings.isRealtimeAudioModeSelected
        realtimeSettings.setVisibility(isRealtime)
        azureSettings.setVisibility(!isRealtime)
    }

    // Getter methods that delegate to ViewModel for backward compatibility
    fun getApiProvider(): RealtimeApiProvider {
        return RealtimeApiProvider.fromString(viewModel.getApiProvider())
    }

    fun getVolume(): Int = viewModel.getVolume()

    fun isUsingRealtimeAudioInput(): Boolean = generalSettings.isRealtimeAudioModeSelected

    fun getModel(): String = viewModel.getModel()

    companion object {
        const val MODE_REALTIME_API = "realtime_api"
        const val MODE_AZURE_SPEECH = "azure_speech"

        // Legacy helper for languages
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


