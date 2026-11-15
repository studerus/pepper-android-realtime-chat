package ch.fhnw.pepper_realtime.manager

import android.util.Log
import ch.fhnw.pepper_realtime.data.NavigationStore
import ch.fhnw.pepper_realtime.robot.RobotSafetyGuard
import ch.fhnw.pepper_realtime.tools.OperationScope
import ch.fhnw.pepper_realtime.tools.ToolContext
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.LocalizeAndMapBuilder
import com.aldebaran.qi.sdk.`object`.actuation.LocalizeAndMap
import com.aldebaran.qi.sdk.`object`.actuation.LocalizationStatus
import kotlinx.coroutines.*
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Owns one draft from preparation through finalization, independently of individual tool returns. */
class MappingCoordinator internal constructor(
    private val navigation: NavigationServiceManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val buildAction: (QiContext) -> LocalizeAndMap = { LocalizeAndMapBuilder.with(it).build() }
) {
    private class Run(val context: ToolContext, val store: NavigationStore, val draft: NavigationStore.Draft) {
        val owner = OperationScope()
        val registrations = mutableListOf<Closeable>()
        @Volatile var action: LocalizeAndMap? = null
        @Volatile var future: Future<Void>? = null
        @Volatile var finishing = false
        var committed = false
    }

    private val lock = Any()
    private var current: Run? = null
    private var stopping: Future<Void>? = null

    val isLocalized: Boolean get() {
        val run = synchronized(lock) { current } ?: return false
        return !run.finishing && run.owner.isActive && run.future?.isDone == false && run.action?.status == LocalizationStatus.LOCALIZED
    }

    fun start(context: ToolContext): Boolean {
        context.ensureActive()
        val run = synchronized(lock) {
            if (current != null) return false
            val store = context.navigationStore
            Run(context, store, store.begin()).also { current = it }
        }
        run.owner.onCancel { cleanUp(run) }
        attach(run, context)
        try {
            if (!run.owner.isActive || !navigation.beginMapping(run.owner)) {
                run.owner.cancel()
                return false
            }
            context.locationProvider.refreshLocations(context.appContext)
        } catch (error: Exception) {
            run.owner.cancel()
            throw error
        }
        run.owner.launchIn(scope, dispatcher) {
            try {
                withTimeout(30_000) {
                    val previous = synchronized(lock) { stopping }
                    // A failed old action is already stopped and must not poison the next attempt.
                    previous?.awaitResult(allowCancellation = true, allowError = true)
                    navigation.stopCurrentLocalization().awaitResult(allowCancellation = true, allowError = true)
                    run.owner.ensureActive()
                    val qi = context.qiContext as QiContext
                    val safety = RobotSafetyGuard.evaluateMovementSafety(qi)
                    check(safety.isOk()) { safety.message ?: "Mapping safety check failed" }
                    val action = buildAction(qi)
                    ensureActive()
                    run.owner.guard {
                        run.action = action
                        action.addOnStatusChangedListener { status ->
                            run.owner.runIfActive {
                                if (run.finishing) return@runIfActive
                                when (status) {
                                    LocalizationStatus.SCANNING -> context.sendAsyncUpdate("[MAPPING STATUS] Pepper is scanning. Please wait...", false)
                                    LocalizationStatus.LOCALIZED -> {
                                        context.notifyServiceStateChange("mappingLocalized")
                                        context.sendAsyncUpdate("[MAPPING READY] Pepper is ready to explore. Movement commands and saved locations now belong to this draft. Say 'finish the map' to save it; the previous map and its locations remain intact until then.", true)
                                    }
                                    else -> Unit
                                }
                            }
                        }
                        val future = action.async().run()
                        run.future = future
                        future.thenConsume { result ->
                            val unexpected = synchronized(lock) { current === run && !run.finishing }
                            if (unexpected && run.owner.isActive) {
                                context.sendAsyncUpdate("[MAPPING ERROR] Mapping stopped before it was saved. The previous map and locations are unchanged.${if (result.hasError()) " ${result.error?.message}" else ""}", true)
                                run.owner.cancel()
                            }
                        }
                    }
                }
            } catch (error: TimeoutCancellationException) {
                context.sendAsyncUpdate("[MAPPING ERROR] Mapping preparation timed out. The previous map and locations are unchanged.", true)
                run.owner.cancel()
            } catch (_: CancellationException) {
                run.owner.cancel()
            } catch (error: Exception) {
                context.sendAsyncUpdate("[MAPPING ERROR] Could not start mapping: ${error.message}. The previous map and locations are unchanged.", true)
                run.owner.cancel()
            }
        }
        return true
    }

    fun finish(context: ToolContext): Boolean {
        context.ensureActive()
        val run = synchronized(lock) { current } ?: return false
        val accepted = run.owner.guard {
            synchronized(lock) {
                if (current !== run || run.finishing || run.future == null || run.future!!.isDone) false
                else {
                    run.store.freeze(run.draft)
                    run.finishing = true
                    true
                }
            }
        }
        if (!accepted) return false
        attach(run, context)
        run.owner.launchIn(scope, dispatcher) {
            try {
                withTimeout(90_000) {
                    val workerContext = currentCoroutineContext()
                    navigation.prepareMappingFinish(run.owner)
                    val future = run.future!!
                    future.requestCancellation()
                    future.awaitResult(allowCancellation = true)
                    run.owner.ensureActive()
                    ensureActive()
                    val map = run.action!!.dumpMap()
                    val serialized = map.serialize()
                    run.store.stageMap(run.draft, serialized) { workerContext.ensureActive(); run.owner.ensureActive() }
                    run.owner.guard {
                        workerContext.ensureActive()
                        context.ensureActive()
                        run.context.ensureActive()
                        run.store.commit(run.draft)
                        run.committed = true
                    }
                    context.locationProvider.refreshLocations(context.appContext)
                    context.sendAsyncUpdate("[MAP SAVED] The new map and its locations have been saved together. Pepper will now orient itself.", true)

                    run.owner.guard { navigation.invalidateMapCache() }
                    val cached = suspendCancellableCoroutine<Boolean> { continuation ->
                        run.owner.guard {
                            navigation.cacheNewMap(map,
                                { if (continuation.isActive) continuation.resume(true) },
                                { if (continuation.isActive) continuation.resume(false) })
                        }
                    }
                    check(cached) { "Could not prepare the saved map for localization" }
                    context.toolHost.runOnUiThread { context.toolHost.updateMapPreview() }
                    val localization = run.owner.guard {
                        navigation.ensureLocalizationIfNeeded(context.qiContext as QiContext, null, null, context.operation)
                    }
                    // Cancel a pending localization on abort; successful ongoing localization stays chat-owned.
                    val cancelLocalization = run.owner.onCancel { localization.requestCancellation() }
                    try {
                        check(localization.awaitResult() == true) { "Localization failed" }
                    } finally { cancelLocalization.close() }
                    context.sendAsyncUpdate("[ORIENTATION COMPLETED] Pepper is localized and ready for navigation.", true)
                }
            } catch (error: TimeoutCancellationException) {
                reportFinishError(run, context, "Finalization or localization timed out")
            } catch (_: CancellationException) {
                // Chat/call cancellation discards an uncommitted draft without sending stale results.
            } catch (error: Exception) {
                reportFinishError(run, context, error.message ?: "Unknown error")
            } finally {
                run.owner.cancel()
            }
        }
        return true
    }

    private fun reportFinishError(run: Run, context: ToolContext, reason: String) {
        val message = if (run.committed) "[ORIENTATION ERROR] The new map and locations are saved, but orientation is not ready: $reason. You can retry navigation later."
            else "[MAPPING ERROR] The new map could not be saved: $reason. The previous map and locations are unchanged."
        context.sendAsyncUpdate(message, true)
    }

    private fun attach(run: Run, context: ToolContext) {
        val registration = context.onCancel { run.owner.cancel() }
        synchronized(run.registrations) {
            if (run.owner.isActive) run.registrations.add(registration) else registration.close()
        }
    }

    private fun cleanUp(run: Run) {
        synchronized(lock) {
            if (current !== run) return
            // Cancelling a replacement during preparation must still wait for its predecessor.
            run.future?.let { stopping = it }
            current = null
        }
        val cleanups: List<() -> Unit> = listOf(
            { run.future?.let { if (!it.isDone) it.requestCancellation() }; Unit },
            { run.action?.removeAllOnStatusChangedListeners(); Unit },
            { run.store.abort(run.draft) },
            { navigation.endMapping(run.owner) },
            { run.context.locationProvider.refreshLocations(run.context.appContext) }
        )
        cleanups.forEach { cleanup ->
            try { cleanup() } catch (error: Exception) { Log.w("MappingCoordinator", "Mapping cleanup failed", error) }
        }
        synchronized(run.registrations) { run.registrations.forEach { it.close() }; run.registrations.clear() }
    }

    private suspend fun <T> Future<T>.awaitResult(allowCancellation: Boolean = false, allowError: Boolean = false): T? = suspendCancellableCoroutine { continuation ->
        thenConsume { result ->
            if (continuation.isActive) {
                when {
                    result.isCancelled -> if (allowCancellation) continuation.resume(null) else continuation.cancel()
                    result.hasError() -> if (allowError) continuation.resume(null)
                        else continuation.resumeWithException(result.error ?: IllegalStateException("Robot action failed"))
                    else -> continuation.resume(result.value)
                }
            }
        }
        continuation.invokeOnCancellation { requestCancellation() }
    }
}
