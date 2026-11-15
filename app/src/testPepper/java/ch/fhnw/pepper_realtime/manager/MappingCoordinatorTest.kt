package ch.fhnw.pepper_realtime.manager

import android.content.Context
import ch.fhnw.pepper_realtime.data.LocationProvider
import ch.fhnw.pepper_realtime.data.NavigationStore
import ch.fhnw.pepper_realtime.tools.OperationScope
import ch.fhnw.pepper_realtime.tools.ToolContext
import ch.fhnw.pepper_realtime.tools.interfaces.RealtimeMessageSender
import ch.fhnw.pepper_realtime.tools.interfaces.ToolHost
import ch.fhnw.pepper_realtime.tools.navigation.FinishEnvironmentMapTool
import com.aldebaran.qi.Consumer
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.actuation.ExplorationMap
import com.aldebaran.qi.sdk.`object`.actuation.LocalizeAndMap
import com.aldebaran.qi.sdk.`object`.actuation.LocalizationStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.mockito.Answers.RETURNS_DEEP_STUBS
import org.mockito.ArgumentMatchers.contains
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*

@OptIn(ExperimentalCoroutinesApi::class)
class MappingCoordinatorTest {
    @get:Rule val folder = TemporaryFolder()
    private val nativeLoader = mockStatic(Class.forName("com.aldebaran.qi.EmbeddedTools"))
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = TestScope(dispatcher)
    private val navigation = mock<NavigationServiceManager>()
    private val qi = mock<QiContext>(defaultAnswer = RETURNS_DEEP_STUBS)
    private val action = mock<LocalizeAndMap>(defaultAnswer = RETURNS_DEEP_STUBS)
    private val map = mock<ExplorationMap>()
    private val host = mock<ToolHost>()
    private val app = mock<Context>()
    private val sender = mock<RealtimeMessageSender>()
    private val locations = mock<LocationProvider>()
    private val chat = OperationScope()
    private val mapping = ControlledFuture<Void>()
    private val localization = ControlledFuture<Boolean>()
    private var failureTarget: String? = null
    private lateinit var store: NavigationStore
    private lateinit var context: ToolContext
    private lateinit var coordinator: MappingCoordinator

    @Before fun setUp() {
        File(folder.newFolder("maps"), "default_map.map").writeText("old map")
        File(folder.newFolder("locations"), "Kitchen.loc").writeText("old location")
        store = NavigationStore(folder.root) { source, target ->
            if (target.name == failureTarget) throw IOException("disk full")
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            Unit
        }
        whenever(host.getAppContext()).thenReturn(app)
        whenever(app.filesDir).thenReturn(folder.root)
        context = spy(ToolContext(host, null, mock(), mock(), navigation, mock(), mock(), mock(), locations, sender, chat))
        context.updateQiContext(qi)
        doReturn(store).whenever(context).navigationStore
        whenever(qi.power.chargingFlap.state.open).thenReturn(false)
        whenever(navigation.beginMapping(any())).thenReturn(true)
        val stopped = ControlledFuture<Void>().apply { complete() }
        whenever(navigation.stopCurrentLocalization()).thenReturn(stopped.future)
        whenever(action.async().run()).thenReturn(mapping.future)
        whenever(action.status).thenReturn(LocalizationStatus.LOCALIZED)
        whenever(action.dumpMap()).thenReturn(map)
        whenever(map.serialize()).thenReturn("new map")
        mapping.cancelCompletes = true
        localization.complete(true)
        whenever(navigation.ensureLocalizationIfNeeded(eq(qi), anyOrNull(), anyOrNull(), same(chat))).thenReturn(localization.future)
        doAnswer { it.getArgument<Runnable>(1).run(); null }.whenever(navigation).cacheNewMap(same(map), anyOrNull(), anyOrNull())
        coordinator = MappingCoordinator(navigation, scope, dispatcher) { action }
        whenever(navigation.mappingCoordinator).thenReturn(coordinator)
        clearInvocations(action)
    }

    @After fun tearDown() { chat.cancel(); scheduler.runCurrent(); nativeLoader.close() }

    private fun begin(): NavigationStore.Snapshot {
        assertTrue(coordinator.start(context))
        scheduler.runCurrent()
        assertTrue(coordinator.isLocalized)
        val draft = store.visible()
        store.writeLocation(draft, "Kitchen", "new location")
        return draft
    }

    private fun assertOld() {
        assertEquals("old map", store.active().map.readText())
        assertEquals("old location", store.locationFile(store.active(), "Kitchen").readText())
        assertFalse(store.isMapping())
    }

