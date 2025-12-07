package io.github.anonymous.pepper_realtime.tools

import android.util.Log
import io.github.anonymous.pepper_realtime.tools.entertainment.PlayAnimationTool
import io.github.anonymous.pepper_realtime.tools.entertainment.PlayYouTubeVideoTool
import io.github.anonymous.pepper_realtime.tools.games.DrawingGameTool
import io.github.anonymous.pepper_realtime.tools.games.MemoryGameTool
import io.github.anonymous.pepper_realtime.tools.games.QuizTool
import io.github.anonymous.pepper_realtime.tools.games.TicTacToeMoveTool
import io.github.anonymous.pepper_realtime.tools.games.TicTacToeStartTool
import io.github.anonymous.pepper_realtime.tools.information.GetDateTimeTool
import io.github.anonymous.pepper_realtime.tools.information.GetRandomJokeTool
import io.github.anonymous.pepper_realtime.tools.information.GetWeatherTool
import io.github.anonymous.pepper_realtime.tools.information.SearchInternetTool
import io.github.anonymous.pepper_realtime.tools.navigation.ApproachHumanTool
import io.github.anonymous.pepper_realtime.tools.navigation.CreateEnvironmentMapTool
import io.github.anonymous.pepper_realtime.tools.navigation.FinishEnvironmentMapTool
import io.github.anonymous.pepper_realtime.tools.navigation.FollowHumanTool
import io.github.anonymous.pepper_realtime.tools.navigation.LookAtPositionTool
import io.github.anonymous.pepper_realtime.tools.navigation.MovePepperTool
import io.github.anonymous.pepper_realtime.tools.navigation.NavigateToLocationTool
import io.github.anonymous.pepper_realtime.tools.navigation.SaveCurrentLocationTool
import io.github.anonymous.pepper_realtime.tools.navigation.StopFollowHumanTool
import io.github.anonymous.pepper_realtime.tools.navigation.TurnPepperTool
import io.github.anonymous.pepper_realtime.tools.vision.AnalyzeVisionTool
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool registry using factory pattern with instance caching.
 * Manages tool instances, definitions, and availability.
 * 
 * Tool instances are cached to avoid repeated object allocation.
 * Note: Only the Tool objects are cached, not execution results.
 * Each call to execute() still performs fresh operations.
 */
class ToolRegistry {

    /**
     * Functional interface for tool creation
     */
    fun interface ToolFactory {
        fun createTool(): Tool
    }

    // Factory for creating tool instances
    private val toolFactories = mutableMapOf<String, ToolFactory>()
    
    // Cache for tool instances to avoid repeated object allocation
    private val toolCache = mutableMapOf<String, Tool>()

    init {
        registerAllTools()
    }

    /**
     * Register all available tools with their factories
     */
    private fun registerAllTools() {
        // Information tools
        registerTool("get_current_datetime") { GetDateTimeTool() }
        registerTool("get_random_joke") { GetRandomJokeTool() }
        registerTool("search_internet") { SearchInternetTool() }
        registerTool("get_weather") { GetWeatherTool() }

        // Entertainment tools
        registerTool("play_animation") { PlayAnimationTool() }
        registerTool("play_youtube_video") { PlayYouTubeVideoTool() }

        // Navigation tools
        registerTool("move_pepper") { MovePepperTool() }
        registerTool("turn_pepper") { TurnPepperTool() }
        registerTool("look_at_position") { LookAtPositionTool() }
        registerTool("create_environment_map") { CreateEnvironmentMapTool() }
        registerTool("finish_environment_map") { FinishEnvironmentMapTool() }
        registerTool("save_current_location") { SaveCurrentLocationTool() }
        registerTool("navigate_to_location") { NavigateToLocationTool() }
        registerTool("approach_human") { ApproachHumanTool() }
        registerTool("follow_human") { FollowHumanTool() }
        registerTool("stop_follow_human") { StopFollowHumanTool() }

        // Game tools
        registerTool("start_memory_game") { MemoryGameTool() }
        registerTool("present_quiz_question") { QuizTool() }
        registerTool("start_tic_tac_toe_game") { TicTacToeStartTool() }
        registerTool("make_tic_tac_toe_move") { TicTacToeMoveTool() }
        registerTool("start_drawing_game") { DrawingGameTool() }

        // Vision tools
        registerTool("analyze_vision") { AnalyzeVisionTool() }

        Log.d(TAG, "Registered ${toolFactories.size} tools across all categories")
    }

