package ch.fhnw.pepper_realtime.manager

import ch.fhnw.pepper_realtime.data.PerceptionData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for DashboardManager.
 * Tests visibility toggling, callback invocation, and state updates.
 */
class DashboardManagerTest {

    private lateinit var manager: DashboardManager
    private var callbackInvoked = false

    @Before
    fun setUp() {
        manager = DashboardManager()
        callbackInvoked = false
    }

    // ==================== Visibility Tests ====================

    @Test
    fun `initial state is not visible`() {
        assertFalse(manager.state.value.isVisible)
        assertFalse(manager.state.value.isMonitoring)
    }

    @Test
    fun `showDashboard sets visible and monitoring to true`() {
        // Act
        manager.showDashboard()

        // Assert
        assertTrue(manager.state.value.isVisible)
        assertTrue(manager.state.value.isMonitoring)
    }

    @Test
    fun `hideDashboard sets visible and monitoring to false`() {
        // Arrange
        manager.showDashboard()
        assertTrue(manager.state.value.isVisible)

        // Act
        manager.hideDashboard()

        // Assert
        assertFalse(manager.state.value.isVisible)
        assertFalse(manager.state.value.isMonitoring)
    }

    @Test
    fun `toggleDashboard flips visibility`() {
        // Initial: not visible
        assertFalse(manager.state.value.isVisible)

        // First toggle -> visible
        manager.toggleDashboard()
        assertTrue(manager.state.value.isVisible)

        // Second toggle -> not visible
        manager.toggleDashboard()
        assertFalse(manager.state.value.isVisible)
    }

    // ==================== Callback Tests ====================

    @Test
    fun `showDashboard invokes callback`() {
        // Arrange
        manager.setOnDashboardOpenedCallback { callbackInvoked = true }

        // Act
        manager.showDashboard()

        // Assert
        assertTrue(callbackInvoked)
    }

    @Test
    fun `toggleDashboard invokes callback when opening`() {
        // Arrange
        manager.setOnDashboardOpenedCallback { callbackInvoked = true }

        // Act - toggle to open
        manager.toggleDashboard()

        // Assert
        assertTrue(callbackInvoked)
    }

    @Test
    fun `toggleDashboard does not invoke callback when closing`() {
        // Arrange
        manager.showDashboard()
        manager.setOnDashboardOpenedCallback { callbackInvoked = true }
        callbackInvoked = false // Reset after showDashboard

        // Act - toggle to close
        manager.toggleDashboard()

        // Assert
        assertFalse(callbackInvoked)
    }

    // ==================== Data Update Tests ====================

    @Test
    fun `updateDashboardHumans updates state`() {
        // Arrange
        val human = PerceptionData.HumanInfo().apply {
            trackId = 1
            recognizedName = "John"
            positionX = 1.5
            positionY = 0.5
            distanceMeters = 1.58
            lookingAtRobot = true
            gazeDurationMs = 2000L
            trackAgeMs = 5000L
        }
        val humans = listOf(human)
        val timestamp = "12:34:56"

        // Act
        manager.updateDashboardHumans(humans, timestamp)

        // Assert
        assertEquals(1, manager.state.value.humans.size)
        assertEquals("John", manager.state.value.humans[0].recognizedName)
        assertEquals(timestamp, manager.state.value.lastUpdate)
    }

    @Test
    fun `resetDashboard clears all state`() {
        // Arrange
        manager.showDashboard()
        val human = PerceptionData.HumanInfo().apply { trackId = 1 }
        manager.updateDashboardHumans(listOf(human), "12:00:00")

        // Act
        manager.resetDashboard()

        // Assert
        val state = manager.state.value
        assertFalse(state.isVisible)
        assertFalse(state.isMonitoring)
        assertTrue(state.humans.isEmpty())
        assertEquals("", state.lastUpdate)
    }
}
