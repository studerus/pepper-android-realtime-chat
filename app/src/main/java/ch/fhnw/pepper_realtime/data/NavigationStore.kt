package ch.fhnw.pepper_realtime.data

import android.system.Os
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Publishes a map and its locations together by atomically replacing one small manifest. */
class NavigationStore internal constructor(
    private val filesDir: File,
    private val replaceFile: (File, File) -> Unit = { source, target -> Os.rename(source.path, target.path) }
) {
    data class Snapshot(val id: String?, val map: File, val locations: File)
    class Draft internal constructor(val snapshot: Snapshot, internal val baseId: String?) {
        internal var finishing = false
    }

    companion object {
        private val stores = mutableMapOf<String, NavigationStore>()
        @Synchronized fun forDirectory(filesDir: File): NavigationStore =
            stores.getOrPut(filesDir.canonicalPath) { NavigationStore(filesDir) }
    }

    private val root = File(filesDir, "navigation_sets")
    private val manifest = File(root, "active.json")
    private var draft: Draft? = null

    @Synchronized fun active(): Snapshot {
        if (!manifest.exists()) return Snapshot(null, File(filesDir, "maps/default_map.map"), File(filesDir, "locations"))
        val id = JSONObject(manifest.readText(Charsets.UTF_8)).getString("current")
        val snapshot = snapshot(id)
        check(snapshot.map.isFile && snapshot.map.length() > 0 && snapshot.locations.isDirectory) {
            "The active navigation set is incomplete"
        }
        return snapshot
    }

    @Synchronized fun visible(): Snapshot = draft?.snapshot ?: active()
    @Synchronized fun isCurrentVisible(snapshot: Snapshot): Boolean = visible().id == snapshot.id
    @Synchronized fun ifVisible(snapshot: Snapshot, action: () -> Unit) {
        if (isCurrentVisible(snapshot)) action()
    }
    @Synchronized fun isMapping(): Boolean = draft != null

    @Synchronized fun begin(): Draft {
        check(draft == null) { "Mapping is already in progress" }
        pruneUnusedSets()
        val base = active()
        val candidate = snapshot(UUID.randomUUID().toString())
        check(candidate.locations.mkdirs()) { "Could not create the map draft" }
        return Draft(candidate, base.id).also { draft = it }
    }

    @Synchronized fun freeze(candidate: Draft) {
        requireDraft(candidate)
        check(!candidate.finishing) { "Map finalization is already in progress" }
        candidate.finishing = true
    }

    @Synchronized fun writable(): Snapshot {
        check(draft?.finishing != true) { "Locations cannot be saved while the map is being finalized" }
        return visible()
    }

    @Synchronized fun writeLocation(expected: Snapshot, name: String, data: String) {
        check(writable().id == expected.id) { "The map changed while reading the robot position; please retry" }
        val target = locationFile(expected, name)
        atomicWrite(target, data)
    }

    fun locationFile(snapshot: Snapshot, name: String): File {
        require(name.isNotBlank() && '/' !in name && '\\' !in name && '\u0000' !in name) { "Invalid location name" }
        val file = File(snapshot.locations, "$name.loc")
        require(file.canonicalFile.parentFile == snapshot.locations.canonicalFile) { "Invalid location path" }
        return file
    }

    @Synchronized fun stageMap(candidate: Draft, serialized: String, checkpoint: () -> Unit) {
        requireDraft(candidate)
        check(candidate.finishing) { "Map is not being finalized" }
        require(serialized.isNotBlank()) { "The robot returned an empty map" }
        atomicWrite(candidate.snapshot.map, serialized, checkpoint)
    }

    /** Short commit point. Call while holding the operation's cancellation guard. */
    @Synchronized fun commit(candidate: Draft): Snapshot {
        requireDraft(candidate)
        check(candidate.finishing && candidate.snapshot.map.isFile && candidate.snapshot.map.length() > 0) { "No complete map to save" }
        check(active().id == candidate.baseId) { "The active map changed during mapping" }
        val data = JSONObject().put("current", candidate.snapshot.id)
        candidate.baseId?.let { data.put("previous", it) }
        atomicWrite(manifest, data.toString())
        draft = null
        return candidate.snapshot
    }

    @Synchronized fun abort(candidate: Draft) {
        if (draft !== candidate) return // In particular, never delete a committed set.
        draft = null
        candidate.snapshot.map.parentFile?.deleteRecursively()
    }

    /** Retain the active set and one previous set; legacy files remain available as a backup. */
    @Synchronized fun pruneUnusedSets() {
        val keep = mutableSetOf(active().id, draft?.snapshot?.id)
        if (manifest.exists()) {
            val previous = JSONObject(manifest.readText()).optString("previous")
            if (previous.isNotEmpty()) keep.add(previous)
        }
        root.listFiles()?.filter { it.isDirectory && isSetId(it.name) && it.name !in keep }
            ?.forEach { it.deleteRecursively() }
    }

    private fun requireDraft(candidate: Draft) = check(draft === candidate) { "Mapping was cancelled or replaced" }
    private fun isSetId(id: String) = id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
    private fun snapshot(id: String): Snapshot {
        require(isSetId(id)) { "Invalid navigation set ID" }
        val directory = File(root, id)
        return Snapshot(id, File(directory, "default_map.map"), File(directory, "locations"))
    }

    private fun atomicWrite(target: File, text: String, checkpoint: () -> Unit = {}) {
        val parent = target.parentFile ?: throw IOException("Missing storage directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Could not create $parent")
        val temporary = File.createTempFile(".pending-", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { output ->
                // Avoid a second map-sized byte array on Pepper's constrained heap.
                val writer = output.writer(Charsets.UTF_8)
                for (offset in text.indices step 8192) {
                    checkpoint()
                    writer.write(text, offset, minOf(8192, text.length - offset))
                }
                writer.flush()
                output.fd.sync()
            }
            checkpoint()
            replaceFile(temporary, target) // Same filesystem; failure leaves the old file intact.
        } finally {
            temporary.delete()
        }
    }
}
