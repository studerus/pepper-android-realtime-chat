package ch.fhnw.pepper_realtime.tools.vision

import ch.fhnw.pepper_realtime.tools.vision.VisionCaptureReadiness.Decision.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.tan

class VisionCaptureReadinessTest {
    private fun sample(tracker: VisionCaptureReadiness, time: Long, yaw: Double = 0.0, pitch: Double = 0.0) =
        tracker.observe(time, 1.0, tan(Math.toRadians(yaw)), tan(Math.toRadians(pitch)))

    @Test fun `already centered target is captured on the first sample`() {
        val tracker = VisionCaptureReadiness()
        assertEquals(CAPTURE, sample(tracker, 0))
    }

    @Test fun `entering alignment tolerance triggers capture without settling delay`() {
        val tracker = VisionCaptureReadiness()
        assertEquals(WAIT, sample(tracker, 0, yaw = 40.0))
        assertEquals(WAIT, sample(tracker, 100, yaw = 20.0))
        assertEquals(WAIT, sample(tracker, 200, yaw = 8.1))
        assertEquals(CAPTURE, sample(tracker, 300, yaw = 7.9))
    }

    @Test fun `immediate capture requires combined angle within tolerance`() {
        val tracker = VisionCaptureReadiness()
        assertEquals(WAIT, sample(tracker, 0, yaw = 6.0, pitch = 6.0))
        assertEquals(CAPTURE, sample(tracker, 100, yaw = 5.0, pitch = 5.0))
    }

    @Test fun `visible off center floor target no longer requires eight degree alignment`() {
        val tracker = VisionCaptureReadiness()
        assertEquals(WAIT, sample(tracker, 0, pitch = -45.0))
        assertEquals(WAIT, sample(tracker, 100, pitch = -30.0))
        assertEquals(WAIT, sample(tracker, 200, pitch = -14.0))
        assertEquals(WAIT, sample(tracker, 300, pitch = -14.0))
        assertEquals(CAPTURE, sample(tracker, 500, pitch = -14.0))
    }

    @Test fun `passing through off center view while moving does not trigger capture`() {
        val tracker = VisionCaptureReadiness()
        for (step in 0..15) assertEquals(WAIT, sample(tracker, step * 100L, yaw = 40.0 - step * 2))
        assertEquals(WAIT, sample(tracker, 1600, yaw = 10.0))
        assertEquals(CAPTURE, sample(tracker, 1800, yaw = 10.0))
    }

    @Test fun `small cumulative drift resets fixed stability anchor`() {
        val tracker = VisionCaptureReadiness()
        for (step in 0..10) assertEquals(WAIT, sample(tracker, step * 100L, yaw = 12.0 + step * 0.4))
    }

    @Test fun `off center initial pose is not mistaken for completed movement`() {
        val tracker = VisionCaptureReadiness()
        assertEquals(WAIT, sample(tracker, 0, yaw = 20.0))
        assertEquals(WAIT, sample(tracker, 100, yaw = 20.0))
        assertEquals(WAIT, sample(tracker, 300, yaw = 20.0))
        assertEquals(WAIT, sample(tracker, 1400, yaw = 20.0))
        assertEquals(CAPTURE, sample(tracker, 1500, yaw = 20.0))
    }

    @Test fun `stalled floor target outside view fails before ten seconds`() {
        val tracker = VisionCaptureReadiness()
        sample(tracker, 0, pitch = -50.0)
        sample(tracker, 500, pitch = -25.0)
        assertEquals(WAIT, sample(tracker, 1000, pitch = -25.0))
        assertEquals(OUTSIDE_VIEW, sample(tracker, 1500, pitch = -25.0))
        assertEquals(-25.0, tracker.lastBearing!!.verticalDegrees, 0.001)
    }

    @Test fun `view boundary and behind targets are not captured`() {
        for ((yaw, pitch) in listOf(23.0 to 0.0, 0.0 to -17.0, -23.0 to 0.0, 0.0 to 17.0)) {
            val tracker = VisionCaptureReadiness()
            sample(tracker, 0, yaw, pitch)
            sample(tracker, 800, yaw, pitch)
            assertEquals(OUTSIDE_VIEW, sample(tracker, 1500, yaw, pitch))
        }
        val behind = VisionCaptureReadiness()
        behind.observe(0, -1.0, 0.0, 0.0)
        behind.observe(800, -1.0, 0.0, 0.0)
        assertEquals(OUTSIDE_VIEW, behind.observe(1500, -1.0, 0.0, 0.0))
    }

    @Test fun `invalid pose resets stability and never permits a photo`() {
        val tracker = VisionCaptureReadiness()
        sample(tracker, 0, yaw = 20.0)
        sample(tracker, 100, yaw = 14.0)
        assertEquals(WAIT, tracker.observe(200, Double.NaN, 0.0, 0.0))
        assertNull(tracker.lastBearing)
        assertEquals(WAIT, sample(tracker, 300, yaw = 14.0))
        assertEquals(WAIT, sample(tracker, 400, yaw = 14.0))
        assertEquals(CAPTURE, sample(tracker, 600, yaw = 14.0))
        assertEquals(WAIT, tracker.observe(700, 0.0, 0.0, 0.0))
    }
}
