package io.github.anonymous.pepper_realtime.tools.navigation;

import android.util.Log;
import io.github.anonymous.pepper_realtime.tools.Tool;
import io.github.anonymous.pepper_realtime.tools.ToolContext;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Stub implementation of TurnPepperTool for standalone mode.
 * Logs turn commands without executing them.
 */
public class TurnPepperTool implements Tool {
    
    private static final String TAG = "TurnPepperTool[STUB]";

    @Override
    public String getName() {
        return "turn_pepper";
    }

    @Override
    public JSONObject getDefinition() {
        try {
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", getName());
            tool.put("description", "Turn Pepper robot left or right by a specific number of degrees. Use this when the user asks Pepper to turn or rotate.");
            
            JSONObject params = new JSONObject();
            params.put("type", "object");
            
            JSONObject properties = new JSONObject();
            properties.put("direction", new JSONObject()
                .put("type", "string")
                .put("description", "Direction to turn")
                .put("enum", new JSONArray().put("left").put("right")));
            properties.put("degrees", new JSONObject()
                .put("type", "number")
                .put("description", "Degrees to turn (15-180)")
                .put("minimum", 15)
                .put("maximum", 180));
            properties.put("speed", new JSONObject()
                .put("type", "number")
                .put("description", "Optional turning speed in rad/s (0.1-1.0)")
                .put("minimum", 0.1)
                .put("maximum", 1.0)
                .put("default", 0.5));
            
            params.put("properties", properties);
            params.put("required", new JSONArray().put("direction").put("degrees"));
            tool.put("parameters", params);
            
            return tool;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool definition", e);
        }
    }

    @Override
    public String execute(JSONObject args, ToolContext context) throws Exception {
        String direction = args.optString("direction", "");
        double degrees = args.optDouble("degrees", 0);
        double speed = args.optDouble("speed", 0.5);
        
        Log.i(TAG, String.format("🤖 [SIMULATED] Turn %s by %.1f degrees at speed %.2f rad/s", 
            direction, degrees, speed));
        
        return new JSONObject()
            .put("success", true)
            .put("message", String.format("Would turn %s by %.0f degrees", direction, degrees))
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

