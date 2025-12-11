package ch.fhnw.pepper_realtime.tools.interfaces

/**
 * Interface for sending messages to the Realtime API
 */
interface RealtimeMessageSender {
    fun sendMessageToRealtimeAPI(text: String, requestResponse: Boolean, allowInterrupt: Boolean)
}

