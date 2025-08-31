package io.github.studerus.pepper_android_realtime;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.List;
import java.util.ArrayList;

@SuppressWarnings("SpellCheckingInspection") // Animation names, API provider names
public class ToolRegistry {
    private static final String TAG = "ToolRegistry";

    /**
     * Load all available saved locations from storage
     */
    private static List<String> getAvailableLocations(Context context) {
        List<String> locations = new ArrayList<>();
        try {
            File locationsDir = new File(context.getFilesDir(), "locations");
            if (locationsDir.exists() && locationsDir.isDirectory()) {
                File[] files = locationsDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.getName().endsWith(".loc")) {
                            String locationName = file.getName().replace(".loc", "");
                            locations.add(locationName);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error loading available locations: " + e.getMessage());
        }
        return locations;
    }

    // Tool information class
    public static class ToolInfo {
        private final String name;
        private final String description;
        private final boolean requiresApiKey;
        private final String apiKeyType;

        public ToolInfo(String name, String description, boolean requiresApiKey, String apiKeyType) {
            this.name = name;
            this.description = description;
            this.requiresApiKey = requiresApiKey;
            this.apiKeyType = apiKeyType;
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
        public boolean requiresApiKey() { return requiresApiKey; }
        public String getApiKeyType() { return apiKeyType; }
    }

    /**
     * Returns all available tools with their information
     */
    public static java.util.List<ToolInfo> getAllAvailableTools() {
        java.util.List<ToolInfo> tools = new java.util.ArrayList<>();
        
        tools.add(new ToolInfo("get_current_datetime", 
            "Get the current date and time. Supports optional format and timezone.", 
            false, null));
            
        tools.add(new ToolInfo("play_animation", 
            "Play a preinstalled Pepper animation.", 
            false, null));
            
        tools.add(new ToolInfo("present_quiz_question", 
            "Presents a quiz question with four possible answers to the user in a popup window.", 
            false, null));
            
        tools.add(new ToolInfo("analyze_vision", 
            "Analyzes the current camera image of the robot and describes what the robot is seeing. Use this function if the user asks what you are seeing or how the user looks. Tell the user to wait a moment before you perform the function.", 
            true, "Groq"));
            
        tools.add(new ToolInfo("get_random_joke", 
            "Returns a random joke. Use this function whenever the user asks for a joke. Do not tell your own jokes, only jokes from the function.", 
            false, null));
            
        tools.add(new ToolInfo("search_internet", 
            "Searches the internet for facts, information, or people that you cannot answer from memory. Always respond to the user before you perform the function. Always first tell the user to wait a moment and then immediately perform the function.", 
            true, "Tavily"));
            
        tools.add(new ToolInfo("get_weather", 
            "Gets the current weather AND the 5-day forecast for a specific location. Always provides both current conditions and future forecast.", 
            true, "OpenWeatherMap"));
            
        tools.add(new ToolInfo("start_memory_game", 
            "Starts a memory matching game with cards that the user has to match in pairs. The user will see a grid of face-down cards and needs to find matching pairs by flipping two cards at a time.", 
            false, null));
            
        tools.add(new ToolInfo("move_pepper", 
            "Move Pepper robot in a specific direction for a given distance. Use this when the user asks Pepper to move around the room.", 
            false, null));
            
        tools.add(new ToolInfo("turn_pepper", 
            "Turn Pepper robot left or right by a specific number of degrees. Use this when the user asks Pepper to turn or rotate.", 
            false, null));
            
        		tools.add(new ToolInfo("create_environment_map", 
			"Create a detailed map of the current environment that Pepper can use for navigation. After starting, guide Pepper manually through the room using 'move' and 'turn' commands to map different areas. Use this when the user wants to set up navigation or map the room.", 
			false, null));
			
		tools.add(new ToolInfo("finish_environment_map", 
			"Complete and save the current mapping process. Use this after Pepper has been guided through the room to finalize the map.", 
			false, null));
			
		tools.add(new ToolInfo("save_current_location", 
			"Save Pepper's current position with a name for future navigation. Locations saved during active mapping are more accurate. Use this when Pepper is at an important location like 'kitchen', 'printer', 'entrance', etc.", 
			false, null));
			
		tools.add(new ToolInfo("navigate_to_location", 
			"Navigate Pepper to a previously saved location. Available locations are loaded dynamically. Use this when the user wants to go to a specific named place.", 
			false, null));
            
        tools.add(new ToolInfo("play_youtube_video", 
            "Search and play a YouTube video based on user's request. Use this when the user asks to play music, songs, or videos from YouTube.", 
            true, "YouTube"));
            
        tools.add(new ToolInfo("start_tic_tac_toe_game", 
            "Start a new Tic Tac Toe game with the user. The user will be X and you will be O. Use this when the user wants to play Tic Tac Toe.", 
            false, null));
            
        tools.add(new ToolInfo("make_tic_tac_toe_move", 
            "Make a move in the current Tic Tac Toe game. Choose a position from 0-8 on the 3x3 grid. The user can see the board visually, so don't describe board state.", 
            false, null));
            
        return tools;
    }

    public static JSONArray buildToolsDefinitionForAzure(Context context, java.util.Set<String> enabledTools) {
        ApiKeyManager keyManager = new ApiKeyManager(context);
        JSONArray tools = new JSONArray();
        
        // Load available locations for dynamic tool descriptions
        List<String> availableLocations = getAvailableLocations(context);
        Log.d(TAG, "Loaded " + availableLocations.size() + " available locations: " + availableLocations);
        
        try {
            // get_current_datetime
            if (enabledTools == null || enabledTools.contains("get_current_datetime")) {
                JSONObject t1 = new JSONObject();
                t1.put("type", "function");
                t1.put("name", "get_current_datetime");
                t1.put("description", "Get the current date and time. Supports optional format and timezone.");
                JSONObject p1 = new JSONObject();
                p1.put("type", "object");
                JSONObject props1 = new JSONObject();
                props1.put("format", new JSONObject().put("type", "string").put("description", "Formatting style. Default is iso."));
                props1.put("timezone", new JSONObject().put("type", "string").put("description", "IANA timezone, e.g. Europe/Zurich."));
                props1.put("pattern", new JSONObject().put("type", "string").put("description", "Java SimpleDateFormat pattern."));
                p1.put("properties", props1);
                t1.put("parameters", p1);
                tools.put(t1);
            }

            // play_animation
            if (enabledTools == null || enabledTools.contains("play_animation")) {
                JSONObject t2 = new JSONObject();
                t2.put("type", "function");
                t2.put("name", "play_animation");
                t2.put("description", "Play a preinstalled Pepper animation. Use the hello_01 animation when the user wants you to wave or say hello.");
                JSONObject p2 = new JSONObject();
                p2.put("type", "object");
                JSONArray animEnums = new JSONArray()
                        .put("applause_01")
                        .put("bowshort_01")
                        .put("funny_01")
                        .put("happy_01")
                        .put("hello_01")
                        .put("hey_02")
                        .put("kisses_01")
                        .put("laugh_01")
                        .put("showfloor_01")
                        .put("showsky_01")
                        .put("showtablet_02")
                        .put("wings_01");
                JSONObject props2 = new JSONObject();
                props2.put("name", new JSONObject().put("type", "string").put("description", "Animation identifier.").put("enum", animEnums));
                p2.put("properties", props2);
                p2.put("required", new JSONArray().put("name"));
                t2.put("parameters", p2);
                tools.put(t2);
            }

            // present_quiz_question
            if (enabledTools == null || enabledTools.contains("present_quiz_question")) {
                JSONObject t3 = new JSONObject();
                t3.put("type", "function");
                t3.put("name", "present_quiz_question");
                t3.put("description", "Presents a quiz question with four possible answers to the user in a popup window.");
                JSONObject p3 = new JSONObject();
                p3.put("type", "object");
                JSONObject props3 = new JSONObject();
                props3.put("question", new JSONObject().put("type", "string").put("description", "The quiz question."));
                JSONObject optionsSchema = new JSONObject();
                optionsSchema.put("type", "array");
                optionsSchema.put("items", new JSONObject().put("type", "string"));
                optionsSchema.put("minItems", 4);
                optionsSchema.put("maxItems", 4);
                optionsSchema.put("description", "An array of exactly four string options for the answer.");
                props3.put("options", optionsSchema);
                props3.put("correct_answer", new JSONObject().put("type", "string").put("description", "The correct answer from the options array."));
                p3.put("properties", props3);
                p3.put("required", new JSONArray().put("question").put("options").put("correct_answer"));
                t3.put("parameters", p3);
                tools.put(t3);
            }

            // analyze_vision (only if Groq API key is available)
            if ((enabledTools == null || enabledTools.contains("analyze_vision")) && keyManager.isVisionAnalysisAvailable()) {
                JSONObject t4 = new JSONObject();
                t4.put("type", "function");
                t4.put("name", "analyze_vision");
                t4.put("description", "Analyzes the current camera image of the robot and describes what the robot is seeing. Use this function if the user asks what you are seeing or how the user looks. Tell the user to wait a moment before you perform the function.");
                JSONObject p4 = new JSONObject();
                p4.put("type", "object");
                JSONObject props4 = new JSONObject();
                props4.put("prompt", new JSONObject().put("type", "string").put("description", "Optional additional instruction for the vision analysis (e.g. 'how old is the person?' if the user asks you to estimate his age)"));
                p4.put("properties", props4);
                t4.put("parameters", p4);
                tools.put(t4);
                Log.d(TAG, "Vision analysis tool registered (Groq API key available)");
            } else {
                Log.d(TAG, "Vision analysis tool NOT registered (Groq API key missing or disabled)");
            }

            // get_random_joke
            if (enabledTools == null || enabledTools.contains("get_random_joke")) {
                JSONObject t5 = new JSONObject();
                t5.put("type", "function");
                t5.put("name", "get_random_joke");
                t5.put("description", "Returns a random joke. Use this function whenever the user asks for a joke. Do not tell your own jokes, only jokes from the function.");
                JSONObject p5 = new JSONObject();
                p5.put("type", "object");
                p5.put("properties", new JSONObject()); // empty properties object required by schema
                t5.put("parameters", p5);
                tools.put(t5);
            }

            // search_internet (only if Tavily API key is available)
            if ((enabledTools == null || enabledTools.contains("search_internet")) && keyManager.isInternetSearchAvailable()) {
                JSONObject t6 = new JSONObject();
                t6.put("type", "function");
                t6.put("name", "search_internet");
                t6.put("description", "Searches the internet for facts, information, or people that you cannot answer from memory. Always respond to the user before you perform the function. Always first tell the user to wait a moment and then immediately perform the function.");
                JSONObject p6 = new JSONObject();
                p6.put("type", "object");
                JSONObject props6 = new JSONObject();
                props6.put("query", new JSONObject().put("type", "string").put("description", "The search query"));
                p6.put("properties", props6);
                p6.put("required", new JSONArray().put("query"));
                t6.put("parameters", p6);
                tools.put(t6);
                Log.d(TAG, "Internet search tool registered (Tavily API key available)");
            } else {
                Log.d(TAG, "Internet search tool NOT registered (Tavily API key missing or disabled)");
            }

            // get_weather (only if OpenWeatherMap API key is available)
            if ((enabledTools == null || enabledTools.contains("get_weather")) && keyManager.isWeatherAvailable()) {
                JSONObject t7 = new JSONObject();
                t7.put("type", "function");
                t7.put("name", "get_weather");
                t7.put("description", "Gets the current weather AND the 5-day forecast for a specific location. Always provides both current conditions and future forecast.");
                JSONObject p7 = new JSONObject();
                p7.put("type", "object");
                JSONObject props7 = new JSONObject();
                props7.put("location", new JSONObject().put("type", "string").put("description", "The city or location to get weather information for"));
                p7.put("properties", props7);
                p7.put("required", new JSONArray().put("location"));
                t7.put("parameters", p7);
                tools.put(t7);
                Log.d(TAG, "Weather tool registered (OpenWeatherMap API key available)");
            } else {
                Log.d(TAG, "Weather tool NOT registered (OpenWeatherMap API key missing or disabled)");
            }

            // start_memory_game
            if (enabledTools == null || enabledTools.contains("start_memory_game")) {
                JSONObject t8 = new JSONObject();
                t8.put("type", "function");
                t8.put("name", "start_memory_game");
                t8.put("description", "Starts a memory matching game with cards that the user has to match in pairs. The user will see a grid of face-down cards and needs to find matching pairs by flipping two cards at a time.");
                JSONObject p8 = new JSONObject();
                p8.put("type", "object");
                JSONObject props8 = new JSONObject();
                props8.put("difficulty", new JSONObject().put("type", "string").put("description", "Game difficulty level").put("enum", new JSONArray().put("easy").put("medium").put("hard")).put("default", "medium"));
                p8.put("properties", props8);
                t8.put("parameters", p8);
                tools.put(t8);
            }

            // move_pepper
            if (enabledTools == null || enabledTools.contains("move_pepper")) {
                JSONObject t9 = new JSONObject();
                t9.put("type", "function");
                t9.put("name", "move_pepper");
                t9.put("description", "Move Pepper robot in a specific direction for a given distance. Use this when the user asks Pepper to move around the room. Call the function directly without announcing it.");
                JSONObject p9 = new JSONObject();
                p9.put("type", "object");
                JSONObject props9 = new JSONObject();
                props9.put("direction", new JSONObject().put("type", "string").put("description", "Direction to move").put("enum", new JSONArray().put("forward").put("backward").put("left").put("right")));
                props9.put("distance", new JSONObject().put("type", "number").put("description", "Distance to move in meters (0.1-4.0)").put("minimum", 0.1).put("maximum", 4.0));
                props9.put("speed", new JSONObject().put("type", "number").put("description", "Optional maximum speed in m/s (0.1-0.55)").put("minimum", 0.1).put("maximum", 0.55).put("default", 0.4));
                p9.put("properties", props9);
                p9.put("required", new JSONArray().put("direction").put("distance"));
                t9.put("parameters", p9);
                tools.put(t9);
            }

            // turn_pepper
            if (enabledTools == null || enabledTools.contains("turn_pepper")) {
                JSONObject t10 = new JSONObject();
                t10.put("type", "function");
                t10.put("name", "turn_pepper");
                t10.put("description", "Turn Pepper robot left or right by a specific number of degrees. Use this when the user asks Pepper to turn or rotate.");
                JSONObject p10 = new JSONObject();
                p10.put("type", "object");
                JSONObject props10 = new JSONObject();
                props10.put("direction", new JSONObject().put("type", "string").put("description", "Direction to turn").put("enum", new JSONArray().put("left").put("right")));
                props10.put("degrees", new JSONObject().put("type", "number").put("description", "Degrees to turn (15-180)").put("minimum", 15).put("maximum", 180));
                props10.put("speed", new JSONObject().put("type", "number").put("description", "Optional turning speed in rad/s (0.1-1.0)").put("minimum", 0.1).put("maximum", 1.0).put("default", 0.5));
                p10.put("properties", props10);
                p10.put("required", new JSONArray().put("direction").put("degrees"));
                t10.put("parameters", p10);
                tools.put(t10);
            }

            // create_environment_map
            if (enabledTools == null || enabledTools.contains("create_environment_map")) {
                JSONObject t11 = new JSONObject();
                t11.put("type", "function");
                t11.put("name", "create_environment_map");
                t11.put("description", "Create a detailed map of the current environment that Pepper can use for navigation. Uses a single global map name internally.");
                JSONObject p11 = new JSONObject();
                p11.put("type", "object");
                p11.put("properties", new JSONObject());
                t11.put("parameters", p11);
                tools.put(t11);
            }

            // finish_environment_map
            if (enabledTools == null || enabledTools.contains("finish_environment_map")) {
                JSONObject t12 = new JSONObject();
                t12.put("type", "function");
                t12.put("name", "finish_environment_map");
                t12.put("description", "Complete and save the current mapping process. Uses a single global map name internally.");
                JSONObject p12 = new JSONObject();
                p12.put("type", "object");
                p12.put("properties", new JSONObject());
                t12.put("parameters", p12);
                tools.put(t12);
            }

            // save_current_location
            if (enabledTools == null || enabledTools.contains("save_current_location")) {
                JSONObject t13 = new JSONObject();
                t13.put("type", "function");
                t13.put("name", "save_current_location");
                t13.put("description", "Save Pepper's current position with a name for future navigation. Use this when the user wants to save a location like 'kitchen', 'printer', 'entrance', etc. Call the function directly without announcing it.");
                JSONObject p13 = new JSONObject();
                p13.put("type", "object");
                JSONObject props13 = new JSONObject();
                props13.put("location_name", new JSONObject().put("type", "string").put("description", "Name for this location (e.g. 'kitchen', 'printer', 'entrance')"));
                props13.put("description", new JSONObject().put("type", "string").put("description", "Optional description of this location").put("default", ""));
                p13.put("properties", props13);
                p13.put("required", new JSONArray().put("location_name"));
                t13.put("parameters", p13);
                tools.put(t13);
            }

            // navigate_to_location
            if (enabledTools == null || enabledTools.contains("navigate_to_location")) {
                JSONObject t14 = new JSONObject();
                t14.put("type", "function");
                t14.put("name", "navigate_to_location");
                
                // Create dynamic description with available locations
                String locationsList;
                if (availableLocations.isEmpty()) {
                    locationsList = "none";
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < availableLocations.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(availableLocations.get(i));
                    }
                    locationsList = sb.toString();
                }
                String dynamicDescription = String.format(
                    "Navigate Pepper to a previously saved location. Available locations: %s and any locations added during the current session. Use this when the user wants to go to a specific named place.", 
                    locationsList
                );
                t14.put("description", dynamicDescription);
                Log.d(TAG, "Dynamic navigate_to_location description: " + dynamicDescription);
                JSONObject p14 = new JSONObject();
                p14.put("type", "object");
                JSONObject props14 = new JSONObject();
                props14.put("location_name", new JSONObject().put("type", "string").put("description", "Name of the saved location to navigate to"));
                props14.put("speed", new JSONObject().put("type", "number").put("description", "Optional movement speed in m/s (0.1-0.55)").put("minimum", 0.1).put("maximum", 0.55).put("default", 0.3));
                p14.put("properties", props14);
                p14.put("required", new JSONArray().put("location_name"));
                t14.put("parameters", p14);
                tools.put(t14);
            }

            // start_tic_tac_toe_game
            if (enabledTools == null || enabledTools.contains("start_tic_tac_toe_game")) {
                JSONObject t16 = new JSONObject();
                t16.put("type", "function");
                t16.put("name", "start_tic_tac_toe_game");
                t16.put("description", "Start a new Tic Tac Toe game with the user. The user will be X and you will be O. Call this function when the user wants to play Tic Tac Toe. Call the function directly without announcing it.");
                JSONObject p16 = new JSONObject();
                p16.put("type", "object");
                p16.put("properties", new JSONObject());
                t16.put("parameters", p16);
                tools.put(t16);
            }

            // make_tic_tac_toe_move
            if (enabledTools == null || enabledTools.contains("make_tic_tac_toe_move")) {
                JSONObject t17 = new JSONObject();
                t17.put("type", "function");
                t17.put("name", "make_tic_tac_toe_move");
                t17.put("description", "Make a move in the current Tic Tac Toe game. Choose a position from 0-8 where 0=top-left, 1=top-center, 2=top-right, 3=middle-left, 4=center, 5=middle-right, 6=bottom-left, 7=bottom-center, 8=bottom-right. The user can see the game board visually, so don't describe the board state after your move.");
                JSONObject p17 = new JSONObject();
                p17.put("type", "object");
                JSONObject props17 = new JSONObject();
                props17.put("position", new JSONObject().put("type", "integer").put("description", "Position on the 3x3 board (0-8)").put("minimum", 0).put("maximum", 8));
                p17.put("properties", props17);
                p17.put("required", new JSONArray().put("position"));
                t17.put("parameters", p17);
                tools.put(t17);
            }

            // play_youtube_video (only if YouTube API key is available)
            if ((enabledTools == null || enabledTools.contains("play_youtube_video")) && keyManager.isYouTubeAvailable()) {
                JSONObject t15 = new JSONObject();
                t15.put("type", "function");
                t15.put("name", "play_youtube_video");
                t15.put("description", "Search and play a YouTube video based on user's request. Use this when the user asks to play music, songs, or videos from YouTube.");
                JSONObject p15 = new JSONObject();
                p15.put("type", "object");
                JSONObject props15 = new JSONObject();
                props15.put("query", new JSONObject().put("type", "string").put("description", "Search query for the video (e.g. 'like a virgin madonna', 'funny cat videos')"));
                p15.put("properties", props15);
                p15.put("required", new JSONArray().put("query"));
                t15.put("parameters", p15);
                tools.put(t15);
                Log.d(TAG, "YouTube video tool registered (YouTube API key available)");
            } else {
                Log.d(TAG, "YouTube video tool NOT registered (YouTube API key missing or disabled)");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error building tools definition for Azure", e);
        }
        return tools;
    }
}
