package ch.fhnw.pepper_realtime.tools

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatWorkRegistryTest {
    @Test fun `ending session cancels returned background actions and invalidates late callbacks`() {
        val registry = ChatWorkRegistry()
        val first = registry.begin()
        val call = first.beginCall("same-id")!!
        var stopped = 0
        call.onCancel { stopped++ }
        registry.end()
        registry.end()
        assertEquals(1, stopped)
        assertFalse(call.runIfActive { fail("Old callback delivered") })
        val next = registry.begin().beginCall("same-id")!!
        assertTrue(next.isActive)
        assertNull(first.beginCall("late-call"))
    }

    @Test fun `individual cancellation does not affect another call`() {
        val session = ChatWorkRegistry().begin()
        val a = session.beginCall("a")!!
        val b = session.beginCall("b")!!
        session.cancelCall("a")
        assertFalse(a.isActive)
        assertTrue(b.isActive)
        assertTrue(session.hasPendingCalls())
        session.completeCall("b")
        assertFalse(session.hasPendingCalls())
        assertNull(session.beginCall("a")) // No duplicate execution after cancellation.
    }

    @Test fun `resources registered after cancellation are cancelled immediately`() {
        val owner = OperationScope()
        var stopped = 0
        owner.cancel()
        owner.onCancel { stopped++ }
        assertEquals(1, stopped)
    }

    @Test fun `completed resources detach and a failed cleanup does not stop others`() {
        val owner = OperationScope()
        var stopped = 0
        owner.onCancel { fail("Completed resource cancelled") }.close()
        owner.onCancel { throw IllegalStateException("Broken cleanup") }
        owner.onCancel { stopped++ }
        owner.cancel()
        assertEquals(1, stopped)
    }

    @Test fun `queued tool never starts after session end`() = runTest {
        val owner = ChatWorkRegistry().begin().beginCall("a")!!
        var executed = false
        val job = owner.launchIn(this, StandardTestDispatcher(testScheduler)) { executed = true }
        owner.cancel()
        runCurrent()
        assertFalse(executed)
        assertTrue(job.isCancelled)
    }
}
