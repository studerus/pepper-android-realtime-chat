package ch.fhnw.pepper_realtime.service

import ch.fhnw.pepper_realtime.service.ApproachTrackMatcher.Position
import ch.fhnw.pepper_realtime.service.PerceptionWebSocketClient.PeopleUpdate
import ch.fhnw.pepper_realtime.service.PerceptionWebSocketClient.TrackedPerson
import org.junit.Assert.*
import org.junit.Test

class ApproachTrackMatcherTest {
    private val target = TrackedPerson(42, "Unknown", false, "", 30f, 0f, 2f, 0, 100, 0, 0, true)
    private val candidate = Position.fromRobotFrame(1.732, 1.0)
    private fun frame(vararg people: TrackedPerson) = PeopleUpdate(people.toList(), 0L, 1000L)
    private fun resolve(update: PeopleUpdate?, vararg positions: Position, now: Long = 1020L) =
        ApproachTrackMatcher.match(42, update, positions.toList(), now)

    @Test fun `unique camera and SDK positions match independent of order or numeric IDs`() {
        val other = Position.fromRobotFrame(1.73, -1.0)
        assertEquals(1, resolve(frame(target.copy(trackId = 7, worldYaw = -30f), target), other, candidate))
        assertNull(resolve(frame(target), other))
        assertNull(resolve(frame(target), Position(30.0, 4.0)))
    }
    @Test fun `both systems must agree uniquely`() {
        assertNull(resolve(frame(target), candidate, Position(44.0, 2.0)))
        assertNull(resolve(frame(target, target.copy(trackId = 9, worldYaw = 36f)), candidate))
        assertNull(resolve(frame(target), candidate, Position(Double.NaN, 2.0)))
    }
    @Test fun `missing disappeared predicted and stale targets cannot start movement`() {
        assertNull(resolve(null, candidate))
        assertNull(resolve(frame(), candidate))
        assertNull(resolve(frame(target.copy(observed = false)), candidate))
        assertNull(resolve(frame(target.copy(timeSinceSeenMs = -1)), candidate))
        assertNull(resolve(frame(target.copy(distance = 0f)), candidate))
        assertNull(resolve(frame(target), candidate, now = 1700L))
        assertNull(resolve(frame(target), candidate, now = 900L))
    }
}
