package io.github.anonymous.pepper_realtime.tools.information;

import android.util.Log;
import io.github.anonymous.pepper_realtime.network.HttpClientManager;
import io.github.anonymous.pepper_realtime.tools.Tool;
import io.github.anonymous.pepper_realtime.tools.ToolContext;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Tool for searching the internet using Tavily API.
 * Provides real-time information that the AI doesn't have in its training data.
 */
public class SearchInternetTool implements Tool {
    
    private static final String TAG = "SearchInternetTool";

    @Override
    public String getName() {
        return "search_internet";
    }

    @Override
    public JSONObject getDefinition() {
        try {
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", getName());
            tool.put("description", "Searches the internet for facts, information, or people that you cannot answer from memory. Always respond to the user before you perform the function. Always first tell the user to wait a moment and then immediately perform the function.");
            
            JSONObject params = new JSONObject();
            params.put("type", "object");
            
            JSONObject properties = new JSONObject();
            properties.put("query", new JSONObject()
                .put("type", "string")
                .put("description", "The search query"));
            
            params.put("properties", properties);
            params.put("required", new JSONArray().put("query"));
            tool.put("parameters", params);
            
            return tool;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool definition", e);
        }
    }

    @Override
    public String execute(JSONObject args, ToolContext context) throws Exception {
        String query = args != null ? args.optString("query", "") : "";
        if (query.isEmpty()) {
            return new JSONObject().put("error", "Missing required parameter: query").toString();
        }

        if (!context.getApiKeyManager().isInternetSearchAvailable()) {
            return new JSONObject().put("error", context.getApiKeyManager().getSearchSetupMessage()).toString();
        }

        String apiKey = context.getApiKeyManager().getTavilyApiKey();

        // Use optimized shared client for better performance and connection reuse
        OkHttpClient client = HttpClientManager.getInstance().getQuickApiClient();

        JSONObject payload = new JSONObject();
        payload.put("api_key", apiKey);
        payload.put("query", query);
        payload.put("search_depth", "basic");
        payload.put("include_answer", true);
        payload.put("max_results", 3);

        RequestBody body = RequestBody.create(payload.toString(), MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url("https://api.tavily.com/search")
                .post(body)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return new JSONObject().put("error", "Internet search failed: HTTP " + response.code()).toString();
            }
            
            okhttp3.ResponseBody responseBody = response.body();
            if (responseBody == null) {
                return new JSONObject().put("error", "Internet search failed: Empty response body").toString();
            }
            
            String resp = responseBody.string();
            JSONObject data = new JSONObject(resp);
            String result = data.optString("answer", "");
            
            if (result.isEmpty()) {
                JSONArray results = data.optJSONArray("results");
                if (results != null && results.length() > 0) {
                    JSONObject first = results.getJSONObject(0);
                    result = first.optString("content", "");
                }
            }
            
            if (result.isEmpty()) {
                result = "Sorry, I couldn't find any information about that.";
            }
            
            return new JSONObject().put("answer", result).toString();
        } catch (Exception e) {
            Log.e(TAG, "Error in internet search", e);
            return new JSONObject().put("error", e.getMessage() != null ? e.getMessage() : "Failed to search internet").toString();
        }
    }

    @Override
    public boolean requiresApiKey() {
        return true;
    }

    @Override
    public String getApiKeyType() {
        return "Tavily";
    }
}
