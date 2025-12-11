package ch.fhnw.pepper_realtime.tools

/**
 * Represents whether a tool requires an API key to function.
 * Uses sealed class for type-safe handling of API key requirements.
 */
sealed class ApiKeyRequirement {
    /**
     * Tool does not require any API key
     */
    data object None : ApiKeyRequirement()

    /**
     * Tool requires an API key of a specific type
     */
    data class Required(val type: ApiKeyType) : ApiKeyRequirement()
}

/**
 * Types of API keys that tools can require
 */
enum class ApiKeyType {
    TAVILY,
    OPENWEATHER,
    YOUTUBE,
    GROQ;

    /**
     * Check if this API key type is available in the given context
     */
    fun isAvailable(context: ToolContext): Boolean {
        return when (this) {
            TAVILY -> context.apiKeyManager.isInternetSearchAvailable()
            OPENWEATHER -> context.apiKeyManager.isWeatherAvailable()
            YOUTUBE -> context.apiKeyManager.isYouTubeAvailable()
            GROQ -> context.apiKeyManager.isVisionAnalysisAvailable()
        }
    }
}

