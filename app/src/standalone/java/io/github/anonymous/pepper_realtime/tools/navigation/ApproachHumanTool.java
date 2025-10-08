package io.github.anonymous.pepper_realtime.tools.navigation;

import android.util.Log;
import io.github.anonymous.pepper_realtime.tools.Tool;
import io.github.anonymous.pepper_realtime.tools.ToolContext;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Stub implementation of ApproachHumanTool for standalone mode.
 */
public class ApproachHumanTool implements Tool {
    
    private static final String TAG = "ApproachHumanTool[STUB]";

    @Override
    public String getName() {
        return "approach_human";
    }

    @Override
    public JSONObject getDefinition() {
        try {
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", getName());
            tool.put("description", "Approach the nearest detected human.");
            
            JSONObject params = new JSONObject();
            params.put("type", "object");
            
            JSONObject properties = new JSONObject();
            properties.put("distance", new JSONObject()
                .put("type", "number")
                .put("description", "Target distance in meters (0.5-2.0)")
                .put("minimum", 0.5)
                .put("maximum", 2.0)
                .put("default", 1.0));
            
            params.put("properties", properties);
            params.put("required", new JSONArray());
            tool.put("parameters", params);
            
            return tool;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool definition", e);
        }
    }

    @Override
    public String execute(JSONObject args, ToolContext context) throws Exception {
        double distance = args.optDouble("distance", 1.0);
        
        Log.i(TAG, String.format("🤖 [SIMULATED] Approach human to %.2fm distance", distance));
        
        return new JSONObject()
            .put("success", true)
            .put("message", String.format("Would approach nearest human to %.1fm distance", distance))
            .toString();
    }
    
    @Override
    public boolean requiresApiKey() {
        return false;
    }
    
    @Override
    public String getApiKeyType() {
        return null;
    }
}

