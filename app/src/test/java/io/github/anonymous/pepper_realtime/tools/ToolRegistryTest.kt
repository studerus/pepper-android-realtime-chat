package io.github.anonymous.pepper_realtime.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ToolRegistry.
 * 
 * Note: Some tests that require Android context or complex mocking
 * are marked for instrumentation testing.
 */
class ToolRegistryTest {

    private lateinit var toolRegistry: ToolRegistry

    @Before
    fun setUp() {
        toolRegistry = ToolRegistry()
    }

    // ========== Tool Registration Tests ==========

    @Test
    fun `registry contains expected core tools`() {
        val allTools = toolRegistry.getAllToolNames()
        
        // Core tools that should always be present
        assertTrue("Should contain get_current_datetime", allTools.contains("get_current_datetime"))
        assertTrue("Should contain get_random_joke", allTools.contains("get_random_joke"))
        assertTrue("Should contain search_internet", allTools.contains("search_internet"))
        assertTrue("Should contain get_weather", allTools.contains("get_weather"))
    }

    @Test
    fun `registry contains navigation tools`() {
        val allTools = toolRegistry.getAllToolNames()
        
        assertTrue("Should contain move_pepper", allTools.contains("move_pepper"))
        assertTrue("Should contain turn_pepper", allTools.contains("turn_pepper"))
        assertTrue("Should contain look_at_position", allTools.contains("look_at_position"))
        assertTrue("Should contain navigate_to_location", allTools.contains("navigate_to_location"))
    }

    @Test
    fun `registry contains game tools`() {
        val allTools = toolRegistry.getAllToolNames()
        
        assertTrue("Should contain start_memory_game", allTools.contains("start_memory_game"))
        assertTrue("Should contain present_quiz_question", allTools.contains("present_quiz_question"))
        assertTrue("Should contain start_tic_tac_toe_game", allTools.contains("start_tic_tac_toe_game"))
        assertTrue("Should contain make_tic_tac_toe_move", allTools.contains("make_tic_tac_toe_move"))
    }

    @Test
    fun `registry contains entertainment tools`() {
        val allTools = toolRegistry.getAllToolNames()
        
        assertTrue("Should contain play_animation", allTools.contains("play_animation"))
        assertTrue("Should contain play_youtube_video", allTools.contains("play_youtube_video"))
    }

    @Test
    fun `registry contains vision tools`() {
        val allTools = toolRegistry.getAllToolNames()
        
        assertTrue("Should contain analyze_vision", allTools.contains("analyze_vision"))
    }

    @Test
    fun `getAllToolNames returns non-empty set`() {
        val allTools = toolRegistry.getAllToolNames()
        
        assertTrue("Should have at least 10 tools", allTools.size >= 10)
    }

    // ========== Tool Retrieval Tests ==========

    @Test
    fun `getOrCreateTool returns tool for valid name`() {
        val tool = toolRegistry.getOrCreateTool("get_current_datetime")
        
        assertNotNull("Tool should not be null", tool)
        assertEquals("get_current_datetime", tool?.getName())
    }

    @Test
    fun `getOrCreateTool returns null for unknown tool`() {
        val tool = toolRegistry.getOrCreateTool("unknown_tool_that_does_not_exist")
        
        assertNull("Unknown tool should return null", tool)
    }

    @Test
    fun `getOrCreateTool returns cached instance on second call`() {
        val tool1 = toolRegistry.getOrCreateTool("get_current_datetime")
        val tool2 = toolRegistry.getOrCreateTool("get_current_datetime")
        
        // Should be the exact same instance (cached)
        assertTrue("Should return cached instance", tool1 === tool2)
    }

    @Test
    fun `clearCache clears cached tools`() {
        // Get a tool to cache it
        val tool1 = toolRegistry.getOrCreateTool("get_current_datetime")
        
        // Clear cache
        toolRegistry.clearCache()
        
        // Get tool again - should be new instance
        val tool2 = toolRegistry.getOrCreateTool("get_current_datetime")
        
        // Both should be valid but different instances
        assertNotNull(tool1)
        assertNotNull(tool2)
        assertTrue("Should be different instances after cache clear", tool1 !== tool2)
    }

    // ========== Tool Count Tests ==========

    @Test
    fun `registry has expected minimum tool count`() {
        val count = toolRegistry.getAllToolNames().size
        
        // Based on registerAllTools():
        // Information: 4 (datetime, joke, search, weather)
        // Entertainment: 2 (animation, youtube)
        // Navigation: 8 (move, turn, look, create_map, finish_map, save_loc, navigate, approach)
        // Games: 4 (memory, quiz, ttt_start, ttt_move)
        // Vision: 1 (analyze_vision)
        // Total: 19
        assertTrue("Should have at least 15 tools", count >= 15)
    }

    // Note: Tests that require ToolContext (executeTool, buildToolsDefinitionForAzure)
    // need Android instrumentation tests due to Android-specific dependencies.
}
