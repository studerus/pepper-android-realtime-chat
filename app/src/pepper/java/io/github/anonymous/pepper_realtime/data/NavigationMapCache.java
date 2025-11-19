package io.github.anonymous.pepper_realtime.data;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.util.Log;

import com.aldebaran.qi.Future;
import com.aldebaran.qi.Promise;
import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.builder.ExplorationMapBuilder;
import com.aldebaran.qi.sdk.object.actuation.ExplorationMap;
import com.aldebaran.qi.sdk.object.actuation.MapTopGraphicalRepresentation;
import com.aldebaran.qi.sdk.object.image.EncodedImage;

import io.github.anonymous.pepper_realtime.manager.ThreadManager;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Isolates map caching and loading logic away from NavigationServiceManager.
 * Keeps loading resilient for aging hardware without relying on System.gc().
 */
public final class NavigationMapCache {

    public interface CriticalSectionListener {
        void onEnter(QiContext qiContext);
        void onExit(boolean success);
    }

    public interface LocalizationStopper {
        Future<Void> stop();
    }

    private static final String TAG = "NavigationMapCache";

    private final Handler mainHandler;
    private final CriticalSectionListener criticalSectionListener;

    private final AtomicBoolean isMapBeingBuilt = new AtomicBoolean(false);
    private volatile Promise<Boolean> inFlightMapLoadPromise;

    private volatile boolean mapLoaded;
    private final AtomicReference<ExplorationMap> cachedMap = new AtomicReference<>();
    private final AtomicReference<MapTopGraphicalRepresentation> cachedMapGfx = new AtomicReference<>();
    private final AtomicReference<Bitmap> cachedMapBitmap = new AtomicReference<>();

    public NavigationMapCache(Handler mainHandler, CriticalSectionListener criticalSectionListener) {
        this.mainHandler = mainHandler;
        this.criticalSectionListener = criticalSectionListener;
    }

    public boolean isMapLoaded() {
        return mapLoaded && cachedMap.get() != null;
    }

    public boolean isLoading() {
        return isMapBeingBuilt.get();
    }

    public boolean isMapSavedOnDisk(Context appContext) {
        try {
            File mapsDir = new File(appContext.getFilesDir(), "maps");
            File mapFile = new File(mapsDir, "default_map.map");
            return mapFile.exists() && mapFile.length() > 0;
        } catch (Throwable t) {
            Log.w(TAG, "Failed checking map on disk", t);
            return false;
        }
    }

    public Future<Boolean> ensureMapLoadedIfNeeded(
            QiContext qiContext,
            Context appContext,
            Runnable onMapLoaded,
            LocalizationStopper localizationStopper
    ) {
        Promise<Boolean> promise = new Promise<>();

        if (isMapLoaded()) {
            promise.setValue(true);
            return promise.getFuture();
        }

        if (qiContext == null || appContext == null) {
            promise.setValue(false);
            return promise.getFuture();
        }

        if (!isMapBeingBuilt.compareAndSet(false, true)) {
            Promise<Boolean> inFlight = inFlightMapLoadPromise;
            if (inFlight != null) {
                return inFlight.getFuture();
            }
            promise.setValue(false);
            return promise.getFuture();
        }

        inFlightMapLoadPromise = promise;
        criticalSectionListener.onEnter(qiContext);

        Future<Void> stopFuture = localizationStopper != null ? localizationStopper.stop() : null;
        Runnable startLoading = () -> loadMapInternal(qiContext, appContext, onMapLoaded);

        if (stopFuture == null) {
            startLoading.run();
        } else {
            stopFuture.thenConsume(res -> {
                if (res != null && res.hasError()) {
                    Log.w(TAG, "Stopping current localization raised an error before map load", res.getError());
                }
                startLoading.run();
            });
        }

        return promise.getFuture();
    }

    public Bitmap getMapBitmap() {
        return cachedMapBitmap.get();
    }

    public MapTopGraphicalRepresentation getMapTopGraphicalRepresentation() {
        return cachedMapGfx.get();
    }

    public ExplorationMap getCachedMap() {
        return cachedMap.get();
    }

    public void cacheNewMap(ExplorationMap newMap, Runnable onCached) {
        if (newMap == null) {
            Log.w(TAG, "Attempted to cache a null map.");
            return;
        }

        ThreadManager.getInstance().executeIO(() -> {
            boolean success = cacheMapGraphics(newMap);
            if (success) {
                cachedMap.set(newMap);
                mapLoaded = true;
                if (onCached != null) {
                    try {
                        onCached.run();
                    } catch (Exception e) {
                        Log.e(TAG, "Callback after caching failed", e);
                    }
                }
            }
        });
    }

