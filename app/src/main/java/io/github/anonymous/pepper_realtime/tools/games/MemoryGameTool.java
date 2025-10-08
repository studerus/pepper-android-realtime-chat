package io.github.anonymous.pepper_realtime.tools.games;

import android.util.Log;
import io.github.anonymous.pepper_realtime.tools.Tool;
import io.github.anonymous.pepper_realtime.tools.ToolContext;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Tool for starting memory matching games.
 * Creates a grid of cards that users need to match in pairs.
 */
public class MemoryGameTool implements Tool {
    
    private static final String TAG = "MemoryGameTool";

    @Override
    public String getName() {
        return "start_memory_game";
    }

    @Override
    public JSONObject getDefinition() {
        try {
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", getName());
            tool.put("description", "Starts a memory matching game with cards that the user has to match in pairs. The user will see a grid of face-down cards and needs to find matching pairs by flipping two cards at a time.");
            
            JSONObject params = new JSONObject();
            params.put("type", "object");
            
            JSONObject properties = new JSONObject();
            properties.put("difficulty", new JSONObject()
                .put("type", "string")
                .put("description", "Game difficulty level")
                .put("enum", new JSONArray().put("easy").put("medium").put("hard"))
                .put("default", "medium"));
            
            params.put("properties", properties);
            tool.put("parameters", params);
            
            return tool;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool definition", e);
        }
    }

    @Override
    public String execute(JSONObject args, ToolContext context) throws Exception {
        String difficulty = args.optString("difficulty", "medium");
        
        if (context.hasUi()) {
            // Execute UI operation on main thread
            context.getActivity().runOnUiThread(() -> {
                if (!context.getActivity().isFinishing()) {
                    io.github.anonymous.pepper_realtime.tools.games.MemoryGameDialog memoryGame = 
                        new io.github.anonymous.pepper_realtime.tools.games.MemoryGameDialog(
                            context.getActivity(), 
                            context,
                            () -> context.sendAsyncUpdate("Memory game closed", false)
                        );
                    memoryGame.show(difficulty);
                } else {
                    Log.w(TAG, "Not showing memory game dialog because activity is finishing.");
                }
            });
        }
        
        return new JSONObject()
                .put("status", "Memory game started.")
                .put("difficulty", difficulty)
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
