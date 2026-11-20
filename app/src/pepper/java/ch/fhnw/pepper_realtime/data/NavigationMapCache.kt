package ch.fhnw.pepper_realtime.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.FileInputStream
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Isolates map caching and loading logic away from NavigationServiceManager.
 * Keeps loading resilient for aging hardware without relying on System.gc().
 */
class NavigationMapCache(
    private val criticalSectionListener: CriticalSectionListener,
    workerDispatcher: CoroutineDispatcher = Dispatchers.IO
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

    @Volatile private var workGeneration = 0L
    private val isMapBeingBuilt = AtomicBoolean(false)
    @Volatile private var inFlightMapLoadPromise: Promise<Boolean>? = null

    @Volatile private var mapLoadedFlag: Boolean = false
    
    private val cachedMap = AtomicReference<ExplorationMap>()
    private val cachedMapGfx = AtomicReference<MapTopGraphicalRepresentation>()
    private val cachedMapBitmap = AtomicReference<Bitmap>()
    
    private val cacheScope = CoroutineScope(SupervisorJob() + workerDispatcher)

    fun isMapLoaded(): Boolean = !isLoading() && mapLoadedFlag && cachedMap.get() != null

    fun isLoading(): Boolean = isMapBeingBuilt.get()

    fun isMapSavedOnDisk(appContext: Context): Boolean {
        return try {
            val mapFile = NavigationStore.forDirectory(appContext.filesDir).active().map
            mapFile.exists() && mapFile.length() > 0
        } catch (t: Throwable) {
            Log.w(TAG, "Failed checking map on disk", t)
            false
        }
    }

    @Synchronized
    fun ensureMapLoadedIfNeeded(
        qiContext: QiContext?,
        appContext: Context?,
        onMapLoaded: Runnable?,
        localizationStopper: LocalizationStopper?
    ): Future<Boolean> {
        inFlightMapLoadPromise?.let { return it.future }
        val promise = Promise<Boolean>()

        if (isMapLoaded()) {
            promise.setValue(true)
            return promise.future
        }

        if (qiContext == null || appContext == null) {
            promise.setValue(false)
            return promise.future
        }

        isMapBeingBuilt.set(true)

        val generation = workGeneration
        inFlightMapLoadPromise = promise
        promise.setOnCancel { synchronized(this) { if (inFlightMapLoadPromise === promise) cancelPendingWork() } }
        criticalSectionListener.onEnter(qiContext)

        val stopFuture = localizationStopper?.stop()
        val startLoading = Runnable {
            if (generation == workGeneration) loadMapInternal(qiContext, appContext, onMapLoaded, generation)
        }

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

    fun cacheNewMap(newMap: ExplorationMap?, onCached: Runnable? = null, onFailed: Runnable? = null) {
        if (newMap == null) {
            Log.w(TAG, "Attempted to cache a null map.")
            onFailed?.run()
            return
        }

        val generation = workGeneration
        cacheScope.launch {
            if (generation != workGeneration) return@launch
            val graphics = prepareMapGraphics(newMap)
            if (installMap(newMap, graphics, generation)) {
                onCached?.let {
                    try {
                        it.run()
                    } catch (e: Exception) {
                        Log.e(TAG, "Callback after caching failed", e)
                    }
                }
            } else if (generation == workGeneration) onFailed?.run()
        }
    }

    @Synchronized
    fun cancelPendingWork() {
        workGeneration++
        cacheScope.coroutineContext.cancelChildren()
        val pending = inFlightMapLoadPromise
        inFlightMapLoadPromise = null
        isMapBeingBuilt.set(false)
        pending?.setValue(false)
    }

    fun reset() {
        recycleBitmap()
        cachedMapGfx.set(null)
        cachedMap.set(null)
        mapLoadedFlag = false
    }

    private fun loadMapInternal(qiContext: QiContext, appContext: Context, onMapLoaded: Runnable?, generation: Long) {
        if (generation != workGeneration) return
        reset()

        cacheScope.launch {
            val mapData = loadMapFileToString(appContext)
            if (mapData == null) {
                Log.e(TAG, "Map data could not be loaded from file.")
                finishLoad(false, null, generation)
                return@launch
            }

            try {
                val map = ExplorationMapBuilder
                    .with(qiContext)
                    .withMapString(mapData)
                    .build()
                
                ensureActive()
                if (generation != workGeneration) return@launch
                val graphics = prepareMapGraphics(map)
                val success = installMap(map, graphics, generation)
                val mainThreadWork = if (success) onMapLoaded else null
                finishLoad(success, mainThreadWork, generation)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to build ExplorationMap", e)
                finishLoad(false, null, generation)
            }
        }
    }

    private suspend fun finishLoad(success: Boolean, mainThreadWork: Runnable?, generation: Long) {
        // A promise continuation can start localization immediately. Leave the map-load
        // phase on the UI thread first, then release waiters back on the worker.
        withContext(Dispatchers.Main.immediate) {
            if (generation != workGeneration) return@withContext
            criticalSectionListener.onExit(success)
            if (generation != workGeneration) return@withContext
            try {
                mainThreadWork?.run()
            } catch (t: Throwable) {
                Log.w(TAG, "onMapLoaded callback error", t)
            }
        }
        val promise = synchronized(this) {
            if (generation != workGeneration) return
            val pending = inFlightMapLoadPromise
            inFlightMapLoadPromise = null
            isMapBeingBuilt.set(false)
            pending
        }
        promise?.setValue(success)
    }

    private data class PreparedGraphics(val bitmap: Bitmap, val representation: MapTopGraphicalRepresentation)

    @Synchronized
    private fun installMap(map: ExplorationMap, graphics: PreparedGraphics?, generation: Long): Boolean {
        if (graphics == null) return false
        if (generation != workGeneration) {
            graphics.bitmap.recycle()
            return false
        }
        recycleBitmap()
        cachedMapBitmap.set(graphics.bitmap)
        cachedMapGfx.set(graphics.representation)
        cachedMap.set(map)
        mapLoadedFlag = true
        return true
    }

    private fun prepareMapGraphics(map: ExplorationMap): PreparedGraphics? {
        return try {
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
                return null
            }

            trimTemporaryBytes(bitmapBytes)
            Log.i(TAG, "Successfully decoded and cached map bitmap (${bitmap.width}x${bitmap.height})")
            PreparedGraphics(bitmap, mapGfx)
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "Out of memory while decoding map bitmap", oom)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract graphical representation from map", e)
            null
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
            val mapFile = NavigationStore.forDirectory(appContext.filesDir).active().map
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

