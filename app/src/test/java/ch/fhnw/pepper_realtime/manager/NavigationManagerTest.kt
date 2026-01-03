package ch.fhnw.pepper_realtime.manager

import ch.fhnw.pepper_realtime.ui.NavigationUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for NavigationManager.
 * Tests visibility toggling and state updates.
 */
class NavigationManagerTest {

    private lateinit var manager: NavigationManager

    @Before
    fun setUp() {
        manager = NavigationManager()
    }

    // ==================== Visibility Tests ====================

    @Test
    fun `initial state is not visible`() {
        assertFalse(manager.state.value.isVisible)
    }

    @Test
    fun `showNavigationOverlay sets visible to true`() {
        // Act
        manager.showNavigationOverlay()

        // Assert
        assertTrue(manager.state.value.isVisible)
    }

    @Test
    fun `hideNavigationOverlay sets visible to false`() {
        // Arrange
        manager.showNavigationOverlay()
        assertTrue(manager.state.value.isVisible)

        // Act
        manager.hideNavigationOverlay()

        // Assert
        assertFalse(manager.state.value.isVisible)
    }

    @Test
    fun `toggleNavigationOverlay flips visibility`() {
        // Initial: not visible
        assertFalse(manager.state.value.isVisible)

        // First toggle -> visible
        manager.toggleNavigationOverlay()
        assertTrue(manager.state.value.isVisible)

        // Second toggle -> not visible
        manager.toggleNavigationOverlay()
        assertFalse(manager.state.value.isVisible)
    }

    // ==================== State Update Tests ====================

    @Test
    fun `setLocalizationStatus updates status`() {
        // Act
        manager.setLocalizationStatus("Localized at Kitchen")

        // Assert
        assertEquals("Localized at Kitchen", manager.state.value.localizationStatus)
    }

    @Test
    fun `updateMapData updates all map fields`() {
        // Arrange
        val locations = listOf(
            ch.fhnw.pepper_realtime.data.SavedLocation(
                name = "Kitchen",
                description = "Main kitchen area"
            )
        )

        // Act
        manager.updateMapData(
            hasMapOnDisk = true,
            mapBitmap = null, // Can't easily create Bitmap in unit test
            mapGfx = null,
            locations = locations
        )

        // Assert
        val state = manager.state.value
        assertTrue(state.hasMapOnDisk)
        assertNull(state.mapBitmap)
        assertNull(state.mapGfx)
        assertEquals(1, state.savedLocations.size)
        assertEquals("Kitchen", state.savedLocations[0].name)
    }

    @Test
    fun `initial state has default localization status`() {
        assertEquals("Not running", manager.state.value.localizationStatus)
    }

    @Test
    fun `initial state has no saved locations`() {
        assertTrue(manager.state.value.savedLocations.isEmpty())
    }
}
