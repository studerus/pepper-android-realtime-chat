package ch.fhnw.pepper_realtime.manager

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClockRecoveryTest {
    private class Environment(private val scheduler: TestCoroutineScheduler) : ClockRecovery.Environment {
        var wallTime = 0L
        var automatic = true
        var connected = true
        val requests = mutableListOf<Long>()
        var onRequest: () -> Unit = {}
        override fun wallTimeMs() = wallTime
        override fun elapsedTimeMs() = scheduler.currentTime
        override fun automaticTimeEnabled() = automatic
        override fun isConnected() = connected
        override fun requestNetworkTime() {
            requests.add(elapsedTimeMs())
            onRequest()
        }
    }

    @Test fun `only Pepper Android 6 is supported`() {
        assertTrue(ClockRecovery.isSupported("pepper", 23))
        assertFalse(ClockRecovery.isSupported("standalone", 23))
        assertFalse(ClockRecovery.isSupported("pepper", 24))
        assertFalse(ClockRecovery.isSupported("pepper", 35))
    }

    @Test fun `plausible date does not request a poll`() = runTest {
        val environment = Environment(testScheduler).apply { wallTime = ClockRecovery.MIN_VALID_TIME_MS }
        assertEquals(ClockRecovery.Result.ALREADY_VALID, ClockRecovery(environment).recover())
        assertTrue(environment.requests.isEmpty())
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun `manual time is respected`() = runTest {
        val environment = Environment(testScheduler).apply { automatic = false }
        assertEquals(ClockRecovery.Result.DISABLED, ClockRecovery(environment).recover())
        assertTrue(environment.requests.isEmpty())
    }

    @Test fun `waits for network then stops after clock correction`() = runTest {
        val environment = Environment(testScheduler).apply {
            connected = false
            onRequest = { wallTime = ClockRecovery.MIN_VALID_TIME_MS }
        }
        launch { delay(65000); environment.connected = true }
        assertEquals(ClockRecovery.Result.RECOVERED, ClockRecovery(environment).recover())
        assertEquals(1, environment.requests.size)
        assertTrue(environment.requests.single() >= 65000)
    }

    @Test fun `offline wait is bounded and sends no polls`() = runTest {
        val environment = Environment(testScheduler).apply { connected = false }
        assertEquals(ClockRecovery.Result.TIMED_OUT, ClockRecovery(environment).recover())
        assertTrue(environment.requests.isEmpty())
        assertEquals(ClockRecovery.MAX_DURATION_MS, testScheduler.currentTime)
    }

    @Test fun `progress distinguishes offline wait and synchronization without duplicate updates`() = runTest {
        val environment = Environment(testScheduler).apply { connected = false }
        val stages = mutableListOf<ClockRecoveryStage>()
        launch {
            delay(15000)
            environment.connected = true
            delay(15000)
            environment.connected = false
            delay(15000)
            environment.connected = true
            delay(5000)
            environment.wallTime = ClockRecovery.MIN_VALID_TIME_MS
        }
        assertEquals(ClockRecovery.Result.RECOVERED, ClockRecovery(environment).recover(stages::add))
        assertEquals(listOf(ClockRecoveryStage.WAITING_FOR_NETWORK, ClockRecoveryStage.SYNCHRONIZING,
            ClockRecoveryStage.WAITING_FOR_NETWORK, ClockRecoveryStage.SYNCHRONIZING), stages)
    }

    @Test fun `requests are rate limited and bounded even if wall clock moves backwards`() = runTest {
        val environment = Environment(testScheduler).apply { onRequest = { wallTime -= 1000000 } }
        assertEquals(ClockRecovery.Result.EXHAUSTED, ClockRecovery(environment).recover())
        assertEquals(ClockRecovery.MAX_ATTEMPTS, environment.requests.size)
        assertEquals((0 until ClockRecovery.MAX_ATTEMPTS).map { it * ClockRecovery.RETRY_INTERVAL_MS }, environment.requests)
    }

    @Test fun `final request has time to complete`() = runTest {
        val environment = Environment(testScheduler)
        environment.onRequest = {
            if (environment.requests.size == ClockRecovery.MAX_ATTEMPTS) {
                launch { delay(20000); environment.wallTime = ClockRecovery.MIN_VALID_TIME_MS }
            }
        }
        assertEquals(ClockRecovery.Result.RECOVERED, ClockRecovery(environment).recover())
        assertEquals(ClockRecovery.MAX_ATTEMPTS, environment.requests.size)
    }

    @Test fun `network loss pauses requests without spending attempts`() = runTest {
        val environment = Environment(testScheduler)
        environment.onRequest = { environment.connected = false }
        launch {
            delay(120000)
            environment.connected = true
            environment.onRequest = { environment.wallTime = ClockRecovery.MIN_VALID_TIME_MS }
        }
        assertEquals(ClockRecovery.Result.RECOVERED, ClockRecovery(environment).recover())
        assertEquals(2, environment.requests.size)
        assertTrue(environment.requests.last() >= 120000)
    }

    @Test fun `disabling automatic time stops retries`() = runTest {
        val environment = Environment(testScheduler)
        launch { delay(10000); environment.automatic = false }
        assertEquals(ClockRecovery.Result.DISABLED, ClockRecovery(environment).recover())
        assertEquals(1, environment.requests.size)
    }

    @Test fun `firmware rejection stops retries`() = runTest {
        val environment = Environment(testScheduler).apply { onRequest = { throw SecurityException("denied") } }
        assertEquals(ClockRecovery.Result.REJECTED, ClockRecovery(environment).recover())
        assertEquals(1, environment.requests.size)
    }

    @Test fun `lifecycle cancellation stops further requests`() = runTest {
        val environment = Environment(testScheduler)
        val job = launch { ClockRecovery(environment).recover() }
        runCurrent()
        assertEquals(1, environment.requests.size)
        job.cancelAndJoin()
        advanceTimeBy(ClockRecovery.MAX_DURATION_MS)
        assertEquals(1, environment.requests.size)
    }
}
