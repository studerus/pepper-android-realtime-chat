package ch.fhnw.pepper_realtime.tools.navigation

import ch.fhnw.pepper_realtime.controller.MovementController
import ch.fhnw.pepper_realtime.data.SavedLocation
import ch.fhnw.pepper_realtime.tools.ToolContext
import ch.fhnw.pepper_realtime.service.PerceptionService
import org.mockito.Answers.RETURNS_DEEP_STUBS
import com.aldebaran.qi.sdk.QiContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class NavigationSafetyTest {
    @Test fun `unresolved explicit track never falls back to a recommended human`() {
        val context = mock<ToolContext>()
        val qi = mock<QiContext>(defaultAnswer = RETURNS_DEEP_STUBS)
        val perception = mock<PerceptionService>()
        whenever(qi.power.chargingFlap.state.open).thenReturn(false)
        whenever(context.qiContext).thenReturn(qi)
        whenever(context.perceptionService).thenReturn(perception)
        whenever(perception.isInitialized).thenReturn(true)
        val result = ApproachHumanTool().execute(JSONObject().put("human_id", 42), context)
        assertTrue(JSONObject(result).getString("error").contains("track 42"))
        verify(perception).resolveApproachTrack(42)
        verify(perception, never()).getRecommendedHumanToApproach()
    }

    @Test fun `invalid tool arguments never reach robot or navigation services`() {
        val cases = listOf(
            MovePepperTool() to """{"distance_forward":4.01}""",
            MovePepperTool() to """{"distance_forward":1,"speed":"fast"}""",
            MovePepperTool() to """{"distance_forward":1,"distance_sideways":null}""",
            TurnPepperTool() to """{"direction":"left","degrees":"NaN"}""",
            TurnPepperTool() to """{"direction":"left","degrees":90,"speed":1}""",
            LookAtPositionTool() to """{"x":6,"y":0,"z":1.2}""",
            LookAtPositionTool() to """{"x":1,"y":0}""",
            NavigateToLocationTool() to """{"location_name":"Kitchen","speed":"NaN"}""",
            ApproachHumanTool() to """{"speed":100}""",
            ApproachHumanTool() to """{"human_id":0}""",
            ApproachHumanTool() to """{"human_id":1.5}""",
            ApproachHumanTool() to """{"human_id":"2"}""",
            FollowHumanTool() to """{"distance":"NaN"}""",
            FollowHumanTool() to """{"distance":0.1}"""
        )
        for ((tool, json) in cases) {
            val context = mock<ToolContext>()
            assertTrue(tool.getName(), JSONObject(tool.execute(JSONObject(json), context)).has("error"))
            verifyNoInteractions(context)
        }
    }

    @Test fun `unknown flap state blocks every base-moving tool before execution`() {
        val cases = listOf(
            MovePepperTool() to """{"distance_forward":1}""",
            TurnPepperTool() to """{"direction":"left","degrees":90}""",
            LookAtPositionTool() to """{"x":1,"y":0,"z":1.2,"movement_policy":"whole_body"}""",
            CreateEnvironmentMapTool() to "{}",
            NavigateToLocationTool() to """{"location_name":"Kitchen"}""",
            ApproachHumanTool() to "{}",
            FollowHumanTool() to "{}"
        )
        for ((tool, json) in cases) {
            val context = mock<ToolContext>()
            val qi = mock<QiContext>() // No power service/sensor available.
            whenever(context.qiContext).thenReturn(qi)
            assertTrue(tool.getName(), JSONObject(tool.execute(JSONObject(json), context)).getString("error").contains("unavailable"))
            verify(context, never()).navigationServiceManager
            verify(context, never()).perceptionService
            verify(qi, never()).actuation
            verify(qi, never()).mapping
        }
    }

    @Test fun `direct controller calls also validate before creating actions`() {
        val qi = mock<QiContext>()
        val listener = mock<MovementController.MovementListener>()
        val controller = MovementController().apply { setListener(listener) }
        try {
            controller.movePepper(qi, Double.NaN, 0.0, 0.4)
            controller.movePepper(qi, 5.0, 0.0, 0.4)
            controller.turnPepper(qi, "left", 90.0, Double.POSITIVE_INFINITY)
            controller.navigateToLocation(qi, SavedLocation(), Float.NaN)
            verify(listener, times(4)).onMovementFinished(eq(false), any())
            verifyNoInteractions(qi)
        } finally {
            controller.shutdown()
        }
    }

    @Test fun `controller rechecks safety even for valid commands`() {
        val qi = mock<QiContext>()
        val listener = mock<MovementController.MovementListener>()
        val controller = MovementController().apply { setListener(listener) }
        try {
            controller.movePepper(qi, 1.0, 0.0, 0.4)
            controller.turnPepper(qi, "left", 90.0, 0.5)
            controller.navigateToLocation(qi, SavedLocation(translation = doubleArrayOf(1.0, 2.0, 0.0)), 0.55f)
            verify(listener, times(3)).onMovementFinished(eq(false), argThat { contains("unavailable") })
            verify(qi, times(3)).power
            verify(qi, never()).actuation
            verify(qi, never()).mapping
        } finally {
            controller.shutdown()
        }
    }
}
