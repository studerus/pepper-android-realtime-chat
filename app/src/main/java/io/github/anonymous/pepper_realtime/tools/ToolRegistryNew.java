package io.github.anonymous.pepper_realtime.tools;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

// Information tools
import io.github.anonymous.pepper_realtime.tools.information.GetDateTimeTool;
import io.github.anonymous.pepper_realtime.tools.information.GetRandomJokeTool;
import io.github.anonymous.pepper_realtime.tools.information.SearchInternetTool;
import io.github.anonymous.pepper_realtime.tools.information.GetWeatherTool;

// Entertainment tools
import io.github.anonymous.pepper_realtime.tools.entertainment.PlayAnimationTool;
import io.github.anonymous.pepper_realtime.tools.entertainment.PlayYouTubeVideoTool;

// Navigation tools
import io.github.anonymous.pepper_realtime.tools.navigation.MovePepperTool;
import io.github.anonymous.pepper_realtime.tools.navigation.TurnPepperTool;
import io.github.anonymous.pepper_realtime.tools.navigation.LookAtPositionTool;
import io.github.anonymous.pepper_realtime.tools.navigation.CreateEnvironmentMapTool;
import io.github.anonymous.pepper_realtime.tools.navigation.FinishEnvironmentMapTool;
import io.github.anonymous.pepper_realtime.tools.navigation.SaveCurrentLocationTool;
import io.github.anonymous.pepper_realtime.tools.navigation.NavigateToLocationTool;

// Game tools
import io.github.anonymous.pepper_realtime.tools.games.MemoryGameTool;
import io.github.anonymous.pepper_realtime.tools.games.QuizTool;
import io.github.anonymous.pepper_realtime.tools.games.TicTacToeStartTool;
import io.github.anonymous.pepper_realtime.tools.games.TicTacToeMoveTool;

// Vision tools
import io.github.anonymous.pepper_realtime.tools.vision.AnalyzeVisionTool;

import io.github.anonymous.pepper_realtime.tools.navigation.ApproachHumanTool;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import io.github.anonymous.pepper_realtime.data.SavedLocation;

/**
 * New tool registry using factory pattern.
 * Manages tool instances, definitions, and availability.
 */
public class ToolRegistryNew {
    
    private static final String TAG = "ToolRegistryNew";
    
    // Factory for creating tool instances
    private final Map<String, ToolFactory> toolFactories = new HashMap<>();
    
    /**
     * Functional interface for tool creation
     */
    @FunctionalInterface
    public interface ToolFactory {
        Tool createTool();
    }
    
    public ToolRegistryNew() {
        registerAllTools();
    }
    
    /**
     * Register all available tools with their factories
     */
    private void registerAllTools() {
        // Information tools
        registerTool("get_current_datetime", GetDateTimeTool::new);
        registerTool("get_random_joke", GetRandomJokeTool::new);
        registerTool("search_internet", SearchInternetTool::new);
        registerTool("get_weather", GetWeatherTool::new);
        
        // Entertainment tools
        registerTool("play_animation", PlayAnimationTool::new);
        registerTool("play_youtube_video", PlayYouTubeVideoTool::new);
        
        // Navigation tools
        registerTool("move_pepper", MovePepperTool::new);
        registerTool("turn_pepper", TurnPepperTool::new);
        registerTool("look_at_position", LookAtPositionTool::new);
        registerTool("create_environment_map", CreateEnvironmentMapTool::new);
        registerTool("finish_environment_map", FinishEnvironmentMapTool::new);
        registerTool("save_current_location", SaveCurrentLocationTool::new);
        registerTool("navigate_to_location", NavigateToLocationTool::new);
        registerTool("approach_human", ApproachHumanTool::new);
        
        // Game tools
        registerTool("start_memory_game", MemoryGameTool::new);
        registerTool("present_quiz_question", QuizTool::new);
        registerTool("start_tic_tac_toe_game", TicTacToeStartTool::new);
        registerTool("make_tic_tac_toe_move", TicTacToeMoveTool::new);
        
        // Vision tools
        registerTool("analyze_vision", AnalyzeVisionTool::new);
        
        Log.i(TAG, "Registered " + toolFactories.size() + " tools across all categories");
    }
    
