package io.github.anonymous.pepper_realtime.tools.interfaces;

public interface RealtimeMessageSender {
    void sendMessageToRealtimeAPI(String text, boolean requestResponse, boolean allowInterrupt);
}
