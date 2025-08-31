package io.github.studerus.pepper_android_realtime;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Centralized API key management with lazy validation.
 * Keys are only validated when the corresponding feature is actually used.
 */
@SuppressWarnings("SpellCheckingInspection") // API provider names (Groq, Tavily, etc.)
public class ApiKeyManager {
    private static final String TAG = "ApiKeyManager";
    private static final String PREFS_NAME = "PepperDialogPrefs";
    
    private final SharedPreferences settings;
    
    public ApiKeyManager(Context context) {
        this.settings = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
    
    // Core API Keys (required for basic functionality)
    public String getAzureOpenAiKey() {
        return getBuildConfigKey("AZURE_OPENAI_KEY", settings.getString("AZURE_OPENAI_KEY", ""));
    }
    
    public String getAzureOpenAiEndpoint() {
        return getBuildConfigKey("AZURE_OPENAI_ENDPOINT", settings.getString("AZURE_OPENAI_ENDPOINT", ""));
    }
    
    public String getOpenAiApiKey() {
        return getBuildConfigKey("OPENAI_API_KEY", settings.getString("OPENAI_API_KEY", ""));
    }
    
    public String getAzureSpeechKey() {
        return getBuildConfigKey("AZURE_SPEECH_KEY", settings.getString("AZURE_SPEECH_KEY", ""));
    }
    
    public String getAzureSpeechRegion() {
        return getBuildConfigKey("AZURE_SPEECH_REGION", settings.getString("AZURE_SPEECH_REGION", "switzerlandnorth"));
    }
    
    // Optional API Keys (for additional features)
    public String getGroqApiKey() {
        return getBuildConfigKey("GROQ_API_KEY", settings.getString("GROQ_API_KEY", ""));
    }
    
    public String getTavilyApiKey() {
        return getBuildConfigKey("TAVILY_API_KEY", settings.getString("TAVILY_API_KEY", ""));
    }
    
    public String getOpenWeatherApiKey() {
        return getBuildConfigKey("OPENWEATHER_API_KEY", settings.getString("OPENWEATHER_API_KEY", ""));
    }
    
    public String getYouTubeApiKey() {
        return getBuildConfigKey("YOUTUBE_API_KEY", settings.getString("YOUTUBE_API_KEY", ""));
    }
    
    // Validation methods
    public boolean hasValidGroqKey() {
        return isValidKey(getGroqApiKey());
    }
    
    public boolean hasValidTavilyKey() {
        return isValidKey(getTavilyApiKey());
    }
    
    public boolean hasValidOpenWeatherKey() {
        return isValidKey(getOpenWeatherApiKey());
    }
    
    public boolean hasValidYouTubeKey() {
        return isValidKey(getYouTubeApiKey());
    }
    
    // Feature availability checks
    public boolean isVisionAnalysisAvailable() {
        return hasValidGroqKey();
    }
    
    public boolean isInternetSearchAvailable() {
        return hasValidTavilyKey();
    }
    
    public boolean isWeatherAvailable() {
        return hasValidOpenWeatherKey();
    }
    
    public boolean isYouTubeAvailable() {
        return hasValidYouTubeKey();
    }
    
    // Error messages for missing keys
    
    public String getSearchSetupMessage() {
        return "🔑 Internet search requires TAVILY_API_KEY.\n" +
               "Get free key at: https://tavily.com/\n" +
               "Add to local.properties: TAVILY_API_KEY=your_key";
    }
    
    public String getWeatherSetupMessage() {
        return "🔑 Weather requires OPENWEATHER_API_KEY.\n" +
               "Get free key at: https://openweathermap.org/api\n" +
               "Add to local.properties: OPENWEATHER_API_KEY=your_key";
    }
    
    public String getYouTubeSetupMessage() {
        return "🔑 YouTube video requires YOUTUBE_API_KEY.\n" +
               "Get free key at: https://console.developers.google.com/\n" +
               "Enable YouTube Data API v3\n" +
               "Add to local.properties: YOUTUBE_API_KEY=your_key";
    }
    
    // Helper methods
    private String getBuildConfigKey(String keyName, String fallback) {
        try {
            // Try to get from BuildConfig first (for build-time injection)
            String buildConfigValue = (String) BuildConfig.class.getField(keyName).get(null);
            if (isValidKey(buildConfigValue)) {
                return buildConfigValue;
            }
        } catch (Exception e) {
            Log.d(TAG, "No BuildConfig value for " + keyName + ", using fallback");
        }
        
        // Fallback to SharedPreferences or empty
        return fallback;
    }
    
    // ==================== REALTIME API PROVIDER MANAGEMENT ====================
    
    /**
     * Get currently selected Realtime API provider
     * @return Current provider (defaults to AZURE_OPENAI)
     */
    public RealtimeApiProvider getRealtimeApiProvider() {
        String providerName = settings.getString("realtime_api_provider", RealtimeApiProvider.AZURE_OPENAI.name());
        return RealtimeApiProvider.fromString(providerName);
    }
    
    /**
     * Set Realtime API provider
     * @param provider New provider to use
     */
    public void setRealtimeApiProvider(RealtimeApiProvider provider) {
        settings.edit()
                .putString("realtime_api_provider", provider.name())
                .apply();
        Log.i(TAG, "Realtime API provider changed to: " + provider.getDisplayName());
    }
    
    /**
     * Check if the current provider has valid API keys configured
     * @return true if the current provider can be used
     */
    public boolean isCurrentRealtimeProviderConfigured() {
        RealtimeApiProvider provider = getRealtimeApiProvider();
        switch (provider) {
            case AZURE_OPENAI:
                return isValidKey(getAzureOpenAiKey()) && isValidKey(getAzureOpenAiEndpoint());
            case OPENAI_DIRECT:
                return isValidKey(getOpenAiApiKey());
            default:
                return false;
        }
    }
    
    /**
     * Get available providers that are properly configured
     * @return Array of configured providers
     */
    public RealtimeApiProvider[] getConfiguredProviders() {
        java.util.List<RealtimeApiProvider> configured = new java.util.ArrayList<>();
        
        // Check Azure OpenAI
        if (isValidKey(getAzureOpenAiKey()) && isValidKey(getAzureOpenAiEndpoint())) {
            configured.add(RealtimeApiProvider.AZURE_OPENAI);
        }
        
        // Check OpenAI Direct
        if (isValidKey(getOpenAiApiKey())) {
            configured.add(RealtimeApiProvider.OPENAI_DIRECT);
        }
        
        return configured.toArray(new RealtimeApiProvider[0]);
    }
    
    private boolean isValidKey(String key) {
        return key != null && 
               !key.isEmpty() && 
               !key.startsWith("your_") && 
               !key.equals("DEMO_KEY") &&
               !key.equals("your_key_here") &&
               key.length() > 10; // Basic sanity check
    }
}
