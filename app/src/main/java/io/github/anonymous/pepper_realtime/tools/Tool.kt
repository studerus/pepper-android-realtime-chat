package io.github.anonymous.pepper_realtime.tools

import org.json.JSONObject

/**
 * Base interface for all Pepper robot tools/functions.
 * Each tool provides its own definition and execution logic.
 */
interface Tool {
    /**
     * Get the unique name/identifier of this tool
     * @return Tool name (e.g., "move_pepper", "play_animation")
     */
    fun getName(): String

    /**
     * Get the JSON schema definition for this tool for AI integration
     * @return JSONObject containing the tool definition for Azure OpenAI
     */
    fun getDefinition(): JSONObject

    /**
     * Execute the tool with the given parameters
     * @param args JSON arguments passed from AI
     * @param context Shared context with dependencies
     * @return JSON result string
     * @throws Exception if tool execution fails
     */
    @Throws(Exception::class)
    fun execute(args: JSONObject, context: ToolContext): String

    /**
     * Check if this tool requires an API key to function
     * @return true if API key is required, false otherwise
     */
    fun requiresApiKey(): Boolean

    /**
     * Get the type of API key required (if any)
     * @return API key type (e.g., "OpenWeatherMap", "Tavily") or null if none required
     */
    fun getApiKeyType(): String?

    /**
     * Check if this tool is currently available based on context.
     * Default implementation checks API key availability based on getApiKeyType().
     *
     * @param context Tool context to check availability
     * @return true if tool can be executed, false if disabled/unavailable
     */
    fun isAvailable(context: ToolContext): Boolean {
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

