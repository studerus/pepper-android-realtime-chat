package ch.fhnw.pepper_realtime.data

import kotlinx.coroutines.CancellationException
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*

class NavigationStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var store: NavigationStore
    private var failingTarget: String? = null

    private fun reopen() = NavigationStore(folder.root) { source, target ->
        if (target.name == failingTarget) throw IOException("Disk full / rename denied")
        Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        Unit
    }

    @Before fun setUp() {
        File(folder.newFolder("maps"), "default_map.map").writeText("old map")
        File(folder.newFolder("locations"), "Kitchen.loc").writeText("old position")
        store = reopen()
    }

    private fun assertOldActive() {
        assertEquals("old map", reopen().active().map.readText())
        assertEquals("old position", store.locationFile(reopen().active(), "Kitchen").readText())
    }

    private fun stage(text: String = "new map"): NavigationStore.Draft {
        val draft = store.begin()
        store.writeLocation(draft.snapshot, "Office", "new position")
        store.freeze(draft)
        store.stageMap(draft, text) {}
        return draft
    }

    @Test fun `legacy data remains readable and draft saves never overwrite it`() {
        assertOldActive()
        val draft = store.begin()
        assertTrue(draft.snapshot.locations.listFiles()!!.isEmpty())
        store.writeLocation(draft.snapshot, "Kitchen", "draft position")
        assertEquals(draft.snapshot, store.visible())
        assertOldActive()
        store.abort(draft)
        assertFalse(draft.snapshot.locations.exists())
        assertEquals(store.active(), store.visible())
        assertOldActive()
    }

    @Test fun `commit publishes map and matching locations together across restart`() {
        val draft = stage()
        assertOldActive()
        store.commit(draft)
        store.abort(draft) // A delayed cleanup must not delete committed data.
        val active = reopen().active()
        assertEquals("new map", active.map.readText())
        assertEquals("new position", store.locationFile(active, "Office").readText())
        assertFalse(store.locationFile(active, "Kitchen").exists())
        assertEquals("old map", File(folder.root, "maps/default_map.map").readText())
        assertEquals("old position", File(folder.root, "locations/Kitchen.loc").readText())
    }

    @Test fun `failed manifest replacement preserves previous set and allows retry`() {
        val draft = stage()
        failingTarget = "active.json"
        assertThrows(IOException::class.java) { store.commit(draft) }
        assertOldActive()
        store.abort(draft)
        failingTarget = null
        store.commit(stage())
        assertEquals("new map", reopen().active().map.readText())
    }

    @Test fun `failed second commit leaves first committed map and destinations intact`() {
        store.commit(stage("first map"))
        val first = reopen().active()
        val draft = stage("second map")
        failingTarget = "active.json"
        assertThrows(IOException::class.java) { store.commit(draft) }
        store.abort(draft)
        assertEquals(first, reopen().active())
        assertEquals("first map", first.map.readText())
        assertEquals("new position", store.locationFile(first, "Office").readText())
    }

    @Test fun `failed map write preserves old data and removes temporary files`() {
        val draft = store.begin()
        store.freeze(draft)
        failingTarget = "default_map.map"
        assertThrows(IOException::class.java) { store.stageMap(draft, "new map") {} }
        assertFalse(draft.snapshot.map.exists())
        assertFalse(folder.root.walkTopDown().any { it.extension == "tmp" })
        assertOldActive()
    }

    @Test fun `cancellation during streamed map write cannot publish a partial map`() {
        val draft = store.begin()
        store.freeze(draft)
        var chunks = 0
        assertThrows(CancellationException::class.java) {
            store.stageMap(draft, "x".repeat(100_000)) {
                if (++chunks == 3) throw CancellationException("Chat ended")
            }
        }
        assertFalse(draft.snapshot.map.exists())
        assertFalse(folder.root.walkTopDown().any { it.extension == "tmp" })
        assertOldActive()
    }

    @Test fun `empty map cannot replace a valid map`() {
        val draft = store.begin()
        store.freeze(draft)
        assertThrows(IllegalArgumentException::class.java) { store.stageMap(draft, " \n") {} }
        assertThrows(IllegalStateException::class.java) { store.commit(draft) }
        assertOldActive()
    }

    @Test fun `frozen or superseded map rejects delayed position saves`() {
        val legacy = store.writable()
        val draft = store.begin()
        assertThrows(IllegalStateException::class.java) { store.writeLocation(legacy, "Kitchen", "wrong frame") }
        store.freeze(draft)
        assertThrows(IllegalStateException::class.java) { store.writeLocation(draft.snapshot, "Kitchen", "late position") }
        store.abort(draft)
        assertThrows(IllegalStateException::class.java) { store.writeLocation(draft.snapshot, "Kitchen", "stale position") }
        assertOldActive()
    }

    @Test fun `failed destination replacement keeps existing file`() {
        failingTarget = "Kitchen.loc"
        assertThrows(IOException::class.java) { store.writeLocation(store.writable(), "Kitchen", "replacement") }
        assertOldActive()
    }

    @Test fun `restart ignores and reclaims uncommitted draft`() {
        val draft = stage()
        store = reopen()
        assertOldActive()
        val next = store.begin()
        assertFalse(draft.snapshot.map.exists())
        assertTrue(next.snapshot.locations.isDirectory)
        assertOldActive()
    }

    @Test fun `cleanup keeps active previous and legacy data`() {
        val first = stage("one"); store.commit(first)
        val second = stage("two"); store.commit(second)
        val third = stage("three"); store.commit(third)
        store.pruneUnusedSets()
        assertFalse(first.snapshot.map.exists())
        assertEquals("two", second.snapshot.map.readText())
        assertEquals("three", reopen().active().map.readText())
        assertEquals("old map", File(folder.root, "maps/default_map.map").readText())
    }

    @Test fun `location names cannot escape their navigation set`() {
        for (name in listOf("", "../outside", "a/b", "a\\b", "a\u0000b")) {
            assertThrows(IllegalArgumentException::class.java) { store.writeLocation(store.writable(), name, "bad") }
        }
        assertOldActive()
    }
}
