package ch.fhnw.pepper_realtime.manager

import ch.fhnw.pepper_realtime.controller.MovementController
import ch.fhnw.pepper_realtime.tools.OperationScope
import com.aldebaran.qi.Consumer
import com.aldebaran.qi.Future
import com.aldebaran.qi.Promise
import com.aldebaran.qi.sdk.QiContext
import org.junit.*
import org.junit.Assert.*
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.*

class NavigationLifetimeTest {
    private val nativeLoader = mockStatic(Class.forName("com.aldebaran.qi.EmbeddedTools"))
    private val promises = mockConstruction(Promise::class.java) { promise, _ ->
        doReturn(mock<Future<Any>>()).whenever(promise).future
    }
    private val movement = mock<MovementController>()
    private val manager = spy(NavigationServiceManager(movement))
    private val qi = mock<QiContext>()
    private val preparation = mock<Future<Void>>()
    private val completions = mutableListOf<Consumer<Future<Void>>>()

    @Before fun setUp() {
        doReturn(preparation).whenever(manager).ensureAutonomousAbilitiesHeld(qi)
        whenever(preparation.thenConsume(any())).thenAnswer { completions.add(it.getArgument(0)); null }
    }
    @After fun tearDown() { manager.cancelAllActions(); promises.close(); nativeLoader.close() }

    @Suppress("UNCHECKED_CAST")
    private fun resultPromise(index: Int = 0) = promises.constructed()[index] as Promise<NavigationServiceManager.MovementResult>

    @Test fun `failed preparation releases busy state and resolves exactly once`() {
        manager.movePepper(qi, 1.0, 0.0, 0.4)
        whenever(preparation.hasError()).thenReturn(true)
        completions[0].consume(preparation)
        val result = argumentCaptor<NavigationServiceManager.MovementResult>()
        verify(resultPromise()).setValue(result.capture())
        assertFalse(result.firstValue.success)
        whenever(preparation.hasError()).thenReturn(false)
        manager.movePepper(qi, 2.0, 0.0, 0.4)
        assertEquals(2, completions.size)
        completions[0].consume(preparation) // Old preparation must not start movement two.
        verify(movement, never()).movePepper(anyOrNull(), any(), any(), any(), anyOrNull(), anyOrNull())
        completions[1].consume(preparation)
        verify(movement).movePepper(same(qi), eq(2.0), eq(0.0), eq(0.4), any(), any())
    }

    @Test fun `chat cancellation during preparation prevents a delayed movement start`() {
        val owner = OperationScope()
        manager.turnPepper(qi, "left", 90.0, 0.4, owner)
        owner.cancel()
        verify(preparation).requestCancellation()
        completions.single().consume(preparation)
        verify(movement, never()).turnPepper(anyOrNull(), any(), any(), any(), anyOrNull(), anyOrNull())
        val result = argumentCaptor<NavigationServiceManager.MovementResult>()
        verify(resultPromise(), times(1)).setValue(result.capture())
        assertFalse(result.firstValue.success)
    }

    @Test fun `old navigation cleanup cannot release the next process lock`() {
        val a = OperationScope(); val b = OperationScope()
        assertTrue(manager.tryStartNavigationProcess(a))
        a.cancel()
        assertTrue(manager.tryStartNavigationProcess(b))
        manager.endNavigationProcess(a)
        assertTrue(manager.isNavigationProcessActive)
        b.cancel()
        assertFalse(manager.isNavigationProcessActive)
    }

    @Test fun `mapping holds service mode until its owner ends`() {
        val owner = OperationScope()
        assertTrue(manager.beginMapping(owner))
        assertTrue(manager.areGesturesSuppressed())
        manager.handleServiceStateChange("resumeNormalOperation")
        assertTrue(manager.areGesturesSuppressed())
        owner.cancel()
        assertFalse(manager.areGesturesSuppressed())
        assertFalse(manager.isNavigationProcessActive)
    }

    @Test fun `old mapping cleanup cannot release a newer map session`() {
        val first = OperationScope(); val next = OperationScope()
        assertTrue(manager.beginMapping(first))
        first.cancel()
        assertTrue(manager.beginMapping(next))
        manager.endMapping(first)
        assertTrue(manager.areGesturesSuppressed())
        assertTrue(manager.isNavigationProcessActive)
        next.cancel()
        assertFalse(manager.areGesturesSuppressed())
    }

    @Test fun `map finalization cancels guidance and rejects new movement until cleanup`() {
        val owner = OperationScope()
        assertTrue(manager.beginMapping(owner))
        manager.movePepper(qi, 1.0, 0.0, 0.4)
        manager.prepareMappingFinish(owner)
        assertEquals(1, completions.size)
        manager.movePepper(qi, 2.0, 0.0, 0.4)
        assertEquals(1, completions.size)
        completions[0].consume(preparation)
        verify(movement, never()).movePepper(anyOrNull(), any(), any(), any(), anyOrNull(), anyOrNull())
        owner.cancel()
        manager.movePepper(qi, 3.0, 0.0, 0.4)
        assertEquals(2, completions.size)
    }
}