    /**
     * Register a tool with its factory
     */
    private void registerTool(String name, ToolFactory factory) {
        toolFactories.put(name, factory);
    }
    
    /**
     * Create a tool instance by name
     */
    public Tool createTool(String name) {
        ToolFactory factory = toolFactories.get(name);
        if (factory == null) {
            Log.w(TAG, "Unknown tool requested: " + name);
            return null;
        }
        return factory.createTool();
    }
    

    
    /**
     * Get all registered tool names
     */
    public Set<String> getAllToolNames() {
        return toolFactories.keySet();
    }
    

    
    /**
     * Build tools definition for enabled tools (used by both Azure and OpenAI)
     */
    public JSONArray buildToolsDefinitionForAzure(ToolContext context, Set<String> enabledTools) {
        JSONArray tools = new JSONArray();
        
        for (String toolName : toolFactories.keySet()) {
            // Skip if tool is not in enabled set
            if (enabledTools != null && !enabledTools.contains(toolName)) {
                continue;
            }
            
            Tool tool = createTool(toolName);
            if (tool == null) {
                continue;
            }
            
            // Check if tool is available (e.g., API keys)
            if (!tool.isAvailable(context)) {
                Log.d(TAG, "Tool '" + toolName + "' not available, skipping");
                continue;
            }
            
            try {
                JSONObject toolDefinition = tool.getDefinition();
                
                // For navigate_to_location tool, dynamically add available locations to description
                if ("navigate_to_location".equals(toolName)) {
                    enhanceNavigationToolDescription(toolDefinition, context);
                }
                
                tools.put(toolDefinition);
                Log.d(TAG, "Registered tool: " + toolName);
            } catch (Exception e) {
                Log.e(TAG, "Failed to get definition for tool: " + toolName, e);
            }
        }
        
        Log.i(TAG, "Built tools definition with " + tools.length() + " tools");
        return tools;
    }
    
    /**
     * Enhance navigation tool description with available locations
     */
    private void enhanceNavigationToolDescription(JSONObject toolDefinition, ToolContext context) {
        try {
            Log.d(TAG, "Enhancing navigation tool description...");

            // Logic from getAvailableLocations is now inlined here
            if (context == null || context.getLocationProvider() == null) {
                Log.w(TAG, "LocationProvider not available via ToolContext. Cannot enhance description.");
                return;
            }
            List<SavedLocation> savedLocations = context.getLocationProvider().getSavedLocations();
            List<String> availableLocations = new ArrayList<>();
            for (SavedLocation loc : savedLocations) {
                availableLocations.add(loc.name);
            }
            Log.i(TAG, "Fetched " + availableLocations.size() + " locations from LocationProvider for tool definition.");

            String baseDescription = "Navigate Pepper to a previously saved location. Use this when the user wants to go to a specific named place.";
            
            if (availableLocations.isEmpty()) {
                toolDefinition.put("description", baseDescription + " Currently no saved locations available - user needs to save locations first. Additional locations saved during this session can also be used.");
                Log.i(TAG, "Enhanced navigation tool - no locations available, but session locations can be added");
            } else {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < availableLocations.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(availableLocations.get(i));
                }
                String locationsText = sb.toString();
                toolDefinition.put("description", baseDescription + " Available saved locations: " + locationsText + ". Additional locations saved during this session can also be used.");
                
                Log.i(TAG, "Enhanced navigation tool with " + availableLocations.size() + " saved locations: " + locationsText + " (plus any locations saved during session)");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to enhance navigation tool description", e);
        }
    }
    
    /**
     * Execute a tool by name
     */
    public String executeTool(String toolName, JSONObject args, ToolContext context) {
        Tool tool = createTool(toolName);
        if (tool == null) {
            try {
                return new JSONObject().put("error", "Unknown tool: " + toolName).toString();
            } catch (Exception e) {
                return "{\"error\":\"Unknown tool and failed to create error response\"}";
            }
        }
        
        try {
            return tool.execute(args, context);
        } catch (Exception e) {
            Log.e(TAG, "Tool execution error: " + toolName, e);
            try {
                return new JSONObject().put("error", e.getMessage()).toString();
            } catch (Exception ignored) {
                return "{\"error\":\"execution failed\"}";
            }
        }
    }

}
