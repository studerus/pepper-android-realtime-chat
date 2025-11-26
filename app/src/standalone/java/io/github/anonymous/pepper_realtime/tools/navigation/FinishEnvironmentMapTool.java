package io.github.anonymous.pepper_realtime.tools.navigation;

import android.util.Log;
import io.github.anonymous.pepper_realtime.tools.BaseTool;
import io.github.anonymous.pepper_realtime.tools.ToolContext;
import org.json.JSONObject;

/**
 * Stub implementation of FinishEnvironmentMapTool for standalone mode.
 */
public class FinishEnvironmentMapTool extends BaseTool {
    
    private static final String TAG = "FinishEnvironmentMapTool[STUB]";

    @Override
    public String getName() {
        return "finish_environment_map";
    }

    @Override
    public JSONObject getDefinition() {
        try {
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", getName());
            tool.put("description", "Finish creating environment map and save it.");
            
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
        Log.i(TAG, "🤖 [SIMULATED] Finish environment map");
        
        return new JSONObject()
            .put("success", true)
            .put("message", "Would finish and save environment map")
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

