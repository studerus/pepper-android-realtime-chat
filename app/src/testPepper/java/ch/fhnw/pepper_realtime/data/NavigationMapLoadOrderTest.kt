package ch.fhnw.pepper_realtime.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import ch.fhnw.pepper_realtime.data.NavigationMapCache.CriticalSectionListener
import com.aldebaran.qi.Future
import com.aldebaran.qi.Promise
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.ExplorationMapBuilder
import com.aldebaran.qi.sdk.`object`.actuation.ExplorationMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.mockito.Answers
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.*
import java.nio.ByteBuffer

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationMapLoadOrderTest {
    @get:Rule val folder = TemporaryFolder()
    private val main = TestCoroutineScheduler()
    private val worker = TestCoroutineScheduler()
    private val listener = mock<CriticalSectionListener>()
    private val cache = NavigationMapCache(listener, StandardTestDispatcher(worker))
    private val qi = mock<QiContext>()
    private val context = mock<Context>()
    private val loader = mockStatic(Class.forName("com.aldebaran.qi.EmbeddedTools"))
    private val promises = mockConstruction(Promise::class.java) { promise, _ ->
        doReturn(mock<Future<Any>>()).whenever(promise).future
    }
    @Suppress("UNCHECKED_CAST")
    private fun pending(index: Int) = promises.constructed()[index] as Promise<Boolean>
    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(main))
        whenever(context.filesDir).thenReturn(folder.root)
    }
    @After fun tearDown() {
        cache.cancelPendingWork()
        Dispatchers.resetMain()
        promises.close()
        loader.close()
    }

    @Test fun `successful map waits for UI cleanup and concurrent caller joins same load`() {
        val mapFile = NavigationStore.forDirectory(folder.root).active().map
        mapFile.parentFile!!.mkdirs()
        mapFile.writeText("test map")
        val map = mock<ExplorationMap>(defaultAnswer = Answers.RETURNS_DEEP_STUBS)
        whenever(map.topGraphicalRepresentation.image.data).thenReturn(ByteBuffer.wrap(byteArrayOf(1)))
        val builder = mock<ExplorationMapBuilder>()
        whenever(builder.withMapString("test map")).thenReturn(builder)
        whenever(builder.build()).thenReturn(map)
        val bitmap = mock<Bitmap>()
        mockStatic(ExplorationMapBuilder::class.java).use { builders ->
            builders.`when`<ExplorationMapBuilder> { ExplorationMapBuilder.with(qi) }.thenReturn(builder)
            mockStatic(BitmapFactory::class.java).use { decoder ->
                decoder.`when`<Bitmap> { BitmapFactory.decodeByteArray(any(), eq(0), eq(1), any()) }.thenReturn(bitmap)
                val callback = mock<Runnable>()
                val first = cache.ensureMapLoadedIfNeeded(qi, context, callback, null)
                worker.runCurrent()
                val pending = pending(0)
                assertSame(map, cache.getCachedMap())
                assertFalse(cache.isMapLoaded())
                assertSame(first, cache.ensureMapLoadedIfNeeded(qi, context, null, null))
                verify(pending, never()).setValue(any())
                main.runCurrent()
                worker.runCurrent()
                inOrder(listener, callback, pending) {
                    verify(listener).onExit(true)
                    verify(callback).run()
                    verify(pending).setValue(true)
                }
                assertTrue(cache.isMapLoaded())
            }
        }
    }

    @Test fun `cancelled load does not post stale completion to a newer load`() {
        cache.ensureMapLoadedIfNeeded(qi, context, null, null)
        worker.runCurrent() // No saved map: failure is waiting for the UI thread.
        val old = pending(0)
        cache.cancelPendingWork()
        cache.ensureMapLoadedIfNeeded(qi, context, null, null)
        worker.runCurrent()
        main.runCurrent()
        worker.runCurrent()
        verify(old, times(1)).setValue(false)
        verify(listener, times(1)).onExit(false)
        verify(pending(1)).setValue(false)
        assertFalse(cache.isLoading())
    }
}
