package ch.fhnw.pepper_realtime.ui.settings

/**
 * Immutable state holder for all settings.
 * Used with StateFlow for reactive UI updates in Compose.
 */
data class SettingsState(
    // Core Settings
    val systemPrompt: String = "",
    val model: String = "",
    val voice: String = "ash",
    val speedProgress: Int = 100,
    val language: String = "en-US",
    val apiProvider: String = "OPENAI_DIRECT",
    val audioInputMode: String = "realtime_api",
    val temperatureProgress: Int = 33,
    val volume: Int = 80,
    val silenceTimeout: Int = 500,
    val confidenceThreshold: Float = 0.7f,
    val enabledTools: Set<String> = emptySet(),
    
    // Realtime API Settings (OpenAI)
    val transcriptionModel: String = "whisper-1",
    val transcriptionLanguage: String = "",
    val transcriptionPrompt: String = "",
    val turnDetectionType: String = "server_vad",
    val vadThreshold: Float = 0.5f,
    val prefixPadding: Int = 300,
    val silenceDuration: Int = 500,
    val idleTimeout: Int? = null,
    val eagerness: String = "auto",
    val noiseReduction: String = "off",
    
    // Google Live API Settings
    val googleStartSensitivity: String = "HIGH",
    val googleEndSensitivity: String = "HIGH",
    val googlePrefixPaddingMs: Int = 20,
    val googleSilenceDurationMs: Int = 500,
    val googleThinkingBudget: Int = 0,
    val googleAffectiveDialog: Boolean = false,
    val googleProactiveAudio: Boolean = false,
    val googleShowThinking: Boolean = false,
    val googleSearchGrounding: Boolean = false,
    // x.ai specific settings
    val xaiWebSearch: Boolean = true,
    val xaiXSearch: Boolean = true
)

