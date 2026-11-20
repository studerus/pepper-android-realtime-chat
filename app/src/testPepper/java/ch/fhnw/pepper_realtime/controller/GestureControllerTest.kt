package ch.fhnw.pepper_realtime.controller

import com.aldebaran.qi.Consumer
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.actuation.Animate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Answers.RETURNS_DEEP_STUBS
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.*
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class GestureControllerTest {
    private val nativeLoader = mockStatic(Class.forName("com.aldebaran.qi.EmbeddedTools"))
    private val scheduler = mock<ScheduledExecutorService>()
    private val context = mock<QiContext>()
    private val tasks = mutableListOf<Runnable>()
    private val builds = mutableListOf<Pending<Animate>>()
    private val controller = GestureController({ scheduler }) { _, _ ->
        Pending(mock<Future<Animate>>()).also { builds.add(it) }.future
    }

    private class Pending<T>(val future: Future<T>) {
        private lateinit var completion: Consumer<Future<T>>
        init {
            whenever(future.thenConsume(any())).thenAnswer { completion = it.getArgument(0); null }
        }
        fun finish(value: T? = null, success: Boolean = true) {
            whenever(future.isSuccess).thenReturn(success)
            whenever(future.value).thenReturn(value)
            completion.consume(future)
        }
    }

    @Before fun setup() {
        whenever(scheduler.schedule(any<Runnable>(), any<Long>(), eq(TimeUnit.MILLISECONDS))).thenAnswer {
            tasks.add(it.getArgument(0))
            mock<ScheduledFuture<Any>>()
        }
    }
    @After fun teardown() { controller.shutdown(); nativeLoader.close() }

    private fun start() {
        controller.start(context, { true }, { 1 })
        tasks.removeAt(0).run()
    }

    @Test fun `late build from lost focus cannot start motion`() {
        start()
        controller.onRobotFocusLost()
        verify(builds.single().future).requestCancellation()
        start() // Even if QiSDK supplies the same context object again.
        val stale = mock<Animate>()
        builds.first().finish(stale)
        verifyNoInteractions(stale)
        val fresh = mock<Animate>(defaultAnswer = RETURNS_DEEP_STUBS)
        builds.last().finish(fresh)
        verify(fresh.async()).run()
    }

    @Test fun `ordinary turns reuse actions but a new focus rebuilds them`() {
        start()
        val action = mock<Animate>(defaultAnswer = RETURNS_DEEP_STUBS)
        val run = Pending(mock<Future<Void>>())
        whenever(action.async().run()).thenReturn(run.future)
        builds.single().finish(action)
        controller.stopNow()
        start()
        assertEquals(1, builds.size)
        verify(action.async(), times(2)).run()
        controller.onRobotFocusLost()
        run.finish() // Old run completion must not enqueue another gesture.
        assertEquals(0, tasks.size)
        start()
        assertEquals(2, builds.size)
    }

    @Test fun `failed build retries only while gestures are allowed`() {
        var allowed = true
        controller.start(context, { allowed }, { 1 })
        tasks.removeAt(0).run()
        builds.single().finish(success = false)
        tasks.removeAt(0).run()
        assertEquals(2, builds.size)
        allowed = false
        val action = mock<Animate>()
        builds.last().finish(action)
        verifyNoInteractions(action)
        assertEquals(0, tasks.size)
    }
}
