package ch.fhnw.pepper_realtime.tools.navigation

import android.content.Context
import ch.fhnw.pepper_realtime.data.LocationProvider
import ch.fhnw.pepper_realtime.data.NavigationStore
import ch.fhnw.pepper_realtime.manager.MappingCoordinator
import ch.fhnw.pepper_realtime.manager.NavigationServiceManager
import ch.fhnw.pepper_realtime.tools.ToolContext
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.actuation.Frame
import com.aldebaran.qi.sdk.`object`.geometry.Quaternion
import com.aldebaran.qi.sdk.`object`.geometry.Transform
import com.aldebaran.qi.sdk.`object`.geometry.TransformTime
import com.aldebaran.qi.sdk.`object`.geometry.Vector3
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.mockito.Answers.RETURNS_DEEP_STUBS
import org.mockito.kotlin.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*

class SaveCurrentLocationToolTest {
    @get:Rule val folder = TemporaryFolder()
    private val context = mock<ToolContext>()
    private val app = mock<Context>()
    private val navigation = mock<NavigationServiceManager>()
    private val locations = mock<LocationProvider>()
    private val qi = mock<QiContext>(defaultAnswer = RETURNS_DEEP_STUBS)
    private val robotFrame = mock<Frame>()
    private val mapFrame = mock<Frame>()
    private val tool = SaveCurrentLocationTool()
    private val args = JSONObject().put("location_name", "Kitchen")
    private lateinit var savedFile: File
    private lateinit var store: NavigationStore
    private val mapping = mock<MappingCoordinator>()

    @Before fun setUp() {
        store = NavigationStore(folder.root) { source, target ->
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
        whenever(context.navigationStore).thenReturn(store)
        whenever(navigation.mappingCoordinator).thenReturn(mapping)
        whenever(context.qiContext).thenReturn(qi)
        whenever(context.navigationServiceManager).thenReturn(navigation)
        whenever(context.appContext).thenReturn(app)
        whenever(context.locationProvider).thenReturn(locations)
        whenever(app.filesDir).thenReturn(folder.root)
        whenever(qi.actuation.robotFrame()).thenReturn(robotFrame)
        whenever(qi.mapping.mapFrame()).thenReturn(mapFrame)
        savedFile = File(folder.newFolder("locations"), "Kitchen.loc").apply { writeText("original location") }
    }

    @Test fun `unlocalized save leaves existing location untouched`() {
        assertSaveRejected()
        verifyNoInteractions(robotFrame, locations)
    }

    @Test fun `missing map frame never becomes an identity position`() {
        whenever(navigation.isLocalizationReady()).thenReturn(true)
        whenever(qi.mapping.mapFrame()).thenThrow(IllegalStateException("No map"))
        assertSaveRejected()
    }

    @Test fun `failed transform never becomes an identity position`() {
        whenever(navigation.isLocalizationReady()).thenReturn(true)
        whenever(robotFrame.computeTransform(mapFrame)).thenThrow(IllegalStateException("Cannot localize"))
        assertSaveRejected()
    }

    @Test fun `missing transform never writes defaults`() {
        whenever(navigation.isLocalizationReady()).thenReturn(true)
        assertSaveRejected()
    }

    @Test fun `invalid coordinates and orientation leave old location untouched`() {
        whenever(navigation.isLocalizationReady()).thenReturn(true)
        for (transform in listOf(
            transform(Double.NaN), transform(Double.POSITIVE_INFINITY),
            Transform(Quaternion(0.0, 0.0, 0.0, 0.0), Vector3(1.0, 2.0, 0.0))
        )) {
            whenever(robotFrame.computeTransform(mapFrame)).thenReturn(TransformTime(transform, 0L))
            assertSaveRejected()
        }
    }

    @Test fun `localized position is saved relative to the actual map frame`() {
        whenever(navigation.isLocalizationReady()).thenReturn(true)
        whenever(robotFrame.computeTransform(mapFrame)).thenReturn(TransformTime(transform(2.5), 0L))
        assertFalse(JSONObject(tool.execute(args, context)).has("error"))
        val saved = JSONObject(savedFile.readText())
        assertEquals(2.5, saved.getJSONArray("translation").getDouble(0), 0.0)
        assertEquals(1.0, saved.getJSONArray("rotation").getDouble(3), 0.0)
        verify(locations).refreshLocations(app)
    }

    @Test fun `a real localized map origin remains a valid position`() {
        whenever(navigation.isLocalizationReady()).thenReturn(true)
        whenever(robotFrame.computeTransform(mapFrame)).thenReturn(TransformTime(
            Transform(Quaternion(0.0, 0.0, 0.0, 1.0), Vector3(0.0, 0.0, 0.0)), 0L))
        assertFalse(JSONObject(tool.execute(args, context)).has("error"))
        assertEquals(0.0, JSONObject(savedFile.readText()).getJSONArray("translation").getDouble(0), 0.0)
    }

    @Test fun `active localized mapping supports saving before normal localization`() {
        val draft = store.begin()
        whenever(mapping.isLocalized).thenReturn(true)
        whenever(robotFrame.computeTransform(mapFrame)).thenReturn(TransformTime(transform(3.0), 0L))
        assertFalse(JSONObject(tool.execute(args, context)).has("error"))
        assertEquals(3.0, JSONObject(store.locationFile(draft.snapshot, "Kitchen").readText()).getJSONArray("translation").getDouble(0), 0.0)
        assertEquals("original location", savedFile.readText())
    }

    @Test fun `scanning or finished mapping cannot authorize a save`() {
        whenever(navigation.isLocalizationReady()).thenReturn(true)
        val draft = store.begin()
        assertSaveRejected() // An old normal-localization flag must not override current scanning.
        whenever(navigation.isLocalizationReady()).thenReturn(false)
        whenever(mapping.isLocalized).thenReturn(true)
        store.freeze(draft)
        assertSaveRejected()
    }

    private fun assertSaveRejected() {
        assertTrue(JSONObject(tool.execute(args, context)).has("error"))
        assertEquals("original location", savedFile.readText())
        verify(locations, never()).refreshLocations(any())
    }

    private fun transform(x: Double) = Transform(Quaternion(0.0, 0.0, 0.0, 1.0), Vector3(x, -1.0, 0.0))

}
