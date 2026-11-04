package ch.fhnw.pepper_realtime.tools.vision

import kotlin.math.abs
import kotlin.math.atan2

/** Readiness from successive target bearings in the moving gaze frame, after LookAt starts. */
class VisionCaptureReadiness {
    enum class Decision { WAIT, CAPTURE, OUTSIDE_VIEW }

    data class Bearing(val horizontalDegrees: Double, val verticalDegrees: Double, val inFront: Boolean) {
        // Conservative inner region, not a calibrated camera projection. Leave margin for
        // the offset between the gaze frame (eyes) and the head camera (roughly 56 x 44 deg FOV).
        val withinView: Boolean
            get() = inFront && abs(horizontalDegrees) <= 22.0 && abs(verticalDegrees) <= 16.0

        fun difference(other: Bearing): Double = maxOf(
            angleDifference(horizontalDegrees, other.horizontalDegrees),
            angleDifference(verticalDegrees, other.verticalDegrees)
        )

        private fun angleDifference(a: Double, b: Double): Double =
            abs(((a - b + 540.0) % 360.0) - 180.0)
    }

    var lastBearing: Bearing? = null
        private set
    private var firstSampleAt: Long? = null
    private var initialBearing: Bearing? = null
    private var stableAnchor: Bearing? = null
    private var stableSince = 0L
    private var sampleCount = 0
    private var movementObserved = false

    fun observe(nowMs: Long, x: Double, y: Double, z: Double): Decision {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() || (x == 0.0 && y == 0.0 && z == 0.0)) {
            lastBearing = null
            stableAnchor = null
            sampleCount = 0
            return Decision.WAIT
        }
        val bearing = Bearing(Math.toDegrees(atan2(y, x)), Math.toDegrees(atan2(z, x)), x > 0)
        lastBearing = bearing
        // LookAt is already running and will keep tracking throughout capture.
        // A centered target needs no additional settling window; off-center ones do.
        if (VisionLookTarget.isAligned(x, y, z)) return Decision.CAPTURE
        if (firstSampleAt == null) {
            firstSampleAt = nowMs
            initialBearing = bearing
        }
        if (bearing.difference(initialBearing!!) > 1.0) movementObserved = true
        val anchor = stableAnchor
        if (anchor == null || bearing.difference(anchor) > 0.6 || bearing.withinView != anchor.withinView) {
            stableAnchor = bearing
            stableSince = nowMs
            sampleCount = 1
        } else sampleCount++

        val elapsed = nowMs - firstSampleAt!!
        val stableFor = nowMs - stableSince
        // Do not mistake a delayed movement start for reaching a mechanical limit.
        if (bearing.withinView && sampleCount >= 3 && stableFor >= 250 &&
            (movementObserved || elapsed >= 1500)) return Decision.CAPTURE
        if (!bearing.withinView && sampleCount >= 3 && stableFor >= 800 && elapsed >= 1500) {
            return Decision.OUTSIDE_VIEW
        }
        return Decision.WAIT
    }
}
