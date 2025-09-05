package io.github.studerus.pepper_android_realtime.tools.games;

import android.util.Log;
import io.github.studerus.pepper_android_realtime.tools.Tool;
import io.github.studerus.pepper_android_realtime.tools.ToolContext;

import org.json.JSONObject;

/**
 * Tool for starting a new Tic Tac Toe game.
 * Opens the game dialog and initializes a new game where user is X and AI is O.
 */
public class TicTacToeStartTool implements Tool {
    
    private static final String TAG = "TicTacToeStartTool";

    @Override
    public String getName() {
        return "start_tic_tac_toe_game";
    }

    @Override
    public JSONObject getDefinition() {
        try {
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", getName());
            tool.put("description", "Start a new Tic Tac Toe game with the user. The user will be X and you will be O. Call this function when the user wants to play Tic Tac Toe. Call the function directly without announcing it.");
            
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
        Log.i(TAG, "Starting Tic Tac Toe game");
        
        // Start game using the manager
        boolean success = TicTacToeGameManager.startNewGame(context);
        
        if (success) {
            return new JSONObject()
                .put("success", "Tic Tac Toe game started! You are X, I am O. Make your first move!")
                .toString();
        } else {
            return new JSONObject()
                .put("error", "Failed to start Tic Tac Toe game - UI not available")
                .toString();
        }
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
