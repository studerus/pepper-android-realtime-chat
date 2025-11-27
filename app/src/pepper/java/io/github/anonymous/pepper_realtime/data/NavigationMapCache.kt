package io.github.anonymous.pepper_realtime.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.Promise
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.ExplorationMapBuilder
import com.aldebaran.qi.sdk.`object`.actuation.ExplorationMap
import com.aldebaran.qi.sdk.`object`.actuation.MapTopGraphicalRepresentation
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Isolates map caching and loading logic away from NavigationServiceManager.
 * Keeps loading resilient for aging hardware without relying on System.gc().
 */
class NavigationMapCache(
    private val mainHandler: Handler,
    private val criticalSectionListener: CriticalSectionListener
) {

    interface CriticalSectionListener {
        fun onEnter(qiContext: QiContext)
        fun onExit(success: Boolean)
    }

    fun interface LocalizationStopper {
        fun stop(): Future<Void>?
    }

    companion object {
        private const val TAG = "NavigationMapCache"
    }

    private val isMapBeingBuilt = AtomicBoolean(false)
    @Volatile private var inFlightMapLoadPromise: Promise<Boolean>? = null

    @Volatile private var mapLoadedFlag: Boolean = false
    
    private val cachedMap = AtomicReference<ExplorationMap>()
    private val cachedMapGfx = AtomicReference<MapTopGraphicalRepresentation>()
    private val cachedMapBitmap = AtomicReference<Bitmap>()
    
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isMapLoaded(): Boolean = mapLoadedFlag && cachedMap.get() != null

    fun isLoading(): Boolean = isMapBeingBuilt.get()

    fun isMapSavedOnDisk(appContext: Context): Boolean {
        return try {
            val mapsDir = File(appContext.filesDir, "maps")
            val mapFile = File(mapsDir, "default_map.map")
            mapFile.exists() && mapFile.length() > 0
        } catch (t: Throwable) {
            Log.w(TAG, "Failed checking map on disk", t)
            false
        }
    }

    fun ensureMapLoadedIfNeeded(
        qiContext: QiContext?,
        appContext: Context?,
        onMapLoaded: Runnable?,
        localizationStopper: LocalizationStopper?
    ): Future<Boolean> {
        val promise = Promise<Boolean>()

        if (isMapLoaded()) {
            promise.setValue(true)
            return promise.future
        }

        if (qiContext == null || appContext == null) {
            promise.setValue(false)
            return promise.future
        }

        if (!isMapBeingBuilt.compareAndSet(false, true)) {
            inFlightMapLoadPromise?.let { return it.future }
            promise.setValue(false)
            return promise.future
        }

        inFlightMapLoadPromise = promise
        criticalSectionListener.onEnter(qiContext)

        val stopFuture = localizationStopper?.stop()
        val startLoading = Runnable { loadMapInternal(qiContext, appContext, onMapLoaded) }

        if (stopFuture == null) {
            startLoading.run()
        } else {
            stopFuture.thenConsume { res ->
                if (res?.hasError() == true) {
                    Log.w(TAG, "Stopping current localization raised an error before map load", res.error)
                }
                startLoading.run()
            }
        }

        return promise.future
    }

    fun getMapBitmap(): Bitmap? = cachedMapBitmap.get()

    fun getMapTopGraphicalRepresentation(): MapTopGraphicalRepresentation? = cachedMapGfx.get()

    fun getCachedMap(): ExplorationMap? = cachedMap.get()

    fun cacheNewMap(newMap: ExplorationMap?, onCached: Runnable? = null) {
        if (newMap == null) {
            Log.w(TAG, "Attempted to cache a null map.")
            return
        }

        cacheScope.launch {
            val success = cacheMapGraphics(newMap)
            if (success) {
                cachedMap.set(newMap)
                mapLoadedFlag = true
                onCached?.let {
                    try {
                        it.run()
                    } catch (e: Exception) {
                        Log.e(TAG, "Callback after caching failed", e)
                    }
                }
            }
        }
    }

    fun reset() {
        recycleBitmap()
        cachedMapGfx.set(null)
        cachedMap.set(null)
        mapLoadedFlag = false
    }

    private fun loadMapInternal(qiContext: QiContext, appContext: Context, onMapLoaded: Runnable?) {
        reset()

        cacheScope.launch {
            val mapData = loadMapFileToString(appContext)
            if (mapData == null) {
                Log.e(TAG, "Map data could not be loaded from file.")
                finishLoad(false, null)
                return@launch
            }

            try {
                val map = ExplorationMapBuilder
                    .with(qiContext)
                    .withMapString(mapData)
                    .build()
                
                val success = cacheMapGraphics(map)
                val mainThreadWork = if (success) {
                    cachedMap.set(map)
                    mapLoadedFlag = true
                    onMapLoaded
                } else {
                    null
                }
                finishLoad(success, mainThreadWork)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to build ExplorationMap", e)
                finishLoad(false, null)
            }
        }
    }

    private fun finishLoad(success: Boolean, mainThreadWork: Runnable?) {
        val promise = inFlightMapLoadPromise
        inFlightMapLoadPromise = null
        isMapBeingBuilt.set(false)

        if (promise != null) {
            try {
                promise.setValue(success)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve map load promise", e)
            }
        }

        mainHandler.post {
            mainThreadWork?.let {
                try {
                    it.run()
                } catch (t: Throwable) {
                    Log.w(TAG, "onMapLoaded callback error", t)
                }
            }
            criticalSectionListener.onExit(success)
        }
    }

    private fun cacheMapGraphics(map: ExplorationMap): Boolean {
        return try {
            recycleBitmap()
            cachedMapGfx.set(null)

            Log.i(TAG, "Extracting graphical representation from ExplorationMap")
            val mapGfx = map.topGraphicalRepresentation
            val encodedImage = mapGfx.image
            val buffer = encodedImage.data
            val bitmapBytes = ByteArray(buffer.remaining())
            buffer.get(bitmapBytes)

            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                @Suppress("DEPRECATION")
                inDither = true
                inMutable = false
            }

            val bitmap = BitmapFactory.decodeByteArray(bitmapBytes, 0, bitmapBytes.size, options)
            if (bitmap == null) {
                Log.e(TAG, "Bitmap decoding returned null")
                return false
            }

            cachedMapBitmap.set(bitmap)
            cachedMapGfx.set(mapGfx)
            trimTemporaryBytes(bitmapBytes)
            Log.i(TAG, "Successfully decoded and cached map bitmap (${bitmap.width}x${bitmap.height})")
            true
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "Out of memory while decoding map bitmap", oom)
            recycleBitmap()
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract graphical representation from map", e)
            recycleBitmap()
            false
        }
    }

    private fun recycleBitmap() {
        cachedMapBitmap.getAndSet(null)?.let { previous ->
            if (!previous.isRecycled) {
                previous.recycle()
            }
        }
    }

    private fun trimTemporaryBytes(bitmapBytes: ByteArray?) {
        // Allow GC to reclaim the array sooner on constrained hardware.
        bitmapBytes?.let { Arrays.fill(it, 0.toByte()) }
    }

    private fun loadMapFileToString(appContext: Context): String? {
        return try {
            val mapsDir = File(appContext.filesDir, "maps")
            val mapFile = File(mapsDir, "default_map.map")
            if (!mapFile.exists() || mapFile.length() <= 0) {
                Log.w(TAG, "No map file on disk: ${mapFile.absolutePath}")
                return null
            }

            BufferedInputStream(FileInputStream(mapFile)).use { bis ->
                ByteArrayOutputStream(mapFile.length().toInt()).use { baos ->
                    val buffer = ByteArray(16 * 1024)
                    var read: Int
                    while (bis.read(buffer).also { read = it } != -1) {
                        baos.write(buffer, 0, read)
                    }
                    baos.toString("UTF-8")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read map file", e)
            null
        }
    }
}