    private fun assertReleased() = verify(navigation, times(1)).endMapping(any())

    @Test fun `finish without mapping has no service side effects`() {
        val result = JSONObject(FinishEnvironmentMapTool().execute(JSONObject(), context))
        assertTrue(result.has("error"))
        verify(navigation).mappingCoordinator
        verifyNoMoreInteractions(navigation)
        verifyNoInteractions(locations, action)
        assertOld()
    }

    @Test fun `successful finalization commits matching destinations and releases mode once`() {
        val draft = begin()
        assertTrue(coordinator.finish(context))
        assertFalse(coordinator.finish(context))
        scheduler.runCurrent()
        assertEquals(draft, store.active())
        assertEquals("new map", store.active().map.readText())
        assertEquals("new location", store.locationFile(store.active(), "Kitchen").readText())
        assertFalse(store.isMapping())
        assertReleased()
        verify(localization.future, never()).requestCancellation()
        verify(sender).sendScopedMessage(contains("ORIENTATION COMPLETED"), eq(true), eq(true), same(chat))
    }

    @Test fun `chat cancellation discards draft and permits a new run`() {
        val draft = begin()
        chat.cancel()
        scheduler.runCurrent()
        assertOld()
        assertFalse(draft.locations.exists())
        assertReleased()
        verify(action).removeAllOnStatusChangedListeners()
        val next = context.forOperation(OperationScope())
        val nextContext = spy(next)
        doReturn(store).whenever(nextContext).navigationStore
        // A different action is intentionally not started: preparation is still queued.
        assertTrue(coordinator.start(nextContext))
        nextContext.operation!!.cancel()
        scheduler.runCurrent()
        assertOld()
    }

    @Test fun `unexpected mapping completion preserves old data`() {
        begin()
        mapping.complete()
        scheduler.runCurrent()
        assertOld()
        assertReleased()
        verify(sender).sendScopedMessage(contains("stopped before it was saved"), eq(true), eq(true), same(chat))
    }

    @Test fun `repeated cancellation cannot bypass a still stopping old mapping`() {
        begin()
        mapping.cancelCompletes = false
        chat.cancel()
        fun newContext(owner: OperationScope) = spy(context.forOperation(owner)).also {
            doReturn(store).whenever(it).navigationStore
        }
        val secondOwner = OperationScope()
        assertTrue(coordinator.start(newContext(secondOwner)))
        scheduler.runCurrent()
        secondOwner.cancel()
        val thirdOwner = OperationScope()
        assertTrue(coordinator.start(newContext(thirdOwner)))
        scheduler.runCurrent()
        assertFalse(coordinator.isLocalized)
        verify(action.async(), times(1)).run()
        val next = ControlledFuture<Void>()
        whenever(action.async().run()).thenReturn(next.future)
        mapping.complete()
        scheduler.runCurrent()
        assertTrue(coordinator.isLocalized)
        thirdOwner.cancel()
        scheduler.runCurrent()
        assertOld()
    }

    @Test fun `failed old mapping does not poison the next attempt`() {
        begin()
        whenever(mapping.future.hasError()).thenReturn(true)
        mapping.complete()
        scheduler.runCurrent()
        assertOld()
        val next = ControlledFuture<Void>()
        whenever(action.async().run()).thenReturn(next.future)
        assertTrue(coordinator.start(context))
        scheduler.runCurrent()
        assertTrue(coordinator.isLocalized)
        assertTrue(store.isMapping())
    }

    @Test fun `robot stop failure during finalization cannot publish a map`() {
        begin()
        whenever(mapping.future.hasError()).thenReturn(true)
        mapping.cancelCompletes = false
        assertTrue(coordinator.finish(context))
        scheduler.runCurrent()
        mapping.complete()
        scheduler.runCurrent()
        assertOld()
        assertReleased()
        verify(action, never()).dumpMap()
    }

