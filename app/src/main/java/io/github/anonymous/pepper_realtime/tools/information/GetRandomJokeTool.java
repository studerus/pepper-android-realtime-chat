package io.github.anonymous.pepper_realtime.tools.information;

import android.util.Log;
import io.github.anonymous.pepper_realtime.tools.Tool;
import io.github.anonymous.pepper_realtime.tools.ToolContext;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * Tool for getting random jokes from the local jokes database.
 * Reads jokes from assets/jokes.json file.
 */
public class GetRandomJokeTool implements Tool {
    
    private static final String TAG = "GetRandomJokeTool";

    @Override
    public String getName() {
        return "get_random_joke";
    }

    @Override
    public JSONObject getDefinition() {
        try {
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", getName());
            tool.put("description", "Returns a random joke. Use this function whenever the user asks for a joke. Do not tell your own jokes, only jokes from the function.");
            
            JSONObject params = new JSONObject();
            params.put("type", "object");
            params.put("properties", new JSONObject()); // empty properties object required by schema
            tool.put("parameters", params);
            
            return tool;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool definition", e);
        }
    }

    @Override
    public String execute(JSONObject args, ToolContext context) throws Exception {
        // Read jokes database and sanitize problematic quotes before JSON parsing
        String json = readJokesFromAssets(context);
        if (json == null || json.isEmpty()) {
            return new JSONObject().put("error", "jokes.json not found").toString();
        }
        
        JSONObject root = new JSONObject(json);
        JSONArray arr = root.optJSONArray("jokes");
        if (arr == null || arr.length() == 0) {
            return new JSONObject().put("error", "No jokes available").toString();
        }
        
        Random rnd = new Random();
        JSONObject joke = arr.getJSONObject(rnd.nextInt(arr.length()));
        
        JSONObject result = new JSONObject();
        result.put("id", joke.optInt("id"));
        result.put("text", joke.optString("text"));
        return result.toString();
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public String getApiKeyType() {
        return null;
    }

    /**
     * Read jokes from assets folder
     */
    private String readJokesFromAssets(ToolContext context) {
        try {
            try (java.io.InputStream is = context.getAppContext().getAssets().open("jokes.json");
                 BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                return sb.toString();
            }
        } catch (Exception e) {
            Log.e(TAG, "readJokesFromAssets failed: jokes.json", e);
            return null;
        }
    }
}
