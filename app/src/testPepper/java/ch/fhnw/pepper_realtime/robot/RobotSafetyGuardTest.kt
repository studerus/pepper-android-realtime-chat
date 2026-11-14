package ch.fhnw.pepper_realtime.robot

import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.power.Power
import com.aldebaran.qi.sdk.`object`.power.FlapSensor
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Answers.RETURNS_DEEP_STUBS
import org.mockito.kotlin.*

class RobotSafetyGuardTest {
    @Test fun `missing context cannot authorize movement`() {
        assertEquals(RobotSafetyGuard.SafetyStatus.ROBOT_NOT_READY, RobotSafetyGuard.evaluateMovementSafety(null).status)
    }

    @Test fun `missing power sensor and state each block movement`() {
        val context = mock<QiContext>()
        assertUnknown(context)
        val power = mock<Power>()
        whenever(context.power).thenReturn(power)
        assertUnknown(context)
        val sensor = mock<FlapSensor>()
        whenever(power.chargingFlap).thenReturn(sensor)
        assertUnknown(context)
    }

    @Test fun `only explicitly closed flap authorizes movement`() {
        val context = mock<QiContext>(defaultAnswer = RETURNS_DEEP_STUBS)
        whenever(context.power.chargingFlap.state.open).thenReturn(false)
        assertTrue(RobotSafetyGuard.evaluateMovementSafety(context).isOk())
        whenever(context.power.chargingFlap.state.open).thenReturn(true)
        assertEquals(RobotSafetyGuard.SafetyStatus.CHARGING_FLAP_OPEN, RobotSafetyGuard.evaluateMovementSafety(context).status)
    }

    @Test fun `sensor exception blocks movement`() {
        val context = mock<QiContext>(defaultAnswer = RETURNS_DEEP_STUBS)
        whenever(context.power.chargingFlap.state).thenThrow(IllegalStateException("Disconnected sensor"))
        assertUnknown(context)
    }

    private fun assertUnknown(context: QiContext) {
        val result = RobotSafetyGuard.evaluateMovementSafety(context)
        assertEquals(RobotSafetyGuard.SafetyStatus.UNKNOWN, result.status)
        assertFalse(result.isOk())
        assertFalse(result.message.isNullOrBlank())
    }
}
