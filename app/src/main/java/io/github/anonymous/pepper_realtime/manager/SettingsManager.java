package io.github.anonymous.pepper_realtime.manager;

import android.view.View;
import android.widget.LinearLayout;

import java.util.HashSet;
import java.util.Set;

import io.github.anonymous.pepper_realtime.ui.ChatActivity;
import io.github.anonymous.pepper_realtime.ui.settings.AzureSettingsUiController;
import io.github.anonymous.pepper_realtime.ui.settings.GeneralSettingsUiController;
import io.github.anonymous.pepper_realtime.ui.settings.LanguageOption;
import io.github.anonymous.pepper_realtime.ui.settings.RealtimeSettingsUiController;
import io.github.anonymous.pepper_realtime.ui.settings.SettingsViewModel;
import io.github.anonymous.pepper_realtime.ui.settings.ToolsSettingsUiController;

@SuppressWarnings("SpellCheckingInspection")
public class SettingsManager {

    public static final String MODE_REALTIME_API = "realtime_api";
    public static final String MODE_AZURE_SPEECH = "azure_speech";

    private final SettingsViewModel viewModel;
    private final GeneralSettingsUiController generalSettings;
    private final RealtimeSettingsUiController realtimeSettings;
    private final AzureSettingsUiController azureSettings;
    private final ToolsSettingsUiController toolsSettings;

    public SettingsManager(ChatActivity activity, View settingsView, SettingsViewModel viewModel) {
        this.viewModel = viewModel;
        this.generalSettings = new GeneralSettingsUiController(activity, settingsView, viewModel);
        this.realtimeSettings = new RealtimeSettingsUiController(activity, settingsView, viewModel);
        this.azureSettings = new AzureSettingsUiController(activity, settingsView, viewModel);
        this.toolsSettings = new ToolsSettingsUiController(activity, settingsView, viewModel);

        // Register callback to update visibility when audio input mode changes
        generalSettings.setVisibilityUpdateCallback(() -> updateVisibility());

        // Initial visibility setup
        updateVisibility();
    }

    public void onDrawerClosed() {
        // Apply changes from all controllers
        generalSettings.applyChanges();
        realtimeSettings.applyChanges();
        azureSettings.applyChanges();
        toolsSettings.applyChanges();

        // Update visibility based on new settings
        updateVisibility();

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

    private void updateVisibility() {
        boolean isRealtime = generalSettings.isRealtimeAudioModeSelected();
        realtimeSettings.setVisibility(isRealtime);
        azureSettings.setVisibility(!isRealtime);
    }

    // Legacy helper for languages
    public static java.util.List<LanguageOption> getAvailableLanguages() {
        java.util.List<LanguageOption> languages = new java.util.ArrayList<>();

        // German variants
        languages.add(new LanguageOption("German (Switzerland)", "de-CH"));
        languages.add(new LanguageOption("German (Germany)", "de-DE"));
        languages.add(new LanguageOption("German (Austria)", "de-AT"));

        // English variants
        languages.add(new LanguageOption("English (United States)", "en-US"));
        languages.add(new LanguageOption("English (United Kingdom)", "en-GB"));
        languages.add(new LanguageOption("English (Australia)", "en-AU"));
        languages.add(new LanguageOption("English (Canada)", "en-CA"));

        // French variants
        languages.add(new LanguageOption("French (Switzerland)", "fr-CH"));
        languages.add(new LanguageOption("French (France)", "fr-FR"));
        languages.add(new LanguageOption("French (Canada)", "fr-CA"));

        // Italian variants
        languages.add(new LanguageOption("Italian (Switzerland)", "it-CH"));
        languages.add(new LanguageOption("Italian (Italy)", "it-IT"));

        // Spanish variants
        languages.add(new LanguageOption("Spanish (Spain)", "es-ES"));
        languages.add(new LanguageOption("Spanish (Mexico)", "es-MX"));

        // Other languages
        languages.add(new LanguageOption("Portuguese (Portugal)", "pt-PT"));
        languages.add(new LanguageOption("Portuguese (Brazil)", "pt-BR"));
        languages.add(new LanguageOption("Dutch (Netherlands)", "nl-NL"));
        languages.add(new LanguageOption("Japanese (Japan)", "ja-JP"));
        languages.add(new LanguageOption("Chinese (Mandarin, Simplified)", "zh-CN"));
        languages.add(new LanguageOption("Korean (South Korea)", "ko-KR"));
        languages.add(new LanguageOption("Russian (Russia)", "ru-RU"));
        languages.add(new LanguageOption("Polish (Poland)", "pl-PL"));
        languages.add(new LanguageOption("Turkish (Turkey)", "tr-TR"));
        languages.add(new LanguageOption("Hindi (India)", "hi-IN"));

        return languages;
    }

    // Getter methods that delegate to ViewModel for backward compatibility
    public io.github.anonymous.pepper_realtime.network.RealtimeApiProvider getApiProvider() {
        return io.github.anonymous.pepper_realtime.network.RealtimeApiProvider.fromString(
                viewModel.getApiProvider());
    }

    public int getVolume() {
        return viewModel.getVolume();
    }

    public boolean isUsingRealtimeAudioInput() {
        return generalSettings.isRealtimeAudioModeSelected();
    }

    public String getModel() {
        return viewModel.getModel();
    }
}
