package io.github.anonymous.pepper_realtime.tools.navigation;

import android.util.Log;
import io.github.anonymous.pepper_realtime.tools.Tool;
import io.github.anonymous.pepper_realtime.tools.ToolContext;
import org.json.JSONObject;

/**
 * Stub implementation of MovePepperTool for standalone mode.
 */
public class MovePepperTool implements Tool {
    
    private static final String TAG = "MovePepperTool[STUB]";

    @Override
    public String getName() {
        return "move_pepper";
    }

    @Override
    public JSONObject getDefinition() {
        try {
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", getName());
            tool.put("description", "Move Pepper robot in a specific direction for a given distance. Use this when the user asks Pepper to move around the room. Call the function directly without announcing it. You can combine forward/backward and sideways movements.");
            
            JSONObject params = new JSONObject();
            params.put("type", "object");
            
            JSONObject properties = new JSONObject();
            properties.put("distance_forward", new JSONObject()
                .put("type", "number")
                .put("description", "Distance to move forward (positive) or backward (negative) in meters (-4.0 to 4.0). Optional, defaults to 0.")
                .put("minimum", -4.0)
                .put("maximum", 4.0));
            properties.put("distance_sideways", new JSONObject()
                .put("type", "number")
                .put("description", "Distance to move left (positive) or right (negative) in meters (-4.0 to 4.0). Optional, defaults to 0.")
                .put("minimum", -4.0)
                .put("maximum", 4.0));
            properties.put("speed", new JSONObject()
                .put("type", "number")
                .put("description", "Optional maximum speed in m/s (0.1-0.55)")
                .put("minimum", 0.1)
                .put("maximum", 0.55)
                .put("default", 0.4));
            
            params.put("properties", properties);
            tool.put("parameters", params);
            
            return tool;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool definition", e);
        }
    }

    @Override
    public String execute(JSONObject args, ToolContext context) throws Exception {
        double distanceForward = args.optDouble("distance_forward", 0.0);
        double distanceSideways = args.optDouble("distance_sideways", 0.0);
        double speed = args.optDouble("speed", 0.4);
        
        // Validate that at least one movement is requested
        if (distanceForward == 0.0 && distanceSideways == 0.0) {
            return new JSONObject().put("error", "Please provide a non-zero distance for 'distance_forward' or 'distance_sideways'.").toString();
        }
        
        Log.i(TAG, String.format("🤖 [SIMULATED] Move - Forward: %.2fm, Sideways: %.2fm, Speed: %.2fm/s", 
            distanceForward, distanceSideways, speed));
        
        return new JSONObject()
            .put("success", true)
            .put("message", String.format(java.util.Locale.US, "Would move forward %.2fm, sideways %.2fm at speed %.2fm/s", 
                distanceForward, distanceSideways, speed))
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

