package io.github.anonymous.pepper_realtime;

import android.util.Log;

/**
 * Enumeration of available Realtime API providers
 * Supports both Azure OpenAI and direct OpenAI connections
 */
public enum RealtimeApiProvider {
    AZURE_OPENAI("Azure OpenAI", "gpt-4o-realtime-preview"),
    OPENAI_DIRECT("OpenAI Direct", "gpt-realtime");
    
    private final String displayName;
    private final String modelName;
    
    RealtimeApiProvider(String displayName, String modelName) {
        this.displayName = displayName;
        this.modelName = modelName;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    
    /**
     * Get WebSocket URL for this provider
     * @param azureEndpoint Azure endpoint (only used for Azure provider)
     * @param model Specific model to use (overrides default modelName)
     * @return WebSocket URL
     */
    public String getWebSocketUrl(String azureEndpoint, String model) {
        String actualModel = (model != null && !model.isEmpty()) ? model : modelName;
        switch (this) {
            case AZURE_OPENAI:
                return String.format("wss://%s/openai/realtime?api-version=2024-10-01-preview&deployment=%s", 
                                    azureEndpoint, actualModel);
            case OPENAI_DIRECT:
                return String.format("wss://api.openai.com/v1/realtime?model=%s", actualModel);
            default:
                throw new IllegalStateException("Unknown provider: " + this);
        }
    }
    
    
    /**
     * Check if this provider requires Azure configuration
     * @return true if Azure keys are needed
     */
    public boolean isAzureProvider() {
        return this == AZURE_OPENAI;
    }
    
    /**
     * Get authentication header value for this provider
     * @param azureKey Azure OpenAI key (for Azure provider)
     * @param openaiKey Direct OpenAI key (for OpenAI provider)
     * @return Authorization header value
     */
    public String getAuthorizationHeader(String azureKey, String openaiKey) {
        switch (this) {
            case AZURE_OPENAI:
                return azureKey;
            case OPENAI_DIRECT:
                return "Bearer " + openaiKey;
            default:
                throw new IllegalStateException("Unknown provider: " + this);
        }
    }
    
    /**
     * Parse provider from string (for settings persistence)
     * @param value String representation
     * @return Provider enum or default (AZURE_OPENAI)
     */
    public static RealtimeApiProvider fromString(String value) {
        if (value == null) return AZURE_OPENAI;
        
        try {
            return RealtimeApiProvider.valueOf(value);
        } catch (IllegalArgumentException e) {
            Log.w("RealtimeApiProvider", "Unknown provider: " + value + ", using default");
            return AZURE_OPENAI;
        }
    }
}
