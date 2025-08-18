package com.example.pepper_test2;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

@SuppressWarnings("SpellCheckingInspection") // Animation names, API provider names
public class ToolRegistry {
    private static final String TAG = "ToolRegistry";

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
            
        return tools;
    }

    public static JSONArray buildToolsDefinitionForAzure(Context context) {
        return buildToolsDefinitionForAzure(context, null);
    }

    public static JSONArray buildToolsDefinitionForAzure(Context context, java.util.Set<String> enabledTools) {
        ApiKeyManager keyManager = new ApiKeyManager(context);
        JSONArray tools = new JSONArray();
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
                t2.put("description", "Play a preinstalled Pepper animation.");
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
                t9.put("description", "Move Pepper robot in a specific direction for a given distance. Use this when the user asks Pepper to move around the room.");
                JSONObject p9 = new JSONObject();
                p9.put("type", "object");
                JSONObject props9 = new JSONObject();
                props9.put("direction", new JSONObject().put("type", "string").put("description", "Direction to move").put("enum", new JSONArray().put("forward").put("backward").put("left").put("right")));
                props9.put("distance", new JSONObject().put("type", "number").put("description", "Distance to move in meters (0.5-3.0)").put("minimum", 0.5).put("maximum", 3.0));
                props9.put("speed", new JSONObject().put("type", "number").put("description", "Optional maximum speed in m/s (0.1-0.55)").put("minimum", 0.1).put("maximum", 0.55).put("default", 0.4));
                p9.put("properties", props9);
                p9.put("required", new JSONArray().put("direction").put("distance"));
                t9.put("parameters", p9);
                tools.put(t9);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error building tools definition for Azure", e);
        }
        return tools;
    }
}
