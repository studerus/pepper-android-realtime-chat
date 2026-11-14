package ch.fhnw.pepper_realtime.manager

import android.os.Handler
import android.util.Log
import ch.fhnw.pepper_realtime.tools.OperationScope
import com.aldebaran.qi.Future
import com.aldebaran.qi.Promise
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.LocalizeBuilder
import com.aldebaran.qi.sdk.`object`.actuation.ExplorationMap
import com.aldebaran.qi.sdk.`object`.actuation.Localize
import com.aldebaran.qi.sdk.`object`.actuation.LocalizationStatus
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal class LocalizationCoordinator(
    private val mainHandler: Handler,
    private val phaseCallback: PhaseCallback,
    private val statusCallback: StatusCallback
) {
    fun interface PhaseCallback { fun setPhase(phase: NavigationServiceManager.NavigationPhase) }
    fun interface StatusCallback { fun updateStatus(mapStatus: String?, localizationStatus: String?) }

    private class Run(val success: Runnable?, val failure: Runnable?) {
        val promise = Promise<Boolean>()
        val completed = AtomicBoolean(false)
        val owner = OperationScope()
        var action: Localize? = null
        var future: Future<Void>? = null
        var cancellation: Closeable? = null
        var stopped = false

        fun complete(ok: Boolean) {
            if (ok) {
                if (!owner.runIfActive { settle(true) }) settle(false)
            } else settle(false)
        }

        private fun settle(ok: Boolean) {
            if (!completed.compareAndSet(false, true)) return
            try {
                if (ok) success?.run() else failure?.run()
            } catch (error: Exception) {
                Log.w("LocalizationCoord", "Localization callback failed", error)
            } finally {
                promise.setValue(ok)
            }
        }
    }

    private val lock = Any()
    private var current: Run? = null
    private var generation = 0L
    @Volatile var isLocalizationReady = false
        private set

    fun markLocalizationInProgress() { isLocalizationReady = false }
    fun reset() { stopCurrentLocalization() }

    fun ensureLocalizationIfNeeded(qiContext: QiContext?, explorationMap: ExplorationMap?,
                                   onLocalized: Runnable?, onFailed: Runnable?,
                                   operation: OperationScope? = null): Future<Boolean> {
        val run = Run(onLocalized, onFailed)
        if (operation?.isActive == false || qiContext == null || explorationMap == null) {
            run.complete(false)
            return run.promise.future
        }
        if (isLocalizationReady) {
            if (operation != null) operation.runIfActive { run.complete(true) } else run.complete(true)
            if (!run.completed.get()) run.complete(false)
            return run.promise.future
        }
        val reserve = {
            stopCurrentLocalization()
            synchronized(lock) { current = run; generation++; isLocalizationReady = false }
        }
        try {
            if (operation != null) operation.guard(reserve) else reserve()
        } catch (_: kotlinx.coroutines.CancellationException) {
            run.complete(false)
            return run.promise.future
        }
        run.owner.onCancel { stop(run) }
        run.promise.setOnCancel { run.owner.cancel() }
        val registration = operation?.onCancel { run.owner.cancel() }
        synchronized(lock) {
            if (run.stopped) { registration?.close(); return run.promise.future }
            run.cancellation = registration
        }
        run.owner.runIfActive {
            synchronized(lock) {
                if (current === run && !run.stopped) phaseCallback.setPhase(NavigationServiceManager.NavigationPhase.LOCALIZATION_MODE)
            }
        }
        try {
            val localize = LocalizeBuilder.with(qiContext).withMap(explorationMap).build()
            synchronized(lock) {
                if (run.stopped) return run.promise.future
                run.action = localize
                localize.addOnStatusChangedListener { status ->
                    val ready = synchronized(lock) {
                        if (current !== run || run.stopped) return@addOnStatusChangedListener
                        isLocalizationReady = status == LocalizationStatus.LOCALIZED
                        isLocalizationReady
                    }
                    if (ready) run.owner.runIfActive {
                        statusCallback.updateStatus("✅ Map: Ready", "📍 Localization: Localized")
                        run.complete(true)
                    }
                }
                val future = localize.async().run()
                run.future = future
                future.thenConsume { stop(run, cancelFuture = false) }
            }
        } catch (error: Exception) {
            Log.e("LocalizationCoord", "Failed to start localization", error)
            stop(run)
        }
        return run.promise.future
    }

    private fun stop(run: Run, cancelFuture: Boolean = true) {
        val stoppedGeneration = synchronized(lock) {
            if (run.stopped) return
            run.stopped = true
            if (current === run) { current = null; isLocalizationReady = false }
            generation
        }
        run.cancellation?.close()
        run.owner.cancel()
        try {
            if (cancelFuture) run.future?.requestCancellation()
        } catch (error: Exception) {
            Log.w("LocalizationCoord", "Could not cancel localization", error)
        } finally {
            try { run.action?.removeAllOnStatusChangedListeners() } catch (_: Exception) { }
            run.complete(false) // Also resolves cancellation before the first LOCALIZED event.
        }
        mainHandler.post {
            synchronized(lock) {
                if (current == null && generation == stoppedGeneration) {
                    statusCallback.updateStatus(null, "🛑 Localization: Stopped")
                    phaseCallback.setPhase(NavigationServiceManager.NavigationPhase.NORMAL_OPERATION)
                }
            }
        }
    }

    fun stopCurrentLocalization(): Future<Void> {
        val run = synchronized(lock) { current }
        if (run != null) {
            stop(run)
            run.future?.let { return it }
        }
        isLocalizationReady = false
        return Promise<Void>().apply { setValue(null) }.future
    }
}