    @Test fun `serialization failure preserves old data and resumes services`() {
        begin()
        whenever(map.serialize()).thenThrow(IllegalStateException("serialization failed"))
        assertTrue(coordinator.finish(context))
        scheduler.runCurrent()
        assertOld()
        assertReleased()
        verify(navigation, never()).cacheNewMap(anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test fun `cancelling only the finish call discards draft and releases mode`() {
        begin()
        mapping.cancelCompletes = false
        val finishCall = OperationScope()
        assertTrue(coordinator.finish(context.forOperation(finishCall)))
        scheduler.runCurrent()
        finishCall.cancel()
        mapping.complete()
        scheduler.runCurrent()
        assertTrue(chat.isActive)
        assertOld()
        assertReleased()
        verify(action, never()).dumpMap()
    }

    @Test fun `action builder failure discards draft and releases services`() {
        val failing = MappingCoordinator(navigation, scope, dispatcher) { throw IllegalStateException("builder failed") }
        assertTrue(failing.start(context))
        scheduler.runCurrent()
        assertOld()
        assertReleased()
    }

    @Test fun `failed manifest commit preserves old map and locations`() {
        begin()
        failureTarget = "active.json"
        assertTrue(coordinator.finish(context))
        scheduler.runCurrent()
        assertOld()
        assertReleased()
        verify(sender).sendScopedMessage(contains("previous map and locations are unchanged"), eq(true), eq(true), same(chat))
    }

    @Test fun `cancellation during serialization prevents commit`() {
        begin()
        whenever(map.serialize()).thenAnswer { chat.cancel(); "new map" }
        assertTrue(coordinator.finish(context))
        scheduler.runCurrent()
        assertOld()
        assertReleased()
    }

    @Test fun `cache failure after commit retains new pair and resumes services`() {
        begin()
        doAnswer { it.getArgument<Runnable>(2).run(); null }.whenever(navigation).cacheNewMap(same(map), anyOrNull(), anyOrNull())
        assertTrue(coordinator.finish(context))
        scheduler.runCurrent()
        assertEquals("new map", store.active().map.readText())
        assertEquals("new location", store.locationFile(store.active(), "Kitchen").readText())
        assertReleased()
        verify(sender).sendScopedMessage(contains("ORIENTATION ERROR"), eq(true), eq(true), same(chat))
    }

    @Test fun `pending map stop times out without data loss or stuck mode`() {
        begin()
        mapping.cancelCompletes = false
        assertTrue(coordinator.finish(context))
        scheduler.advanceUntilIdle()
        assertOld()
        assertReleased()
        verify(sender).sendScopedMessage(contains("timed out"), eq(true), eq(true), same(chat))
    }

    @Test fun `missing cache callback times out after save and releases services`() {
        begin()
        doNothing().whenever(navigation).cacheNewMap(same(map), anyOrNull(), anyOrNull())
        assertTrue(coordinator.finish(context))
        scheduler.advanceUntilIdle()
        assertEquals("new map", store.active().map.readText())
        assertReleased()
        verify(sender).sendScopedMessage(contains("ORIENTATION ERROR"), eq(true), eq(true), same(chat))
    }

    @Test fun `late cache callback after cancellation cannot begin localization`() {
        begin()
        var callback: Runnable? = null
        doAnswer { callback = it.getArgument(1); null }.whenever(navigation).cacheNewMap(same(map), anyOrNull(), anyOrNull())
        assertTrue(coordinator.finish(context))
        scheduler.runCurrent()
        chat.cancel()
        callback!!.run()
        scheduler.runCurrent()
        assertEquals("new map", store.active().map.readText())
        assertReleased()
        verify(navigation, never()).ensureLocalizationIfNeeded(anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test fun `preparation timeout discards draft and releases services`() {
        val pending = ControlledFuture<Void>()
        whenever(navigation.stopCurrentLocalization()).thenReturn(pending.future)
        assertTrue(coordinator.start(context))
        assertFalse(coordinator.finish(context))
        scheduler.advanceUntilIdle()
        assertOld()
        assertReleased()
        verify(action, never()).async()
    }

    @Test fun `failure acquiring service mode cannot leave a draft behind`() {
        whenever(navigation.beginMapping(any())).thenThrow(IllegalStateException("service failed"))
        assertThrows(IllegalStateException::class.java) { coordinator.start(context) }
        assertOld()
        assertReleased()
    }

    private class ControlledFuture<T> {
        val future: Future<T> = mock()
        var cancelCompletes = false
        private var done = false
        private var cancelled = false
        private var value: T? = null
        private val callbacks = mutableListOf<Consumer<Future<T>>>()
        init {
            whenever(future.isDone).thenAnswer { done }
            whenever(future.isCancelled).thenAnswer { cancelled }
            whenever(future.value).thenAnswer { value }
            whenever(future.thenConsume(any())).thenAnswer {
                val callback = it.getArgument<Consumer<Future<T>>>(0)
                if (done) callback.consume(future) else callbacks.add(callback)
                null
            }
            doAnswer { if (cancelCompletes && !done) { cancelled = true; complete() }; null }.whenever(future).requestCancellation()
        }
        fun complete(result: T? = null) {
            value = result
            done = true
            val pending = callbacks.toList()
            callbacks.clear()
            pending.forEach { it.consume(future) }
        }
    }
}
