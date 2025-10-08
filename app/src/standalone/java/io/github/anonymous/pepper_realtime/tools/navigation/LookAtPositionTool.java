package io.github.anonymous.pepper_realtime.tools.navigation;

import android.util.Log;
import io.github.anonymous.pepper_realtime.tools.Tool;
import io.github.anonymous.pepper_realtime.tools.ToolContext;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Stub implementation of LookAtPositionTool for standalone mode.
 */
public class LookAtPositionTool implements Tool {
    
    private static final String TAG = "LookAtPositionTool[STUB]";

    @Override
    public String getName() {
        return "look_at_position";
    }

    @Override
    public JSONObject getDefinition() {
        try {
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", getName());
            tool.put("description", "Make Pepper look at a specific 3D position.");
            
            JSONObject params = new JSONObject();
            params.put("type", "object");
            
            JSONObject properties = new JSONObject();
            properties.put("x", new JSONObject().put("type", "number").put("description", "X coordinate"));
            properties.put("y", new JSONObject().put("type", "number").put("description", "Y coordinate"));
            properties.put("z", new JSONObject().put("type", "number").put("description", "Z coordinate"));
            
            params.put("properties", properties);
            params.put("required", new JSONArray().put("x").put("y").put("z"));
            tool.put("parameters", params);
            
            return tool;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool definition", e);
        }
    }

    @Override
    public String execute(JSONObject args, ToolContext context) throws Exception {
        double x = args.optDouble("x", 0);
        double y = args.optDouble("y", 0);
        double z = args.optDouble("z", 0);
        
        Log.i(TAG, String.format("🤖 [SIMULATED] Look at position: (%.2f, %.2f, %.2f)", x, y, z));
        
        return new JSONObject()
            .put("success", true)
            .put("message", String.format("Would look at position (%.2f, %.2f, %.2f)", x, y, z))
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

