package io.github.studerus.pepper_android_realtime.tools.games;

import android.util.Log;
import io.github.studerus.pepper_android_realtime.tools.Tool;
import io.github.studerus.pepper_android_realtime.tools.ToolContext;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Tool for making moves in an active Tic Tac Toe game.
 * AI uses this tool to place its O moves on the game board.
 */
public class TicTacToeMoveTool implements Tool {
    
    private static final String TAG = "TicTacToeMoveTool";

    @Override
    public String getName() {
        return "make_tic_tac_toe_move";
    }

    @Override
    public JSONObject getDefinition() {
        try {
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", getName());
            tool.put("description", "Make a strategic move in the current Tic Tac Toe game. Choose a position from 0-8 where 0=top-left, 1=top-center, 2=top-right, 3=middle-left, 4=center, 5=middle-right, 6=bottom-left, 7=bottom-center, 8=bottom-right. Play strategically: 1) Win immediately if you can complete three in a row, 2) Block the user if they can win on their next move, 3) Take the center (position 4) if available, 4) Take corners (0,2,6,8) over edges. Don't make it too easy for the user to win - play competitively but not perfectly. The user can see the game board visually, so don't describe the board state after your move.");
            
            JSONObject params = new JSONObject();
            params.put("type", "object");
            
            JSONObject properties = new JSONObject();
            properties.put("position", new JSONObject()
                .put("type", "integer")
                .put("description", "Position on the 3x3 board (0-8)")
                .put("minimum", 0)
                .put("maximum", 8));
            
            params.put("properties", properties);
            params.put("required", new JSONArray().put("position"));
            tool.put("parameters", params);
            
            return tool;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool definition", e);
        }
    }

    @Override
    public String execute(JSONObject args, ToolContext context) throws Exception {
        int position = args.getInt("position");
        Log.i(TAG, "AI making Tic Tac Toe move at position: " + position);
        
        // Check if there's an active game
        if (TicTacToeGameManager.hasNoActiveGame()) {
            return new JSONObject()
                .put("error", "No active Tic Tac Toe game found")
                .toString();
        }
        
        // Get current game for validation
        TicTacToeDialog gameDialog = TicTacToeGameManager.getCurrentDialog();
        TicTacToeGame game = gameDialog.getGame();
        
        // Validate move
        if (!game.isValidMove(position)) {
            return new JSONObject()
                .put("error", "Invalid move. Position " + position + " is already occupied or out of bounds.")
                .toString();
        }
        
        // Make AI move using the manager
        boolean success = TicTacToeGameManager.makeAIMove(position);
        
        if (success) {
            return new JSONObject()
                .put("success", "Move confirmed.")
                .toString();
        } else {
            return new JSONObject()
                .put("error", "Failed to make move")
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
