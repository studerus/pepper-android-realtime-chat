package ch.fhnw.pepper_realtime.manager

import org.junit.*
import org.junit.Assert.*
import org.mockito.kotlin.*
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

class SpeechRecognizerManagerTest {
    private val executor = ManualExecutor()
    private val callbacks = mock<SpeechRecognizerManager.ActivityCallbacks>()
    private val sessions = mutableListOf<FakeSession>()
    private val startFailures = ArrayDeque<String?>()
    private var initializationFails = false
    private val manager = SpeechRecognizerManager(executor) { _, events ->
        if (initializationFails) throw IllegalStateException("initialization failed")
        FakeSession(events, if (startFailures.isEmpty()) null else startFailures.removeFirst()).also {
            sessions.add(it); events.connected()
        }
    }

    @Before fun setUp() { manager.setCallbacks(callbacks) }
    @After fun tearDown() { manager.shutdown(); executor.runAll() }
    private fun configure() { manager.configure("key", "region", "de-CH", 500, 0.7); executor.runAll() }
    private fun start() { manager.start(); executor.runAll() }

    @Test fun `failed start clears readiness and a later start succeeds`() {
        startFailures.add("network failure")
        configure(); start()
        assertTrue(sessions[0].closed)
        verify(callbacks).onStopped(); verify(callbacks).onError(any())
        verify(callbacks, never()).onStarted() // SDK readiness events before a failed start are insufficient.
        start()
        assertEquals(2, sessions.size)
        verify(callbacks).onStarted()
    }

    @Test fun `failed lazy retry also permits a later fresh start`() {
        startFailures.add("0x22 invalid recognizer"); startFailures.add("retry failed")
        configure(); start()
        assertEquals(2, sessions.size)
        assertTrue(sessions.all { it.closed })
        verify(callbacks, times(1)).onError(any())
        start(); verify(callbacks).onStarted()
        assertEquals(3, sessions.size)
    }

    @Test fun `successful lazy retry uses the same readiness rules`() {
        startFailures.add("SPXERR_INVALID_RECOGNIZER")
        configure(); start()
        assertEquals(2, sessions.size)
        verify(callbacks).onStarted()
        verify(callbacks, never()).onError(anyOrNull())
        sessions[1].events.sessionStopped()
        start()
        assertEquals(2, sessions[1].starts)
    }

    @Test fun `old recognizer events cannot stop or report results into the retry`() {
        startFailures.add("failed")
        configure(); start()
        val old = sessions[0]
        start()
        clearInvocations(callbacks)
        old.events.connected(); old.events.sessionStarted(); old.events.sessionStopped()
        old.events.recognized("stale", 1.0); old.events.partial("stale"); old.events.failed("stale")
        verifyNoInteractions(callbacks)
        manager.start(); executor.runAll()
        assertEquals(1, sessions[1].starts)
    }

    @Test fun `duplicate queued starts execute only once`() {
        configure()
        manager.start(); manager.start(); executor.runAll()
        assertEquals(1, sessions.single().starts)
        verify(callbacks).onStarted()
    }

    @Test fun `stop before queued start prevents recognition from starting`() {
        configure()
        manager.start(); manager.stop(); executor.runAll()
        assertEquals(0, sessions.single().starts)
        verify(callbacks, never()).onStarted()
        start(); verify(callbacks).onStarted()
    }

    @Test fun `normal stop and start reuse the warm connection`() {
        configure(); start()
        manager.stop(); executor.runAll()
        start()
        assertEquals(1, sessions.size)
        assertEquals(2, sessions.single().starts)
        verify(callbacks, times(2)).onStarted()
    }

    @Test fun `stop cancellation event cannot cancel the next queued start`() {
        configure(); start()
        sessions.single().cancelOnStop = true
        manager.stop(); manager.start(); executor.runAll()
        assertEquals(2, sessions.single().starts)
        verify(callbacks, times(2)).onStarted()
        verify(callbacks, never()).onError(anyOrNull())
    }

    @Test fun `failed stop discards the old recognizer before restarting`() {
        configure(); start()
        sessions.single().stopFails = true
        manager.stop(); manager.start(); executor.runAll()
        assertEquals(2, sessions.size)
        assertTrue(sessions[0].closed)
        verify(callbacks, times(2)).onStarted()
    }

    @Test fun `runtime cancellation resets state and reports one error`() {
        configure(); start()
        val failed = sessions.single()
        failed.events.failed("service error"); failed.events.failed("duplicate")
        executor.runAll()
        assertTrue(failed.closed)
        verify(callbacks, times(1)).onError("service error")
        start(); assertEquals(2, sessions.size)
    }

    @Test fun `initialization failure can recover on explicit start`() {
        initializationFails = true; configure()
        verify(callbacks).onError(any())
        initializationFails = false; start()
        verify(callbacks).onStarted()
    }

    @Test fun `shutdown suppresses queued preparation and readiness events`() {
        manager.configure("key", "region", "de-CH", 500, 0.7)
        manager.start(); manager.shutdown(); executor.runAll()
        assertTrue(sessions.isEmpty())
        verifyNoInteractions(callbacks)
    }

    @Test fun `stop during initialization does not auto start through onReady`() {
        manager.configure("key", "region", "de-CH", 500, 0.7)
        manager.stop(); executor.runAll()
        verify(callbacks, never()).onReady()
        start(); verify(callbacks).onStarted()
    }

    @Test fun `shutdown closes the recognizer even if stop fails`() {
        configure(); start()
        sessions.single().stopFails = true
        manager.shutdown(); executor.runAll()
        assertTrue(sessions.single().closed)
    }

    private class FakeSession(val events: SpeechSession.Events, val failure: String?) : SpeechSession {
        var starts = 0
        var closed = false
        var stopFails = false
        var cancelOnStop = false
        override fun start() {
            starts++
            events.sessionStarted()
            failure?.let { throw IllegalStateException(it) }
        }
        override fun stop() {
            if (stopFails) throw IllegalStateException("stop failed")
            if (cancelOnStop) events.failed("Recognition was stopped")
            events.sessionStopped()
        }
        override fun close() { closed = true }
    }

    private class ManualExecutor : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { check(!stopped); tasks.add(command) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return tasks.toMutableList().also { tasks.clear() } }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }
}
