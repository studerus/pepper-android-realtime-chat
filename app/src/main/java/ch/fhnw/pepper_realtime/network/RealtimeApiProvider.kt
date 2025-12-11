package ch.fhnw.pepper_realtime.network

import android.util.Log

/**
 * Enumeration of available Realtime API providers
 * Supports both Azure OpenAI and direct OpenAI connections
 */
enum class RealtimeApiProvider(
    private val displayName: String,
    private val modelName: String
) {
    AZURE_OPENAI("Azure OpenAI", "gpt-4o-realtime-preview"),
    OPENAI_DIRECT("OpenAI Direct", "gpt-realtime");

    fun getDisplayName(): String = displayName

    /**
     * Get WebSocket URL for this provider
     *
     * @param azureEndpoint Azure endpoint (only used for Azure provider)
     * @param model         Specific model to use (overrides default modelName)
     * @return WebSocket URL
     */
    fun getWebSocketUrl(azureEndpoint: String?, model: String?): String {
        val actualModel = if (!model.isNullOrEmpty()) model else modelName
        return when (this) {
            AZURE_OPENAI -> "wss://$azureEndpoint/openai/realtime?api-version=2024-10-01-preview&deployment=$actualModel"
            OPENAI_DIRECT -> "wss://api.openai.com/v1/realtime?model=$actualModel"
        }
    }

    /**
     * Check if this provider requires Azure configuration
     *
     * @return true if Azure keys are needed
     */
    fun isAzureProvider(): Boolean = this == AZURE_OPENAI

    /**
     * Get authentication header value for this provider
     *
     * @param azureKey  Azure OpenAI key (for Azure provider)
     * @param openaiKey Direct OpenAI key (for OpenAI provider)
     * @return Authorization header value
     */
    fun getAuthorizationHeader(azureKey: String?, openaiKey: String?): String {
        return when (this) {
            AZURE_OPENAI -> azureKey ?: ""
            OPENAI_DIRECT -> "Bearer ${openaiKey ?: ""}"
        }
    }

    /**
     * Return display name for UI presentation (e.g., in spinners)
     */
    override fun toString(): String = displayName

    companion object {
        private const val TAG = "RealtimeApiProvider"

        /**
         * Parse provider from string (for settings persistence)
         *
         * @param value String representation
         * @return Provider enum or default (AZURE_OPENAI)
         */
        fun fromString(value: String?): RealtimeApiProvider {
            if (value == null) return AZURE_OPENAI

            return try {
                valueOf(value)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Unknown provider: $value, using default")
                AZURE_OPENAI
            }
        }
    }
}


