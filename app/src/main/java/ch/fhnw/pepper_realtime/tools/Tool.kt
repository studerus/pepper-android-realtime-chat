package ch.fhnw.pepper_realtime.tools

import org.json.JSONObject

/**
 * Base interface for all Pepper robot tools/functions.
 * Each tool provides its own definition and execution logic.
 */
interface Tool {
    /**
     * Get the unique name/identifier of this tool
     * @return Tool name (e.g., "move_pepper", "play_animation")
     */
    fun getName(): String

    /**
     * Get the JSON schema definition for this tool for AI integration
     * @return JSONObject containing the tool definition for Azure OpenAI
     */
    fun getDefinition(): JSONObject

    /**
     * Execute the tool with the given parameters
     * @param args JSON arguments passed from AI
     * @param context Shared context with dependencies
     * @return JSON result string
     * @throws Exception if tool execution fails
     */
    @Throws(Exception::class)
    fun execute(args: JSONObject, context: ToolContext): String

    /**
     * The API key requirement for this tool.
     * Use ApiKeyRequirement.None for tools that don't need an API key,
     * or ApiKeyRequirement.Required(ApiKeyType.XXX) for tools that do.
     */
    val apiKeyRequirement: ApiKeyRequirement
        get() = ApiKeyRequirement.None

    /**
     * Check if this tool is currently available based on context.
     * Default implementation checks API key availability based on apiKeyRequirement.
     *
     * @param context Tool context to check availability
     * @return true if tool can be executed, false if disabled/unavailable
     */
    fun isAvailable(context: ToolContext): Boolean {
        return when (val requirement = apiKeyRequirement) {
            is ApiKeyRequirement.None -> true
            is ApiKeyRequirement.Required -> requirement.type.isAvailable(context)
        }
    }
}

