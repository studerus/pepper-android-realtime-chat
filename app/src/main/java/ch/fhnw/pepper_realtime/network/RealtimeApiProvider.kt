package ch.fhnw.pepper_realtime.network

import android.util.Log

/**
 * Enumeration of available Realtime API providers
 * Supports Azure OpenAI, direct OpenAI, and x.ai connections
 */
enum class RealtimeApiProvider(
    private val displayName: String,
    private val modelName: String
) {
    AZURE_OPENAI("Azure OpenAI", "gpt-4o-realtime-preview"),
    OPENAI_DIRECT("OpenAI Direct", "gpt-4o-realtime-preview"),
    XAI("x.ai Grok", "Grok Voice Agent");

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
            XAI -> "wss://api.x.ai/v1/realtime"
        }
    }

    /**
     * Check if this provider requires Azure configuration
     *
     * @return true if Azure keys are needed
     */
    fun isAzureProvider(): Boolean = this == AZURE_OPENAI

    /**
     * Check if this is the x.ai provider
     */
    fun isXaiProvider(): Boolean = this == XAI

    /**
     * Get authentication header name for this provider
     * Azure uses "api-key", OpenAI and x.ai use "Authorization"
     */
    fun getAuthHeaderName(): String = when (this) {
        AZURE_OPENAI -> "api-key"
        OPENAI_DIRECT, XAI -> "Authorization"
    }

    /**
     * Get authentication header value for this provider
     *
     * @param azureKey  Azure OpenAI key (for Azure provider)
     * @param openaiKey Direct OpenAI key (for OpenAI provider)
     * @param xaiKey    x.ai API key (for XAI provider)
     * @return Authorization header value
     */
    fun getAuthorizationHeader(azureKey: String?, openaiKey: String?, xaiKey: String? = null): String {
        return when (this) {
            AZURE_OPENAI -> azureKey ?: ""
            OPENAI_DIRECT -> "Bearer ${openaiKey ?: ""}"
            XAI -> "Bearer ${xaiKey ?: ""}"
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


