package io.github.anonymous.pepper_realtime.manager

import android.os.Handler
import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.Promise
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.LocalizeBuilder
import com.aldebaran.qi.sdk.`object`.actuation.ExplorationMap
import com.aldebaran.qi.sdk.`object`.actuation.Localize
import com.aldebaran.qi.sdk.`object`.actuation.LocalizationStatus
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Encapsulates Pepper localization lifecycle so NavigationServiceManager stays focused on orchestration.
 */
internal class LocalizationCoordinator(
    private val mainHandler: Handler,
    private val phaseCallback: PhaseCallback,
    private val statusCallback: StatusCallback
) {

    fun interface PhaseCallback {
        fun setPhase(phase: NavigationServiceManager.NavigationPhase)
    }

    fun interface StatusCallback {
        fun updateStatus(mapStatus: String?, localizationStatus: String?)
    }

    companion object {
        private const val TAG = "LocalizationCoord"
    }

    @Volatile var isLocalizationReady: Boolean = false
        private set
    @Volatile private var activeLocalizeAction: Localize? = null
    @Volatile private var activeLocalizeFuture: Future<Void>? = null

    fun markLocalizationInProgress() {
        isLocalizationReady = false
    }

    fun reset() {
        try {
            val future = stopCurrentLocalization()
            if (future != null && !future.isDone) {
                try {
                    future.requestCancellation()
                } catch (ignored: Exception) {
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error while resetting localization state", e)
        } finally {
            isLocalizationReady = false
            activeLocalizeAction = null
            activeLocalizeFuture = null
        }
    }

    fun ensureLocalizationIfNeeded(
        qiContext: QiContext?,
        explorationMap: ExplorationMap?,
        onLocalized: Runnable?,
        onFailed: Runnable?
    ): Future<Boolean> {
        val promise = Promise<Boolean>()
        val completed = AtomicBoolean(false)

        val completeSuccess = Runnable {
            if (completed.compareAndSet(false, true)) {
                try {
                    onLocalized?.run()
                } catch (t: Throwable) {
                    Log.w(TAG, "onLocalized callback error", t)
                }
                promise.setValue(true)
            }
        }
        val completeFailure = Runnable {
            if (completed.compareAndSet(false, true)) {
                try {
                    onFailed?.run()
                } catch (t: Throwable) {
                    Log.w(TAG, "onFailed callback error", t)
                }
                promise.setValue(false)
            }
        }

        if (isLocalizationReady) {
            Log.i(TAG, "Localization already confirmed - skipping new Localize action")
            completeSuccess.run()
            return promise.future
        }

        if (qiContext == null || explorationMap == null) {
            Log.w(TAG, "Cannot start localization - missing qiContext or exploration map")
            completeFailure.run()
            return promise.future
        }

        isLocalizationReady = false
        phaseCallback.setPhase(NavigationServiceManager.NavigationPhase.LOCALIZATION_MODE)

        try {
            val localize = LocalizeBuilder.with(qiContext).withMap(explorationMap).build()
            activeLocalizeAction = localize

            localize.addOnStatusChangedListener { status ->
                if (status == LocalizationStatus.LOCALIZED) {
                    isLocalizationReady = true
                    statusCallback.updateStatus("✅ Map: Ready", "📍 Localization: Localized")
                    completeSuccess.run()
                }
            }

            val future = localize.async().run()
            activeLocalizeFuture = future
            future.thenConsume { res ->
                when {
                    res.hasError() -> {
                        Log.e(TAG, "Localization failed", res.error)
                        isLocalizationReady = false
                        activeLocalizeAction = null
                        activeLocalizeFuture = null
                        statusCallback.updateStatus("✅ Map: Ready", "❌ Localization: Failed")
                        mainHandler.post { phaseCallback.setPhase(NavigationServiceManager.NavigationPhase.NORMAL_OPERATION) }
                        completeFailure.run()
                    }
                    res.isSuccess -> {
                        activeLocalizeAction = null
                        activeLocalizeFuture = null
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start localization", t)
            mainHandler.post { phaseCallback.setPhase(NavigationServiceManager.NavigationPhase.NORMAL_OPERATION) }
            statusCallback.updateStatus("✅ Map: Ready", "❌ Localization: Failed")
            completeFailure.run()
        }

        return promise.future
    }

    fun stopCurrentLocalization(): Future<Void> {
        val currentAction = activeLocalizeAction
        val currentFuture = activeLocalizeFuture
        if (currentAction != null && currentFuture != null) {
            Log.i(TAG, "Cancelling active Localize action")
            isLocalizationReady = false
            try {
                currentFuture.requestCancellation()
            } catch (e: Exception) {
                Log.w(TAG, "Error cancelling Localize future", e)
            }
            activeLocalizeAction = null
            activeLocalizeFuture = null
            statusCallback.updateStatus(null, "🛑 Localization: Stopped")
            return currentFuture
        }
        val resolved = Promise<Void>()
        resolved.setValue(null)
        return resolved.future
    }
}

