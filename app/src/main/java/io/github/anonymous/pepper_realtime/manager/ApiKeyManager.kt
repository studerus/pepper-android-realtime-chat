package io.github.anonymous.pepper_realtime.manager

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.anonymous.pepper_realtime.BuildConfig
import io.github.anonymous.pepper_realtime.network.RealtimeApiProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized API key management with lazy validation.
 * Keys are only validated when the corresponding feature is actually used.
 */
@Suppress("SpellCheckingInspection") // API provider names (Groq, Tavily, etc.)
@Singleton
class ApiKeyManager @Inject constructor(context: Context) {

    companion object {
        private const val TAG = "ApiKeyManager"
        private const val PREFS_NAME = "PepperDialogPrefs"
    }

    private val settings: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Core API Keys (required for basic functionality)
    val azureOpenAiKey: String
        get() = getBuildConfigKey("AZURE_OPENAI_KEY", settings.getString("AZURE_OPENAI_KEY", "") ?: "")

    val azureOpenAiEndpoint: String
        get() = getBuildConfigKey("AZURE_OPENAI_ENDPOINT", settings.getString("AZURE_OPENAI_ENDPOINT", "") ?: "")

    val openAiApiKey: String
        get() = getBuildConfigKey("OPENAI_API_KEY", settings.getString("OPENAI_API_KEY", "") ?: "")

    val azureSpeechKey: String
        get() = getBuildConfigKey("AZURE_SPEECH_KEY", settings.getString("AZURE_SPEECH_KEY", "") ?: "")

    val azureSpeechRegion: String
        get() = getBuildConfigKey("AZURE_SPEECH_REGION", settings.getString("AZURE_SPEECH_REGION", "switzerlandnorth") ?: "switzerlandnorth")

    // Optional API Keys (for additional features)
    val groqApiKey: String
        get() = getBuildConfigKey("GROQ_API_KEY", settings.getString("GROQ_API_KEY", "") ?: "")

    val tavilyApiKey: String
        get() = getBuildConfigKey("TAVILY_API_KEY", settings.getString("TAVILY_API_KEY", "") ?: "")

    val openWeatherApiKey: String
        get() = getBuildConfigKey("OPENWEATHER_API_KEY", settings.getString("OPENWEATHER_API_KEY", "") ?: "")

    val youTubeApiKey: String
        get() = getBuildConfigKey("YOUTUBE_API_KEY", settings.getString("YOUTUBE_API_KEY", "") ?: "")

    // Validation methods
    fun hasValidTavilyKey(): Boolean = isValidKey(tavilyApiKey)

    fun hasValidOpenWeatherKey(): Boolean = isValidKey(openWeatherApiKey)

    fun hasValidYouTubeKey(): Boolean = isValidKey(youTubeApiKey)

    // Feature availability checks
    val isVisionAnalysisAvailable: Boolean
        get() = true // Always available now (gpt-realtime has built-in vision)

    val isInternetSearchAvailable: Boolean
        get() = hasValidTavilyKey()

    val isWeatherAvailable: Boolean
        get() = hasValidOpenWeatherKey()

    val isYouTubeAvailable: Boolean
        get() = hasValidYouTubeKey()

    // Error messages for missing keys
    val searchSetupMessage: String
        get() = """
            🔑 Internet search requires TAVILY_API_KEY.
            Get free key at: https://tavily.com/
            Add to local.properties: TAVILY_API_KEY=your_key
        """.trimIndent()

    val weatherSetupMessage: String
        get() = """
            🔑 Weather requires OPENWEATHER_API_KEY.
            Get free key at: https://openweathermap.org/api
            Add to local.properties: OPENWEATHER_API_KEY=your_key
        """.trimIndent()

    val youTubeSetupMessage: String
        get() = """
            🔑 YouTube video requires YOUTUBE_API_KEY.
            Get free key at: https://console.developers.google.com/
            Enable YouTube Data API v3
            Add to local.properties: YOUTUBE_API_KEY=your_key
        """.trimIndent()

    // Helper methods
    private fun getBuildConfigKey(keyName: String, fallback: String): String {
        return try {
            // Try to get from BuildConfig first (for build-time injection)
            val buildConfigValue = BuildConfig::class.java.getField(keyName).get(null) as? String
            if (isValidKey(buildConfigValue)) {
                buildConfigValue!!
            } else {
                fallback
            }
        } catch (e: Exception) {
            Log.d(TAG, "No BuildConfig value for $keyName, using fallback")
            fallback
        }
    }

    // ==================== REALTIME API PROVIDER MANAGEMENT ====================

    /**
     * Get available providers that are properly configured
     * @return Array of configured providers
     */
    fun getConfiguredProviders(): Array<RealtimeApiProvider> {
        val configured = mutableListOf<RealtimeApiProvider>()

        // Check Azure OpenAI
        if (isValidKey(azureOpenAiKey) && isValidKey(azureOpenAiEndpoint)) {
            configured.add(RealtimeApiProvider.AZURE_OPENAI)
        }

        // Check OpenAI Direct
        if (isValidKey(openAiApiKey)) {
            configured.add(RealtimeApiProvider.OPENAI_DIRECT)
        }

        return configured.toTypedArray()
    }

    private fun isValidKey(key: String?): Boolean {
        return key != null &&
                key.isNotEmpty() &&
                !key.startsWith("your_") &&
                key != "DEMO_KEY" &&
                key != "your_key_here" &&
                key.length > 10 // Basic sanity check
    }
}

