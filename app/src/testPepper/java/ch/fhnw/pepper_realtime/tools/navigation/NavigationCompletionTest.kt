package ch.fhnw.pepper_realtime.tools.navigation

import ch.fhnw.pepper_realtime.manager.NavigationServiceManager.MovementResult
import com.aldebaran.qi.Future
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class NavigationCompletionTest {
    private val tool = NavigateToLocationTool()
    private val future = mock<Future<MovementResult>>()

    @Test fun `failed future produces an obstacle update without reading its value`() {
        val error = java.util.concurrent.ExecutionException("path blocked", null)
        whenever(future.hasError()).thenReturn(true)
        whenever(future.error).thenReturn(error)
        val message = requireNotNull(tool.completionMessage(future, "Lab"))
        assertTrue(message.startsWith("[NAVIGATION FAILED] Could not reach Lab"))
        assertTrue(message.contains(OBSTACLE_VISION_HINT))
        verify(future, never()).value
    }

    @Test fun `cancelled future is silent and does not read its value`() {
        whenever(future.isCancelled).thenReturn(true)
        assertNull(tool.completionMessage(future, "Lab"))
        verify(future, never()).value
    }

    @Test fun `movement outcomes distinguish arrival failure and cancellation`() {
        whenever(future.value).thenReturn(MovementResult(true, null),
            MovementResult(false, "Localization lost"), MovementResult(false, "Movement cancelled"))
        assertTrue(tool.completionMessage(future, "Lab")!!.startsWith("[NAVIGATION COMPLETED]"))
        val failure = requireNotNull(tool.completionMessage(future, "Lab"))
        assertTrue(failure.startsWith("[NAVIGATION FAILED]"))
        assertFalse(failure.contains(OBSTACLE_VISION_HINT))
        assertNull(tool.completionMessage(future, "Lab"))
    }
}
