package ch.fhnw.pepper_realtime.tools

import dagger.hilt.android.scopes.ActivityScoped
import javax.inject.Inject

/** A fresh owner for every connection. Call IDs are meaningful only within that owner. */
@ActivityScoped
class ChatWorkRegistry @Inject constructor() {
    class Session {
        val lifetime = OperationScope()
        private val calls = mutableMapOf<String, OperationScope>()
        private val pendingCalls = mutableSetOf<String>()

        fun beginCall(id: String): OperationScope? = synchronized(calls) {
            if (!lifetime.isActive || calls.containsKey(id)) return null
            OperationScope().also { operation ->
                calls[id] = operation
                pendingCalls.add(id)
                lifetime.onCancel { operation.cancel() }
            }
        }

        fun cancelCall(id: String) {
            val operation = synchronized(calls) { calls[id] }
            operation?.cancel()
        }

        fun completeCall(id: String) { synchronized(calls) { pendingCalls.remove(id) } }

        fun cancelPendingCalls() {
            val pending = synchronized(calls) {
                pendingCalls.mapNotNull { calls[it] }.also { pendingCalls.clear() }
            }
            pending.forEach { it.cancel() }
        }

        fun cancel() {
            lifetime.cancel()
            synchronized(calls) { calls.clear(); pendingCalls.clear() }
        }
    }

    @Volatile var current: Session? = null
        private set

    @Synchronized fun begin(): Session {
        end()
        return Session().also { current = it }
    }

    @Synchronized fun end() {
        val old = current
        current = null
        old?.cancel()
    }
}
