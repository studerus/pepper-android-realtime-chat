package io.github.anonymous.pepper_realtime.manager

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.anonymous.pepper_realtime.R
import io.github.anonymous.pepper_realtime.network.RealtimeApiProvider
import io.github.anonymous.pepper_realtime.tools.ToolRegistry
import io.github.anonymous.pepper_realtime.ui.settings.LanguageOption
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val toolRegistry: ToolRegistry
) {
    private val settings: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSettings(): SharedPreferences = settings

    var systemPrompt: String
        get() = settings.getString(KEY_SYSTEM_PROMPT, context.getString(R.string.default_system_prompt)) ?: ""
        set(value) = settings.edit().putString(KEY_SYSTEM_PROMPT, value).apply()

    var model: String
        get() = settings.getString(KEY_MODEL, context.getString(R.string.openai_default_model)) ?: ""
        set(value) = settings.edit().putString(KEY_MODEL, value).apply()

    var voice: String
        get() = settings.getString(KEY_VOICE, "ash") ?: "ash"
        set(value) = settings.edit().putString(KEY_VOICE, value).apply()

    val speed: Float
        get() {
            val speedProgress = settings.getInt(KEY_SPEED, 100)
            return speedProgress / 100f // Convert 25-150 to 0.25-1.5
        }

    var speedProgress: Int
        get() = settings.getInt(KEY_SPEED, 100)
        set(value) = settings.edit().putInt(KEY_SPEED, value).apply()

    var language: String
        get() = settings.getString(KEY_LANGUAGE, "en-US") ?: "en-US"
        set(value) = settings.edit().putString(KEY_LANGUAGE, value).apply()

    var audioInputMode: String
        get() = settings.getString(KEY_AUDIO_INPUT_MODE, MODE_REALTIME_API) ?: MODE_REALTIME_API
        set(value) = settings.edit().putString(KEY_AUDIO_INPUT_MODE, value).apply()

    val isUsingRealtimeAudioInput: Boolean
        get() = MODE_REALTIME_API == audioInputMode

    var volume: Int
        get() = settings.getInt(KEY_VOLUME, 80)
        set(value) = settings.edit().putInt(KEY_VOLUME, value).apply()

    var silenceTimeout: Int
        get() = settings.getInt(KEY_SILENCE_TIMEOUT, 500)
        set(value) = settings.edit().putInt(KEY_SILENCE_TIMEOUT, value).apply()

    val temperature: Float
        get() {
            var tempProgress = settings.getInt(KEY_TEMPERATURE, 33)
            if (tempProgress < 0 || tempProgress > 100) tempProgress = 33
            return 0.6f + (tempProgress / 100.0f) * 0.6f
        }

    var temperatureProgress: Int
        get() = settings.getInt(KEY_TEMPERATURE, 33)
        set(value) = settings.edit().putInt(KEY_TEMPERATURE, value).apply()

    var confidenceThreshold: Float
        get() = settings.getFloat(KEY_CONFIDENCE_THRESHOLD, 0.7f)
        set(value) = settings.edit().putFloat(KEY_CONFIDENCE_THRESHOLD, value).apply()

    var apiProvider: String
        get() = settings.getString(KEY_API_PROVIDER, RealtimeApiProvider.OPENAI_DIRECT.name)
            ?: RealtimeApiProvider.OPENAI_DIRECT.name
        set(value) = settings.edit().putString(KEY_API_PROVIDER, value).apply()

    val apiProviderEnum: RealtimeApiProvider
        get() = RealtimeApiProvider.fromString(apiProvider)

    var enabledTools: Set<String>
        get() {
            val defaultTools = defaultEnabledTools
            val savedTools = settings.getStringSet(KEY_ENABLED_TOOLS, null)

            return if (savedTools == null) {
                defaultTools
            } else {
                val mergedTools = HashSet(savedTools)
                for (defaultTool in defaultTools) {
                    if (!mergedTools.contains(defaultTool)) {
                        mergedTools.add(defaultTool)
                        Log.i("SettingsRepository", "Auto-enabling new tool: $defaultTool")
                    }
                }
                if (mergedTools != savedTools) {
                    enabledTools = mergedTools
                }
                mergedTools
            }
        }
        set(value) = settings.edit().putStringSet(KEY_ENABLED_TOOLS, value).apply()

    private val defaultEnabledTools: Set<String>
        get() = HashSet(toolRegistry.getAllToolNames())

    // Realtime API Settings
    var transcriptionModel: String
        get() = settings.getString(KEY_TRANSCRIPTION_MODEL, "whisper-1") ?: "whisper-1"
        set(value) = settings.edit().putString(KEY_TRANSCRIPTION_MODEL, value).apply()

    var transcriptionLanguage: String
        get() = settings.getString(KEY_TRANSCRIPTION_LANGUAGE, "") ?: ""
        set(value) = settings.edit().putString(KEY_TRANSCRIPTION_LANGUAGE, value).apply()

    var transcriptionPrompt: String
        get() = settings.getString(KEY_TRANSCRIPTION_PROMPT, "") ?: ""
        set(value) = settings.edit().putString(KEY_TRANSCRIPTION_PROMPT, value).apply()

    var turnDetectionType: String
        get() = settings.getString(KEY_TURN_DETECTION_TYPE, "server_vad") ?: "server_vad"
        set(value) = settings.edit().putString(KEY_TURN_DETECTION_TYPE, value).apply()

    var vadThreshold: Float
        get() = settings.getFloat(KEY_VAD_THRESHOLD, 0.5f)
        set(value) = settings.edit().putFloat(KEY_VAD_THRESHOLD, value).apply()

    var prefixPadding: Int
        get() = settings.getInt(KEY_PREFIX_PADDING, 300)
        set(value) = settings.edit().putInt(KEY_PREFIX_PADDING, value).apply()

    var silenceDuration: Int
        get() = settings.getInt(KEY_SILENCE_DURATION, 500)
        set(value) = settings.edit().putInt(KEY_SILENCE_DURATION, value).apply()

    var idleTimeout: Int?
        get() = settings.getInt(KEY_IDLE_TIMEOUT, 0)
        set(value) = settings.edit().putInt(KEY_IDLE_TIMEOUT, value ?: 0).apply()

    var noiseReduction: String
        get() = settings.getString(KEY_NOISE_REDUCTION, "off") ?: "off"
        set(value) = settings.edit().putString(KEY_NOISE_REDUCTION, value).apply()

    var eagerness: String
        get() = settings.getString(KEY_EAGERNESS, "auto") ?: "auto"
        set(value) = settings.edit().putString(KEY_EAGERNESS, value).apply()

    companion object {
        private const val PREFS_NAME = "PepperDialogPrefs"
        private const val KEY_SYSTEM_PROMPT = "systemPrompt"
        private const val KEY_MODEL = "model"
        private const val KEY_VOICE = "voice"
        private const val KEY_SPEED = "speed"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_VOLUME = "volume"
        private const val KEY_SILENCE_TIMEOUT = "silenceTimeout"
        private const val KEY_ENABLED_TOOLS = "enabledTools"
        private const val KEY_API_PROVIDER = "apiProvider"
        private const val KEY_CONFIDENCE_THRESHOLD = "confidenceThreshold"
        private const val KEY_AUDIO_INPUT_MODE = "audioInputMode"

        // Realtime API specific settings
        private const val KEY_TRANSCRIPTION_MODEL = "transcriptionModel"
        private const val KEY_TRANSCRIPTION_LANGUAGE = "transcriptionLanguage"
        private const val KEY_TRANSCRIPTION_PROMPT = "transcriptionPrompt"
        private const val KEY_TURN_DETECTION_TYPE = "turnDetectionType"
        private const val KEY_VAD_THRESHOLD = "vadThreshold"
        private const val KEY_PREFIX_PADDING = "prefixPadding"
        private const val KEY_SILENCE_DURATION = "silenceDuration"
        private const val KEY_IDLE_TIMEOUT = "idleTimeout"
        private const val KEY_NOISE_REDUCTION = "noiseReduction"
        private const val KEY_EAGERNESS = "eagerness"

        // Audio input mode constants
        const val MODE_REALTIME_API = "realtime_api"
        const val MODE_AZURE_SPEECH = "azure_speech"

        fun getAvailableLanguages(): List<LanguageOption> = listOf(
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