    /**
     * Register a tool with its factory
     */
    private fun registerTool(name: String, factory: ToolFactory) {
        toolFactories[name] = factory
    }

    /**
     * Get or create a cached tool instance by name.
     * Tool instances are reused to reduce object allocation overhead.
     * Note: Only the instance is cached, execute() still runs fresh each time.
     */
    fun getOrCreateTool(name: String): Tool? {
        // Return cached instance if available
        toolCache[name]?.let { return it }
        
        // Create new instance and cache it
        val factory = toolFactories[name]
        if (factory == null) {
            Log.w(TAG, "Unknown tool requested: $name")
            return null
        }
        
        val tool = factory.createTool()
        toolCache[name] = tool
        return tool
    }

    /**
     * Clear the tool cache.
     * Useful when tool configurations might have changed.
     */
    fun clearCache() {
        toolCache.clear()
        Log.d(TAG, "Tool cache cleared")
    }

    /**
     * Get all registered tool names
     */
    fun getAllToolNames(): Set<String> = toolFactories.keys

    /**
     * Build tools definition for enabled tools (used by both Azure and OpenAI)
     */
    fun buildToolsDefinitionForAzure(context: ToolContext, enabledTools: Set<String>?): JSONArray {
        val tools = JSONArray()

        for (toolName in toolFactories.keys) {
            // Skip if tool is not in enabled set
            if (enabledTools != null && toolName !in enabledTools) {
                continue
            }

            val tool = getOrCreateTool(toolName) ?: continue

            // Check if tool is available (e.g., API keys)
            if (!tool.isAvailable(context)) {
                Log.d(TAG, "Tool '$toolName' not available, skipping")
                continue
            }

            try {
                val toolDefinition = tool.getDefinition()

                // For navigate_to_location tool, dynamically add available locations to description
                if (toolName == "navigate_to_location") {
                    enhanceNavigationToolDescription(toolDefinition, context)
                }

                tools.put(toolDefinition)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get definition for tool: $toolName", e)
            }
        }

        Log.d(TAG, "Built tools definition with ${tools.length()} tools")
        return tools
    }

    /**
     * Enhance navigation tool description with available locations
     */
    private fun enhanceNavigationToolDescription(toolDefinition: JSONObject, context: ToolContext) {
        try {
            val savedLocations = context.locationProvider.getSavedLocations()
            val availableLocations = savedLocations.map { loc -> loc.name }

            val baseDescription = "Navigate Pepper to a previously saved location. Use this when the user wants to go to a specific named place."

            if (availableLocations.isEmpty()) {
                toolDefinition.put("description", "$baseDescription Currently no saved locations available - user needs to save locations first. Additional locations saved during this session can also be used.")
                Log.d(TAG, "Enhanced navigation tool - no locations available")
            } else {
                val locationsText = availableLocations.joinToString(", ")
                toolDefinition.put("description", "$baseDescription Available saved locations: $locationsText. Additional locations saved during this session can also be used.")
                Log.d(TAG, "Enhanced navigation tool with ${availableLocations.size} saved locations")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enhance navigation tool description", e)
        }
    }

    /**
     * Execute a tool by name.
     * Uses cached tool instance but always runs execute() fresh.
     */
    fun executeTool(toolName: String, args: JSONObject, context: ToolContext): String {
        val tool = getOrCreateTool(toolName) ?: return try {
            JSONObject().put("error", "Unknown tool: $toolName").toString()
        } catch (e: Exception) {
            "{\"error\":\"Unknown tool and failed to create error response\"}"
        }

        return try {
            tool.execute(args, context)
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution error: $toolName", e)
            try {
                JSONObject().put("error", e.message).toString()
            } catch (ignored: Exception) {
                "{\"error\":\"execution failed\"}"
            }
        }
    }

    companion object {
        private const val TAG = "ToolRegistry"
    }
}

