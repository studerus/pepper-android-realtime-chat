package io.github.hrilab.pepper_realtime;

import android.util.Log;

public class TurnManager {
    public enum State { IDLE, LISTENING, THINKING, SPEAKING }

    public interface Callbacks {
        void onEnterListening();
        void onEnterThinking();
        void onEnterSpeaking();
        void onExitSpeaking();
    }

    private static final String TAG = "TurnManager";
    private volatile State state = State.IDLE;
    private final Callbacks callbacks;

    public TurnManager(Callbacks callbacks) { this.callbacks = callbacks; }

    public State getState() { return state; }

    public synchronized void setState(State next) {
        if (next == state) return;
        State prev = state;
        state = next;
        Log.d(TAG, "State: " + prev + " -> " + next);
        switch (next) {
            case LISTENING:
                callbacks.onEnterListening();
                break;
            case THINKING:
                callbacks.onEnterThinking();
                break;
            case SPEAKING:
                callbacks.onEnterSpeaking();
                break;
            case IDLE:
                // no-op
                break;
        }
        if (prev == State.SPEAKING && next != State.SPEAKING) {
            callbacks.onExitSpeaking();
        }
    }
}
