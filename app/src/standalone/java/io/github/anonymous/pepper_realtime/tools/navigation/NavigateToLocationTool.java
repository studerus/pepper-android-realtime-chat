package io.github.anonymous.pepper_realtime.tools.navigation;

import android.util.Log;
import io.github.anonymous.pepper_realtime.tools.BaseTool;
import io.github.anonymous.pepper_realtime.tools.ToolContext;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Stub implementation of NavigateToLocationTool for standalone mode.
 */
public class NavigateToLocationTool extends BaseTool {
    
    private static final String TAG = "NavigateToLocationTool[STUB]";

    @Override
    public String getName() {
        return "navigate_to_location";
    }

    @Override
    public JSONObject getDefinition() {
        try {
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", getName());
            tool.put("description", "Navigate to a previously saved location.");
            
            JSONObject params = new JSONObject();
            params.put("type", "object");
            
            JSONObject properties = new JSONObject();
            properties.put("location_name", new JSONObject()
                .put("type", "string")
                .put("description", "Name of the saved location to navigate to"));
            
            params.put("properties", properties);
            params.put("required", new JSONArray().put("location_name"));
            tool.put("parameters", params);
            
            return tool;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool definition", e);
        }
    }

    @Override
    public String execute(JSONObject args, ToolContext context) throws Exception {
        String locationName = args.optString("location_name", "");
        
        Log.i(TAG, "🤖 [SIMULATED] Navigate to location: " + locationName);
        
        return new JSONObject()
            .put("success", true)
            .put("message", "Would navigate to location: " + locationName)
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

