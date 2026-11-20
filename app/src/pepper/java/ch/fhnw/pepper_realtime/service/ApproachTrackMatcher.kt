package ch.fhnw.pepper_realtime.service

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

internal object ApproachTrackMatcher {
    const val MAX_AGE_MS = 750L

    data class Position(val yaw: Double, val distance: Double) {
        val valid get() = yaw.isFinite() && distance.isFinite() && distance > 0

        companion object {
            fun fromRobotFrame(x: Double, y: Double) =
                Position(Math.toDegrees(atan2(y, x)), hypot(x, y))
        }
    }

    // Compare torso-relative bearings and approximate optical ranges. Use a wider
    // exclusion window for competitors than the acceptance window for the target.
    private fun near(a: Position, b: Position, loose: Boolean): Boolean {
        val angle = abs(Math.IEEEremainder(a.yaw - b.yaw, 360.0))
        val tolerance = if (loose) (a.distance * 0.35).coerceIn(0.55, 0.9)
                        else (a.distance * 0.25).coerceIn(0.35, 0.65)
        return angle <= (if (loose) 20.0 else 12.0) && abs(a.distance - b.distance) <= tolerance
    }

    fun match(trackId: Int, update: PerceptionWebSocketClient.PeopleUpdate?, candidates: List<Position>, now: Long): Int? {
        if (update == null || candidates.any { !it.valid }) return null
        val delay = now - update.receivedElapsedMs
        if (delay !in 0..MAX_AGE_MS) return null
        val target = update.people.singleOrNull { it.trackId == trackId } ?: return null
        if (!target.observed || target.timeSinceSeenMs !in 0..(MAX_AGE_MS - delay)) return null
        fun position(p: PerceptionWebSocketClient.TrackedPerson) = Position(p.worldYaw.toDouble(), p.distance.toDouble())
        val requested = position(target)
        if (!requested.valid) return null
        val index = candidates.indices.singleOrNull { near(requested, candidates[it], loose = true) } ?: return null
        if (!near(requested, candidates[index], loose = false)) return null
        if (update.people.any { it !== target && position(it).let { other ->
            !other.valid || near(other, candidates[index], loose = true) || near(other, requested, loose = true)
        } }) return null
        return index
    }
}
