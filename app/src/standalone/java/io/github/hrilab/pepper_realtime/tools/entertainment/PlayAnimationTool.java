package io.github.hrilab.pepper_realtime.tools.entertainment;

import android.util.Log;
import io.github.hrilab.pepper_realtime.tools.Tool;
import io.github.hrilab.pepper_realtime.tools.ToolContext;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Stub implementation of PlayAnimationTool for standalone mode.
 */
public class PlayAnimationTool implements Tool {
    
    private static final String TAG = "PlayAnimationTool[STUB]";

    @Override
    public String getName() {
        return "play_animation";
    }

    @Override
    public JSONObject getDefinition() {
        try {
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", getName());
            tool.put("description", "Play an animation on Pepper robot.");
            
            JSONObject params = new JSONObject();
            params.put("type", "object");
            
            JSONObject properties = new JSONObject();
            properties.put("animation_name", new JSONObject()
                .put("type", "string")
                .put("description", "Name of animation to play")
                .put("enum", new JSONArray()
                    .put("wave")
                    .put("bow")
                    .put("shrug")
                    .put("think")
                    .put("celebrate")));
            
            params.put("properties", properties);
            params.put("required", new JSONArray().put("animation_name"));
            tool.put("parameters", params);
            
            return tool;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool definition", e);
        }
    }

    @Override
    public String execute(JSONObject args, ToolContext context) throws Exception {
        String animationName = args.optString("animation_name", "");
        
        Log.i(TAG, "🤖 [SIMULATED] Play animation: " + animationName);
        
        return new JSONObject()
            .put("success", true)
            .put("message", "Would play animation: " + animationName)
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

