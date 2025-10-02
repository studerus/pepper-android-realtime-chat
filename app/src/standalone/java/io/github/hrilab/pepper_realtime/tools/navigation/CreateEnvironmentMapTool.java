package io.github.hrilab.pepper_realtime.tools.navigation;

import android.util.Log;
import io.github.hrilab.pepper_realtime.tools.Tool;
import io.github.hrilab.pepper_realtime.tools.ToolContext;
import org.json.JSONObject;

/**
 * Stub implementation of CreateEnvironmentMapTool for standalone mode.
 */
public class CreateEnvironmentMapTool implements Tool {
    
    private static final String TAG = "CreateEnvironmentMapTool[STUB]";

    @Override
    public String getName() {
        return "create_environment_map";
    }

    @Override
    public JSONObject getDefinition() {
        try {
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", getName());
            tool.put("description", "Start creating a new environment map.");
            
            JSONObject params = new JSONObject();
            params.put("type", "object");
            params.put("properties", new JSONObject());
            
            tool.put("parameters", params);
            
            return tool;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool definition", e);
        }
    }

    @Override
    public String execute(JSONObject args, ToolContext context) throws Exception {
        Log.i(TAG, "🤖 [SIMULATED] Create environment map");
        
        return new JSONObject()
            .put("success", true)
            .put("message", "Would start creating environment map")
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

