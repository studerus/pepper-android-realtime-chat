package io.github.anonymous.pepper_realtime.tools;

import org.json.JSONObject;

/**
 * Base interface for all Pepper robot tools/functions.
 * Each tool provides its own definition and execution logic.
 */
public interface Tool {
    
    /**
     * Get the unique name/identifier of this tool
     * @return Tool name (e.g., "move_pepper", "play_animation")
     */
    String getName();
    
    /**
     * Get the JSON schema definition for this tool for AI integration
     * @return JSONObject containing the tool definition for Azure OpenAI
     */
    JSONObject getDefinition();
    
    /**
     * Execute the tool with the given parameters
     * @param args JSON arguments passed from AI
     * @param context Shared context with dependencies
     * @return JSON result string
     * @throws Exception if tool execution fails
     */
    String execute(JSONObject args, ToolContext context) throws Exception;
    
    /**
     * Check if this tool requires an API key to function
     * @return true if API key is required, false otherwise
     */
    boolean requiresApiKey();
    
    /**
     * Get the type of API key required (if any)
     * @return API key type (e.g., "OpenWeatherMap", "Tavily") or null if none required
     */
    String getApiKeyType();
    
    /**
     * Check if this tool is currently available based on context
     * @param context Tool context to check availability
     * @return true if tool can be executed, false if disabled/unavailable
     */
    default boolean isAvailable(ToolContext context) {
        if (requiresApiKey()) {
            // Each tool needs to implement its own availability check
            // based on the specific ApiKeyManager methods
            String apiKeyType = getApiKeyType();
            if ("Tavily".equals(apiKeyType)) {
                return context.getApiKeyManager().isInternetSearchAvailable();
            } else if ("OpenWeatherMap".equals(apiKeyType)) {
                return context.getApiKeyManager().isWeatherAvailable();
            } else if ("YouTube".equals(apiKeyType)) {
                return context.getApiKeyManager().isYouTubeAvailable();
            } else if ("Groq".equals(apiKeyType)) {
                return context.getApiKeyManager().isVisionAnalysisAvailable();
            }
            return false; // Unknown API key type
        }
        return true;
    }
}