    public void cacheNewMap(ExplorationMap newMap) {
        cacheNewMap(newMap, null);
    }

    public void reset() {
        recycleBitmap();
        cachedMapGfx.set(null);
        cachedMap.set(null);
        mapLoaded = false;
    }

    private void loadMapInternal(QiContext qiContext, Context appContext, Runnable onMapLoaded) {
        reset();

        ThreadManager.getInstance().executeIO(() -> {
            final String mapData = loadMapFileToString(appContext);
            if (mapData == null) {
                Log.e(TAG, "Map data could not be loaded from file.");
                finishLoad(false, null);
                return;
            }

            ThreadManager.getInstance().executeNetwork(() -> {
                try {
                    final ExplorationMap map = ExplorationMapBuilder
                            .with(qiContext)
                            .withMapString(mapData)
                            .build();
                    ThreadManager.getInstance().executeIO(() -> {
                        boolean success = cacheMapGraphics(map);
                        Runnable mainThreadWork = null;
                        if (success) {
                            cachedMap.set(map);
                            mapLoaded = true;
                            mainThreadWork = onMapLoaded;
                        }
                        finishLoad(success, mainThreadWork);
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Failed to build ExplorationMap", e);
                    finishLoad(false, null);
                }
            });
        });
    }

    private void finishLoad(boolean success, Runnable mainThreadWork) {
        Promise<Boolean> promise = inFlightMapLoadPromise;
        inFlightMapLoadPromise = null;
        isMapBeingBuilt.set(false);

        if (promise != null) {
            try {
                promise.setValue(success);
            } catch (Exception e) {
                Log.w(TAG, "Failed to resolve map load promise", e);
            }
        }

        mainHandler.post(() -> {
            if (mainThreadWork != null) {
                try {
                    mainThreadWork.run();
                } catch (Throwable t) {
                    Log.w(TAG, "onMapLoaded callback error", t);
                }
            }
            criticalSectionListener.onExit(success);
        });
    }

    private boolean cacheMapGraphics(ExplorationMap map) {
        try {
            recycleBitmap();
            cachedMapGfx.set(null);

            Log.i(TAG, "Extracting graphical representation from ExplorationMap");
            MapTopGraphicalRepresentation mapGfx = map.getTopGraphicalRepresentation();
            EncodedImage encodedImage = mapGfx.getImage();
            ByteBuffer buffer = encodedImage.getData();
            byte[] bitmapBytes = new byte[buffer.remaining()];
            buffer.get(bitmapBytes);

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inDither = true;
            options.inMutable = false;

            Bitmap bitmap = BitmapFactory.decodeByteArray(bitmapBytes, 0, bitmapBytes.length, options);
            if (bitmap == null) {
                Log.e(TAG, "Bitmap decoding returned null");
                return false;
            }

            cachedMapBitmap.set(bitmap);
            cachedMapGfx.set(mapGfx);
            trimTemporaryBytes(bitmapBytes);
            Log.i(TAG, "Successfully decoded and cached map bitmap (" + bitmap.getWidth() + "x" + bitmap.getHeight() + ")");
            return true;
        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "Out of memory while decoding map bitmap", oom);
            recycleBitmap();
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract graphical representation from map", e);
            recycleBitmap();
            return false;
        }
    }

    private void recycleBitmap() {
        Bitmap previous = cachedMapBitmap.getAndSet(null);
        if (previous != null && !previous.isRecycled()) {
            previous.recycle();
        }
    }

    private void trimTemporaryBytes(byte[] bitmapBytes) {
        if (bitmapBytes == null) {
            return;
        }
        // Allow GC to reclaim the array sooner on constrained hardware.
        Arrays.fill(bitmapBytes, (byte) 0);
    }

    private String loadMapFileToString(Context appContext) {
        try {
            File mapsDir = new File(appContext.getFilesDir(), "maps");
            File mapFile = new File(mapsDir, "default_map.map");
            if (!mapFile.exists() || mapFile.length() <= 0) {
                Log.w(TAG, "No map file on disk: " + mapFile.getAbsolutePath());
                return null;
            }

            try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(mapFile));
                 ByteArrayOutputStream baos = new ByteArrayOutputStream((int) mapFile.length())) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = bis.read(buffer)) != -1) {
                    baos.write(buffer, 0, read);
                }
                return baos.toString("UTF-8");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to read map file", e);
            return null;
        }
    }
}
