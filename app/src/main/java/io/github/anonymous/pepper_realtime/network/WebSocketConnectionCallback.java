package io.github.anonymous.pepper_realtime.network;

public interface WebSocketConnectionCallback {
    void onSuccess();

    void onError(Throwable error);
}
