package io.github.anonymous.pepper_realtime.manager;

import android.os.Handler;
import android.util.Log;

import com.aldebaran.qi.Future;
import com.aldebaran.qi.Promise;
import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.builder.LocalizeBuilder;
import com.aldebaran.qi.sdk.object.actuation.ExplorationMap;
import com.aldebaran.qi.sdk.object.actuation.Localize;
import com.aldebaran.qi.sdk.object.actuation.LocalizationStatus;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Encapsulates Pepper localization lifecycle so NavigationServiceManager stays focused on orchestration.
 */
final class LocalizationCoordinator {

    interface PhaseCallback {
        void setPhase(NavigationServiceManager.NavigationPhase phase);
    }

    interface StatusCallback {
        void updateStatus(String mapStatus, String localizationStatus);
    }

    private static final String TAG = "LocalizationCoord";

    private final Handler mainHandler;
    private final PhaseCallback phaseCallback;
    private final StatusCallback statusCallback;

    private volatile boolean localizationReady = false;
    private volatile Localize activeLocalizeAction;
    private volatile Future<Void> activeLocalizeFuture;

    LocalizationCoordinator(Handler mainHandler,
                            PhaseCallback phaseCallback,
                            StatusCallback statusCallback) {
        this.mainHandler = mainHandler;
        this.phaseCallback = phaseCallback;
        this.statusCallback = statusCallback;
    }

    boolean isLocalizationReady() {
        return localizationReady;
    }

    void markLocalizationInProgress() {
        localizationReady = false;
    }

    void reset() {
        try {
            Future<Void> future = stopCurrentLocalization();
            if (future != null && !future.isDone()) {
                try {
                    future.requestCancellation();
                } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error while resetting localization state", e);
        } finally {
            localizationReady = false;
            activeLocalizeAction = null;
            activeLocalizeFuture = null;
        }
    }

    Future<Boolean> ensureLocalizationIfNeeded(QiContext qiContext,
                                                ExplorationMap explorationMap,
                                                Runnable onLocalized,
                                                Runnable onFailed) {
        Promise<Boolean> promise = new Promise<>();
        AtomicBoolean completed = new AtomicBoolean(false);

        Runnable completeSuccess = () -> {
            if (completed.compareAndSet(false, true)) {
                try {
                    if (onLocalized != null) {
                        onLocalized.run();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "onLocalized callback error", t);
                }
                promise.setValue(true);
            }
        };
        Runnable completeFailure = () -> {
            if (completed.compareAndSet(false, true)) {
                try {
                    if (onFailed != null) {
                        onFailed.run();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "onFailed callback error", t);
                }
                promise.setValue(false);
            }
        };

        if (localizationReady) {
            Log.i(TAG, "Localization already confirmed - skipping new Localize action");
            completeSuccess.run();
            return promise.getFuture();
        }

        if (qiContext == null || explorationMap == null) {
            Log.w(TAG, "Cannot start localization - missing qiContext or exploration map");
            completeFailure.run();
            return promise.getFuture();
        }

        localizationReady = false;
        phaseCallback.setPhase(NavigationServiceManager.NavigationPhase.LOCALIZATION_MODE);

        try {
            Localize localize = LocalizeBuilder.with(qiContext).withMap(explorationMap).build();
            activeLocalizeAction = localize;

            localize.addOnStatusChangedListener(status -> {
                if (status == LocalizationStatus.LOCALIZED) {
                    localizationReady = true;
                    statusCallback.updateStatus("??? Map: Ready", "?? Localization: Localized");
                    completeSuccess.run();
                }
            });

            Future<Void> future = localize.async().run();
            activeLocalizeFuture = future;
            future.thenConsume(res -> {
                if (res.hasError()) {
                    Log.e(TAG, "Localization failed", res.getError());
                    localizationReady = false;
                    activeLocalizeAction = null;
                    activeLocalizeFuture = null;
                    statusCallback.updateStatus("??? Map: Ready", "?? Localization: Failed");
                    mainHandler.post(() -> phaseCallback.setPhase(NavigationServiceManager.NavigationPhase.NORMAL_OPERATION));
                    completeFailure.run();
                } else if (res.isSuccess()) {
                    activeLocalizeAction = null;
                    activeLocalizeFuture = null;
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start localization", t);
            mainHandler.post(() -> phaseCallback.setPhase(NavigationServiceManager.NavigationPhase.NORMAL_OPERATION));
            statusCallback.updateStatus("??? Map: Ready", "?? Localization: Failed");
            completeFailure.run();
        }

        return promise.getFuture();
    }

    Future<Void> stopCurrentLocalization() {
        Localize currentAction = activeLocalizeAction;
        Future<Void> currentFuture = activeLocalizeFuture;
        if (currentAction != null && currentFuture != null) {
            Log.i(TAG, "Cancelling active Localize action");
            localizationReady = false;
            try {
                currentFuture.requestCancellation();
            } catch (Exception e) {
                Log.w(TAG, "Error cancelling Localize future", e);
            }
            activeLocalizeAction = null;
            activeLocalizeFuture = null;
            statusCallback.updateStatus(null, "?? Localization: Stopped");
            return currentFuture;
        }
        Promise<Void> resolved = new Promise<>();
        resolved.setValue(null);
        return resolved.getFuture();
    }
}
