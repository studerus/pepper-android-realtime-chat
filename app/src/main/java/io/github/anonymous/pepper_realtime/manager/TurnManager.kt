package io.github.anonymous.pepper_realtime.manager

import android.util.Log

class TurnManager(private var callbacks: Callbacks?) {

    enum class State {
        IDLE, LISTENING, THINKING, SPEAKING
    }

    interface Callbacks {
        fun onEnterListening()
        fun onEnterThinking()
        fun onEnterSpeaking()
        fun onExitSpeaking()
    }

    @Volatile
    var state: State = State.IDLE
        private set

    fun setListener(callbacks: Callbacks?) {
        this.callbacks = callbacks
    }

    @Synchronized
    fun setState(next: State) {
        if (next == state) return
        val prev = state
        state = next
        Log.d(TAG, "State: $prev -> $next")

        when (next) {
            State.LISTENING -> callbacks?.onEnterListening()
            State.THINKING -> callbacks?.onEnterThinking()
            State.SPEAKING -> callbacks?.onEnterSpeaking()
            State.IDLE -> { /* no-op */ }
        }

        if (prev == State.SPEAKING && next != State.SPEAKING) {
            callbacks?.onExitSpeaking()
        }
    }

    companion object {
        private const val TAG = "TurnManager"
    }
}

