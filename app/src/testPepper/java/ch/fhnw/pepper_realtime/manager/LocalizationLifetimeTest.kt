package ch.fhnw.pepper_realtime.manager

import android.os.Handler
import ch.fhnw.pepper_realtime.tools.OperationScope
import com.aldebaran.qi.Consumer
import com.aldebaran.qi.Future
import com.aldebaran.qi.Promise
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.LocalizeBuilder
import com.aldebaran.qi.sdk.`object`.actuation.ExplorationMap
import com.aldebaran.qi.sdk.`object`.actuation.Localize
import com.aldebaran.qi.sdk.`object`.actuation.LocalizationStatus
import org.junit.*
import org.junit.Assert.*
import org.mockito.Answers.RETURNS_DEEP_STUBS
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.mockConstruction
import org.mockito.kotlin.*

class LocalizationLifetimeTest {
    private val nativeLoader = mockStatic(Class.forName("com.aldebaran.qi.EmbeddedTools"))
    private val promises = mockConstruction(Promise::class.java) { promise, _ ->
        doReturn(mock<Future<Any>>()).whenever(promise).future
    }
    private val builders = mockStatic(LocalizeBuilder::class.java)
    private val qi = mock<QiContext>()
    private val map = mock<ExplorationMap>()
    private val builder = mock<LocalizeBuilder>()
    private val action = mock<Localize>(defaultAnswer = RETURNS_DEEP_STUBS)
    private val future = mock<Future<Void>>()
    private val success = mock<Runnable>()
    private val failure = mock<Runnable>()
    private val coordinator = LocalizationCoordinator(mock<Handler>(), {}, { _, _ -> })

    @Before fun setUp() {
        builders.`when`<LocalizeBuilder> { LocalizeBuilder.with(qi) }.thenReturn(builder)
        whenever(builder.withMap(map)).thenReturn(builder)
        whenever(builder.build()).thenReturn(action)
        whenever(action.async().run()).thenReturn(future)
    }
    @After fun tearDown() { coordinator.reset(); builders.close(); promises.close(); nativeLoader.close() }

    @Suppress("UNCHECKED_CAST")
    private fun resultPromise() = promises.constructed().first() as Promise<Any?>

    @Test fun `cancel before localized resolves failure and rejects late status`() {
        val owner = OperationScope()
        coordinator.ensureLocalizationIfNeeded(qi, map, success, failure, owner)
        val status = argumentCaptor<Localize.OnStatusChangedListener>()
        verify(action).addOnStatusChangedListener(status.capture())
        owner.cancel()
        status.firstValue.onStatusChanged(LocalizationStatus.LOCALIZED)
        verify(future).requestCancellation()
        verify(failure, times(1)).run()
        verify(success, never()).run()
        verify(resultPromise()).setValue(false)
        assertFalse(coordinator.isLocalizationReady)
    }

    @Test fun `SDK cancellation also settles a localization still waiting for status`() {
        coordinator.ensureLocalizationIfNeeded(qi, map, success, failure)
        val completion = argumentCaptor<Consumer<Future<Void>>>()
        verify(future).thenConsume(completion.capture())
        whenever(future.isCancelled).thenReturn(true)
        completion.firstValue.consume(future)
        completion.firstValue.consume(future)
        verify(failure, times(1)).run()
        verify(resultPromise()).setValue(false)
        assertFalse(coordinator.isLocalizationReady)
    }

    @Test fun `losing localization clears readiness without completing promise twice`() {
        coordinator.ensureLocalizationIfNeeded(qi, map, success, failure)
        val status = argumentCaptor<Localize.OnStatusChangedListener>()
        verify(action).addOnStatusChangedListener(status.capture())
        status.firstValue.onStatusChanged(LocalizationStatus.LOCALIZED)
        assertTrue(coordinator.isLocalizationReady)
        status.firstValue.onStatusChanged(LocalizationStatus.SCANNING)
        assertFalse(coordinator.isLocalizationReady)
        coordinator.stopCurrentLocalization()
        verify(success, times(1)).run()
        verify(failure, never()).run()
        verify(resultPromise(), times(1)).setValue(true)
        verify(resultPromise(), never()).setValue(false)
    }
}
