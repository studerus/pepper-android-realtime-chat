package ch.fhnw.pepper_realtime.manager

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.network.RealtimeApiProvider
import ch.fhnw.pepper_realtime.tools.ToolRegistry
import ch.fhnw.pepper_realtime.ui.settings.LanguageOption
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val toolRegistry: ToolRegistry
) {
    private val settings: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // Lazy-load default system prompt from assets file (preserves formatting)
    private val defaultSystemPrompt: String by lazy {
        try {
            context.assets.open("default_system_prompt.txt").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e("SettingsRepository", "Failed to load default system prompt from assets", e)
            "You are Pepper, a friendly robot assistant."
        }
    }

    var systemPrompt: String
        get() = settings.getString(KEY_SYSTEM_PROMPT, defaultSystemPrompt) ?: defaultSystemPrompt
        set(value) = settings.edit().putString(KEY_SYSTEM_PROMPT, value).apply()

    var model: String
        get() {
            val defaultModel = when (apiProviderEnum) {
                RealtimeApiProvider.XAI -> "Grok Voice Agent"
                // Google Live API requires models/ prefix for BidiGenerateContent
                RealtimeApiProvider.GOOGLE_GEMINI -> "models/gemini-2.5-flash-native-audio-preview-12-2025"
                else -> context.getString(R.string.openai_default_model)
            }
            return settings.getString(KEY_MODEL, defaultModel) ?: defaultModel
        }
        set(value) = settings.edit().putString(KEY_MODEL, value).apply()

    var voice: String
        get() {
            val defaultVoice = when (apiProviderEnum) {
                RealtimeApiProvider.XAI -> "Ara"
                RealtimeApiProvider.GOOGLE_GEMINI -> "Puck"
                else -> "ash"
            }
            return settings.getString(KEY_VOICE, defaultVoice) ?: defaultVoice
        }
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
            val knownTools = settings.getStringSet(KEY_KNOWN_TOOLS, null)

            return if (savedTools == null) {
                // First time - enable all defaults and mark them as known
                settings.edit().putStringSet(KEY_KNOWN_TOOLS, defaultTools).apply()
                defaultTools
            } else {
                // Check for truly NEW tools (not known before, not just disabled)
                val newTools = defaultTools.filter { it !in (knownTools ?: emptySet()) }
                if (newTools.isNotEmpty()) {
                    val mergedTools = HashSet(savedTools)
                    for (newTool in newTools) {
                        mergedTools.add(newTool)
                        Log.i("SettingsRepository", "Auto-enabling new tool: $newTool")
                    }
                    // Update known tools
                    settings.edit().putStringSet(KEY_KNOWN_TOOLS, defaultTools).apply()
                    enabledTools = mergedTools
                    mergedTools
                } else {
                    savedTools
                }
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

    // ==================== Google Live API Settings ====================
    
    // VAD Sensitivity: "LOW" or "HIGH"
    var googleStartSensitivity: String
        get() = settings.getString(KEY_GOOGLE_START_SENSITIVITY, "HIGH") ?: "HIGH"
        set(value) = settings.edit().putString(KEY_GOOGLE_START_SENSITIVITY, value).apply()
    
    var googleEndSensitivity: String
        get() = settings.getString(KEY_GOOGLE_END_SENSITIVITY, "HIGH") ?: "HIGH"
        set(value) = settings.edit().putString(KEY_GOOGLE_END_SENSITIVITY, value).apply()
    
    var googlePrefixPaddingMs: Int
        get() = settings.getInt(KEY_GOOGLE_PREFIX_PADDING_MS, 20)
        set(value) = settings.edit().putInt(KEY_GOOGLE_PREFIX_PADDING_MS, value).apply()
    
    var googleSilenceDurationMs: Int
        get() = settings.getInt(KEY_GOOGLE_SILENCE_DURATION_MS, 500)
        set(value) = settings.edit().putInt(KEY_GOOGLE_SILENCE_DURATION_MS, value).apply()
    
    // Thinking budget: 0 = disabled, >0 = token budget for thinking
    var googleThinkingBudget: Int
        get() = settings.getInt(KEY_GOOGLE_THINKING_BUDGET, 0)
        set(value) = settings.edit().putInt(KEY_GOOGLE_THINKING_BUDGET, value).apply()
    
    // Affective dialog: enables emotional speech output
    var googleAffectiveDialog: Boolean
        get() = settings.getBoolean(KEY_GOOGLE_AFFECTIVE_DIALOG, false)
        set(value) = settings.edit().putBoolean(KEY_GOOGLE_AFFECTIVE_DIALOG, value).apply()
    
    // Proactive audio: allows Gemini to proactively decide not to respond when content is not relevant
    var googleProactiveAudio: Boolean
        get() = settings.getBoolean(KEY_GOOGLE_PROACTIVE_AUDIO, false)
        set(value) = settings.edit().putBoolean(KEY_GOOGLE_PROACTIVE_AUDIO, value).apply()
    
    // Show thinking traces in chat (only works when thinking budget > 0)
    var googleShowThinking: Boolean
        get() = settings.getBoolean(KEY_GOOGLE_SHOW_THINKING, false)
        set(value) = settings.edit().putBoolean(KEY_GOOGLE_SHOW_THINKING, value).apply()
    
    // Google Search grounding - enables web search for improved accuracy
    var googleSearchGrounding: Boolean
        get() = settings.getBoolean(KEY_GOOGLE_SEARCH_GROUNDING, false)
        set(value) = settings.edit().putBoolean(KEY_GOOGLE_SEARCH_GROUNDING, value).apply()
    
    // Context window compression - enables unlimited session length via sliding window
    var googleContextCompression: Boolean
        get() = settings.getBoolean(KEY_GOOGLE_CONTEXT_COMPRESSION, true)  // Default true for better UX
        set(value) = settings.edit().putBoolean(KEY_GOOGLE_CONTEXT_COMPRESSION, value).apply()
    
    // x.ai Web Search - native web search capability
    var xaiWebSearch: Boolean
        get() = settings.getBoolean(KEY_XAI_WEB_SEARCH, true)  // Default true for backwards compatibility
        set(value) = settings.edit().putBoolean(KEY_XAI_WEB_SEARCH, value).apply()
    
    // x.ai X Search - search X/Twitter posts
    var xaiXSearch: Boolean
        get() = settings.getBoolean(KEY_XAI_X_SEARCH, true)  // Default true for backwards compatibility
        set(value) = settings.edit().putBoolean(KEY_XAI_X_SEARCH, value).apply()

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
        private const val KEY_KNOWN_TOOLS = "knownTools"  // Tracks which tools user has seen (to distinguish new vs disabled)
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

        // Google Live API specific settings
        private const val KEY_GOOGLE_START_SENSITIVITY = "googleStartSensitivity"
        private const val KEY_GOOGLE_END_SENSITIVITY = "googleEndSensitivity"
        private const val KEY_GOOGLE_PREFIX_PADDING_MS = "googlePrefixPaddingMs"
        private const val KEY_GOOGLE_SILENCE_DURATION_MS = "googleSilenceDurationMs"
        private const val KEY_GOOGLE_THINKING_BUDGET = "googleThinkingBudget"
        private const val KEY_GOOGLE_AFFECTIVE_DIALOG = "googleAffectiveDialog"
        private const val KEY_GOOGLE_PROACTIVE_AUDIO = "googleProactiveAudio"
        private const val KEY_GOOGLE_SHOW_THINKING = "googleShowThinking"
        private const val KEY_GOOGLE_SEARCH_GROUNDING = "googleSearchGrounding"
        private const val KEY_GOOGLE_CONTEXT_COMPRESSION = "googleContextCompression"
        private const val KEY_XAI_WEB_SEARCH = "xaiWebSearch"
        private const val KEY_XAI_X_SEARCH = "xaiXSearch"

        // Audio input mode constants
        const val MODE_REALTIME_API = "realtime_api"
        const val MODE_AZURE_SPEECH = "azure_speech"

        fun getAvailableLanguages(): List<LanguageOption> = listOf(
            // English variants
            LanguageOption("English (United States)", "en-US"),
            LanguageOption("English (United Kingdom)", "en-GB"),
            LanguageOption("English (Australia)", "en-AU"),
            LanguageOption("English (Canada)", "en-CA"),

            // German variants
            LanguageOption("German (Germany)", "de-DE"),
            LanguageOption("German (Austria)", "de-AT"),
            LanguageOption("German (Switzerland)", "de-CH"),

            // French variants
            LanguageOption("French (France)", "fr-FR"),
            LanguageOption("French (Canada)", "fr-CA"),
            LanguageOption("French (Switzerland)", "fr-CH"),

            // Italian variants
            LanguageOption("Italian (Italy)", "it-IT"),
            LanguageOption("Italian (Switzerland)", "it-CH"),

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
