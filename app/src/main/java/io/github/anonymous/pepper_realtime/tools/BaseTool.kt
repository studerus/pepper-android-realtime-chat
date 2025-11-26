package io.github.anonymous.pepper_realtime.tools

import org.json.JSONObject

/**
 * Abstract base class for tools that provides default implementation of isAvailable.
 * Java tools should extend this class instead of implementing Tool directly.
 */
abstract class BaseTool : Tool {

    /**
     * Default implementation that checks API key availability based on getApiKeyType().
     * Override this method if your tool has custom availability logic.
     */
    override fun isAvailable(context: ToolContext): Boolean {
        if (!requiresApiKey()) return true

        return when (getApiKeyType()) {
            "Tavily" -> context.apiKeyManager.isInternetSearchAvailable()
            "OpenWeatherMap" -> context.apiKeyManager.isWeatherAvailable()
            "YouTube" -> context.apiKeyManager.isYouTubeAvailable()
            "Groq" -> context.apiKeyManager.isVisionAnalysisAvailable()
            else -> false // Unknown API key type
        }
    }
}

