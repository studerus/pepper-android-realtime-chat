package ch.fhnw.pepper_realtime.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides a cached, thread-safe list of saved locations from disk.
 */
@Singleton
class LocationProvider @Inject constructor() {

    companion object {
        private const val TAG = "LocationProvider"
    }

    @Volatile
    private var cachedLocations: List<SavedLocation> = emptyList()

    fun getSavedLocations(): List<SavedLocation> {
        return cachedLocations.toList() // Return a copy for safety
    }

    /**
     * Refreshes the location cache from disk on a background thread.
     */
    fun refreshLocations(context: Context?) {
        Thread({
            val locations = mutableListOf<SavedLocation>()
            if (context == null) {
                Log.w(TAG, "Context is null, cannot refresh locations.")
                return@Thread
            }

            try {
                val locationsDir = File(context.filesDir, "locations")
                if (!locationsDir.exists() || !locationsDir.isDirectory) {
                    Log.d(TAG, "Locations directory does not exist.")
                    synchronized(this) {
                        cachedLocations = locations
                    }
                    return@Thread
                }

                val locationFiles = locationsDir.listFiles { _, name -> name.endsWith(".loc") }
                locationFiles?.forEach { file ->
                    loadLocationFromFile(file)?.let { locations.add(it) }
                }

                locations.sortWith { l1, l2 -> l1.name.compareTo(l2.name, ignoreCase = true) }
                synchronized(this) {
                    cachedLocations = locations
                }
                Log.i(TAG, "Refreshed and found ${cachedLocations.size} locations.")
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing locations", e)
            }
        }, "location-provider-thread").start()
    }

    private fun loadLocationFromFile(locationFile: File): SavedLocation? {
        return try {
            BufferedReader(
                InputStreamReader(FileInputStream(locationFile), StandardCharsets.UTF_8)
            ).use { reader ->
                val content = reader.readText()

                val data = JSONObject(content)
                SavedLocation(
                    name = data.optString("name", locationFile.name.replace(".loc", "")),
                    description = data.optString("description", ""),
                    timestamp = data.optLong("timestamp", 0),
                    highPrecision = data.optBoolean("high_precision", false),
                    translation = data.getJSONArray("translation").let { arr ->
                        doubleArrayOf(arr.getDouble(0), arr.getDouble(1), arr.getDouble(2))
                    },
                    rotation = data.getJSONArray("rotation").let { arr ->
                        doubleArrayOf(arr.getDouble(0), arr.getDouble(1), arr.getDouble(2), arr.getDouble(3))
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load location from ${locationFile.name}", e)
            null
        }
    }
}

