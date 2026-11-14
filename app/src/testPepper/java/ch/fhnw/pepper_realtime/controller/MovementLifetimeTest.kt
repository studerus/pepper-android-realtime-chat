package ch.fhnw.pepper_realtime.controller

import ch.fhnw.pepper_realtime.tools.OperationScope
import com.aldebaran.qi.Consumer
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.`object`.actuation.GoTo
import org.junit.*
import org.junit.Assert.*
import org.mockito.Answers.RETURNS_DEEP_STUBS
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.*
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MovementLifetimeTest {
    private val nativeLoader = mockStatic(Class.forName("com.aldebaran.qi.EmbeddedTools"))
    private val scheduler = mock<ScheduledExecutorService>()
    private val listener = mock<MovementController.MovementListener>()
    private val controller = MovementController(scheduler).apply { setListener(listener) }
    private val timers = mutableListOf<Runnable>()
    private val timerHandles = mutableListOf<ScheduledFuture<*>>()

    @Before fun setUp() {
        whenever(scheduler.schedule(any<Runnable>(), eq(15L), eq(TimeUnit.SECONDS))).thenAnswer {
            timers.add(it.getArgument(0))
            mock<ScheduledFuture<Any>>().also { handle -> timerHandles.add(handle) }
        }
    }
    @After fun tearDown() { controller.shutdown(); nativeLoader.close() }

    private class Action {
        val action = mock<GoTo>(defaultAnswer = RETURNS_DEEP_STUBS)
        val future = mock<Future<Void>>()
        lateinit var completion: Consumer<Future<Void>>
        init {
            whenever(action.async().run()).thenReturn(future)
            whenever(future.thenConsume(any())).thenAnswer { completion = it.getArgument(0); null }
        }
        fun finish(cancelled: Boolean = false) {
            whenever(future.isCancelled).thenReturn(cancelled)
            whenever(future.isSuccess).thenReturn(!cancelled)
            completion.consume(future)
        }
    }

    @Test fun `old timer cannot cancel the next movement`() {
        val a = Action(); val b = Action()
        controller.runMovement { a.action }
        a.finish()
        verify(timerHandles[0]).cancel(false)
        controller.runMovement { b.action }
        timers[0].run() // Simulate a timer already queued when cancellation happened.
        verify(b.future, never()).requestCancellation()
        verify(listener, times(1)).onMovementFinished(true, null)
        b.finish()
        verify(listener, times(2)).onMovementFinished(true, null)
    }

    @Test fun `timeout and cancellation callback report exactly one result`() {
        val a = Action()
        controller.runMovement { a.action }
        timers.single().run()
        a.finish(cancelled = true)
        verify(a.future, times(1)).requestCancellation()
        verify(listener, times(1)).onMovementFinished(eq(false), any())
        verify(timerHandles.single()).cancel(false)
    }

    @Test fun `cancel during preparation never starts the built action`() {
        val a = Action()
        val owner = OperationScope()
        controller.runMovement(owner) { owner.cancel(); a.action }
        verify(a.action, never()).addOnStartedListener(any())
        assertTrue(timers.isEmpty())
        verify(listener, times(1)).onMovementFinished(eq(false), any())
    }

    @Test fun `old cancellation callback cannot finish or cancel a new action`() {
        val a = Action(); val b = Action()
        val oldOwner = OperationScope()
        controller.runMovement(oldOwner) { a.action }
        oldOwner.cancel()
        controller.runMovement { b.action }
        a.finish(cancelled = true)
        oldOwner.cancel()
        verify(b.future, never()).requestCancellation()
        verify(listener, times(1)).onMovementFinished(eq(false), any())
        b.finish()
    }

    @Test fun `shutdown cancels action timer and rejects subsequent starts`() {
        val a = Action()
        controller.runMovement { a.action }
        controller.shutdown()
        verify(a.future).requestCancellation()
        verify(timerHandles.single()).cancel(false)
        controller.runMovement { fail("Started after shutdown"); a.action }
        verify(listener, times(2)).onMovementFinished(eq(false), any())
    }

    @Test fun `SDK cancellation error still clears the timer and resolves movement`() {
        val a = Action()
        val owner = OperationScope()
        whenever(a.future.requestCancellation()).thenThrow(IllegalStateException("SDK disconnected"))
        controller.runMovement(owner) { a.action }
        owner.cancel()
        verify(timerHandles.single()).cancel(false)
        verify(listener, times(1)).onMovementFinished(eq(false), any())
        val b = Action()
        controller.runMovement { b.action }
        b.finish()
        verify(listener).onMovementFinished(true, null)
    }
}
