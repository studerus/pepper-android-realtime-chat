package ch.fhnw.pepper_realtime.network

import android.util.Log

const val XAI_THINK_FAST_MODEL = "grok-voice-think-fast-1.0"
const val XAI_FAST_MODEL = "grok-voice-fast-1.0"
const val XAI_LEGACY_MODEL_LABEL = "Grok Voice Agent"

/**
 * Enumeration of available Realtime API providers
 * Supports Azure OpenAI, direct OpenAI, and x.ai connections
 */
enum class RealtimeApiProvider(
    private val displayName: String,
    private val modelName: String
) {
    AZURE_OPENAI("Azure OpenAI", "gpt-4o-realtime-preview"),
    OPENAI_DIRECT("OpenAI Direct", "gpt-realtime-1.5"),
    XAI("x.ai Grok", XAI_THINK_FAST_MODEL),
    // Google Live API requires models/ prefix for BidiGenerateContent
    GOOGLE_GEMINI("Google Gemini", "models/gemini-3.1-flash-live-preview");

    fun getDisplayName(): String = displayName

    /**
     * Get WebSocket URL for this provider
     *
     * @param azureEndpoint Azure endpoint (only used for Azure provider)
     * @param model         Specific model to use (overrides default modelName)
     * @param googleApiKey  Google API key (only used for Google provider, passed as URL param)
     * @return WebSocket URL
     */
    fun getWebSocketUrl(azureEndpoint: String?, model: String?, googleApiKey: String? = null): String {
        val requestedModel = if (!model.isNullOrBlank()) model.trim() else modelName
        val actualModel = if (this == XAI && requestedModel == XAI_LEGACY_MODEL_LABEL) {
            XAI_THINK_FAST_MODEL
        } else {
            requestedModel
        }
        return when (this) {
            AZURE_OPENAI -> if (isOpenAiGaRealtimeModel(actualModel)) {
                // Azure GA Realtime endpoint
                "wss://$azureEndpoint/openai/v1/realtime?model=$actualModel"
            } else {
                // Azure preview endpoint
                "wss://$azureEndpoint/openai/realtime?api-version=2025-04-01-preview&deployment=$actualModel"
            }
            OPENAI_DIRECT -> "wss://api.openai.com/v1/realtime?model=$actualModel"
            XAI -> "wss://api.x.ai/v1/realtime?model=$actualModel"
            // v1alpha is required for newer native audio models
            GOOGLE_GEMINI -> "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=${googleApiKey ?: ""}"
        }
    }

    /**
     * Check if this provider requires Azure configuration
     *
     * @return true if Azure keys are needed
     */
    fun isAzureProvider(): Boolean = this == AZURE_OPENAI

    /**
     * Check if this is the Google Gemini provider
     */
    fun isGoogleProvider(): Boolean = this == GOOGLE_GEMINI

    /**
     * Check if this provider requires an Authorization header
     * Google uses API key in URL instead
     */
    fun requiresAuthHeader(): Boolean = this != GOOGLE_GEMINI

    /**
     * Get authentication header name for this provider
     * Azure uses "api-key", OpenAI and x.ai use "Authorization"
     * Google doesn't use auth headers (API key is in URL)
     */
    fun getAuthHeaderName(): String = when (this) {
        AZURE_OPENAI -> "api-key"
        OPENAI_DIRECT, XAI -> "Authorization"
        GOOGLE_GEMINI -> "" // No header needed - API key is in URL
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
            GOOGLE_GEMINI -> "" // No header needed - API key is in URL
        }
    }

    /**
     * Return display name for UI presentation (e.g., in spinners)
     */
    override fun toString(): String = displayName

    companion object {
        private const val TAG = "RealtimeApiProvider"
        private val OPENAI_GA_REALTIME_PREFIX = "gpt-realtime"

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

        /**
         * Returns true for OpenAI Realtime GA models that use the newer session schema.
         * Examples: gpt-realtime, gpt-realtime-mini, gpt-realtime-1.5, and their snapshots.
         */
        fun isOpenAiGaRealtimeModel(model: String?): Boolean {
            if (model.isNullOrBlank()) return false
            val normalized = model.trim().lowercase()
            return normalized.startsWith(OPENAI_GA_REALTIME_PREFIX) && !normalized.contains("preview")
        }

        /**
         * Returns true for Gemini 3.1+ models which use different API semantics
         * (thinkingLevel instead of thinkingBudget, realtimeInput for text, no proactive audio, etc.)
         */
        fun isGemini31Model(model: String?): Boolean {
            if (model.isNullOrBlank()) return false
            val normalized = model.trim().lowercase()
            return normalized.contains("gemini-3")
        }
    }
}
