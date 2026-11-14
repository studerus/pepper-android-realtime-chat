package ch.fhnw.pepper_realtime.tools

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.Closeable

/** Owns work which may continue after a tool has returned its initial response. */
class OperationScope {
    private val lock = Any()
    private var cancelled = false
    private val cancellations = linkedMapOf<Any, () -> Unit>()

    val isActive: Boolean get() = synchronized(lock) { !cancelled }

    fun ensureActive() {
        if (!isActive) throw CancellationException("Chat operation cancelled")
    }

    /** Serialize short starts/deliveries with cancellation; never wait for an action here. */
    fun <T> guard(action: () -> T): T = synchronized(lock) {
        ensureActive()
        action()
    }

    fun runIfActive(action: () -> Unit): Boolean = synchronized(lock) {
        if (cancelled) return false
        action()
        true
    }

    fun onCancel(action: () -> Unit): Closeable {
        val key = Any()
        val runNow = synchronized(lock) {
            if (cancelled) true else { cancellations[key] = action; false }
        }
        if (runNow) invokeCancellation(action)
        return Closeable { synchronized(lock) { cancellations.remove(key) } }
    }

    fun cancel() {
        val actions = synchronized(lock) {
            if (cancelled) return
            cancelled = true
            cancellations.values.toList().also { cancellations.clear() }
        }
        // One failed cleanup must not prevent the other actions from stopping.
        actions.forEach(::invokeCancellation)
    }

    private fun invokeCancellation(action: () -> Unit) {
        try { action() } catch (error: Exception) { Log.w("ChatOperation", "Action cleanup failed", error) }
    }

    fun launchIn(scope: CoroutineScope, dispatcher: CoroutineDispatcher, block: suspend CoroutineScope.() -> Unit): Job {
        val job = scope.launch(dispatcher, start = CoroutineStart.LAZY, block = block)
        val registration = onCancel { job.cancel() }
        job.invokeOnCompletion { registration.close() }
        job.start()
        return job
    }
}
