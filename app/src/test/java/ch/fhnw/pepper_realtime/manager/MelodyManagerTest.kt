package ch.fhnw.pepper_realtime.manager

import ch.fhnw.pepper_realtime.ui.MelodyPlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for MelodyManager.
 * Tests state management and basic behavior.
 * Note: Full playback tests require instrumented tests due to audio dependencies.
 */
class MelodyManagerTest {

    private lateinit var manager: MelodyManager

    @Before
    fun setUp() {
        manager = MelodyManager()
    }

    // ==================== Initial State Tests ====================

    @Test
    fun `initial state is not visible and not playing`() {
        val state = manager.state.value
        assertFalse(state.isVisible)
        assertFalse(state.isPlaying)
        assertEquals("", state.melody)
        assertEquals("", state.currentNote)
        assertEquals(0f, state.progress, 0.001f)
    }

    // ==================== Scope Requirement Tests ====================

    @Test
    fun `startMelodyPlayer returns false without scope`() {
        // Act - try to start without setting scope
        val result = manager.startMelodyPlayer("C4 D4 E4")

        // Assert
        assertFalse(result)
        assertFalse(manager.state.value.isVisible)
    }

    // Note: Tests that require actual ToneGenerator and audio playback
    // are skipped in unit tests. They should be tested in instrumented tests.
    // The following behaviors would need instrumented tests:
    // - startMelodyPlayer with scope updates state correctly
    // - startMelodyPlayer returns false when already playing
    // - dismissMelodyPlayer hides overlay
    // - dismissMelodyPlayer calls callback with cancelled true
}
