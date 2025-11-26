package io.github.anonymous.pepper_realtime.network

import com.google.gson.annotations.SerializedName

/**
 * Data classes for OpenAI Realtime API events
 */
object RealtimeEvents {

    open class BaseEvent {
        @SerializedName("type")
        @JvmField
        var type: String? = null

        @SerializedName("event_id")
        @JvmField
        var eventId: String? = null
    }

    class SessionCreated : BaseEvent() {
        @SerializedName("session")
        @JvmField
        var session: Session? = null
    }

    class SessionUpdated : BaseEvent() {
        @SerializedName("session")
        @JvmField
        var session: Session? = null
    }

    class Session {
        @SerializedName("id")
        @JvmField
        var id: String? = null

        @SerializedName("model")
        @JvmField
        var model: String? = null

        @SerializedName("voice")
        @JvmField
        var voice: String? = null

        @SerializedName("instructions")
        @JvmField
        var instructions: String? = null

        @SerializedName("temperature")
        @JvmField
        var temperature: Double? = null

        @SerializedName("output_audio_format")
        @JvmField
        var outputAudioFormat: String? = null

        @SerializedName("tools")
        @JvmField
        var tools: List<Any>? = null

        @SerializedName("audio")
        @JvmField
        var audio: AudioConfig? = null
    }

    class AudioConfig {
        @SerializedName("output")
        @JvmField
        var output: OutputConfig? = null
    }

    class OutputConfig {
        @SerializedName("voice")
        @JvmField
        var voice: String? = null
    }

    class AudioTranscriptDelta : BaseEvent() {
        @SerializedName("delta")
        @JvmField
        var delta: String? = null

        @SerializedName("response_id")
        @JvmField
        var responseId: String? = null
    }

    class AudioDelta : BaseEvent() {
        @SerializedName("delta")
        @JvmField
        var delta: String? = null

        @SerializedName("response_id")
        @JvmField
        var responseId: String? = null
    }

    class ResponseCreated : BaseEvent() {
        @SerializedName("response")
        @JvmField
        var response: Response? = null
    }

    class ResponseDone : BaseEvent() {
        @SerializedName("response")
        @JvmField
        var response: Response? = null
    }

    class Response {
        @SerializedName("id")
        @JvmField
        var id: String? = null

        @SerializedName("status")
        @JvmField
        var status: String? = null

        @SerializedName("output")
        @JvmField
        var output: List<Item>? = null
    }

    class ResponseOutputItemAdded : BaseEvent() {
        @SerializedName("item")
        @JvmField
        var item: Item? = null
    }

    class ConversationItemCreated : BaseEvent() {
        @SerializedName("item")
        @JvmField
        var item: Item? = null
    }

    class Item {
        @SerializedName("id")
        @JvmField
        var id: String? = null

        @SerializedName("type")
        @JvmField
        var type: String? = null

        @SerializedName("role")
        @JvmField
        var role: String? = null

        @SerializedName("content")
        @JvmField
        var content: List<Content>? = null

        // Function call fields
        @SerializedName("name")
        @JvmField
        var name: String? = null

        @SerializedName("call_id")
        @JvmField
        var callId: String? = null

        @SerializedName("arguments")
        @JvmField
        var arguments: String? = null
    }

    class Content {
        @SerializedName("type")
        @JvmField
        var type: String? = null

        @SerializedName("text")
        @JvmField
        var text: String? = null
    }

    class AudioTranscriptDone : BaseEvent() {
        @SerializedName("transcript")
        @JvmField
        var transcript: String? = null

        @SerializedName("response_id")
        @JvmField
        var responseId: String? = null
    }

    class UserSpeechStarted : BaseEvent() {
        @SerializedName("item_id")
        @JvmField
        var itemId: String? = null
    }

    class UserSpeechStopped : BaseEvent() {
        @SerializedName("item_id")
        @JvmField
        var itemId: String? = null
    }

    class AudioBufferCommitted : BaseEvent() {
        @SerializedName("item_id")
        @JvmField
        var itemId: String? = null

        @SerializedName("previous_item_id")
        @JvmField
        var previousItemId: String? = null
    }

    class UserTranscriptCompleted : BaseEvent() {
        @SerializedName("item_id")
        @JvmField
        var itemId: String? = null

        @SerializedName("transcript")
        @JvmField
        var transcript: String? = null
    }

    class UserTranscriptFailed : BaseEvent() {
        @SerializedName("item_id")
        @JvmField
        var itemId: String? = null

        @SerializedName("error")
        @JvmField
        var error: Error? = null
    }

    class ErrorEvent : BaseEvent() {
        @SerializedName("error")
        @JvmField
        var error: Error? = null
    }

    class Error {
        @SerializedName("type")
        @JvmField
        var type: String? = null

        @SerializedName("code")
        @JvmField
        var code: String? = null

        @SerializedName("message")
        @JvmField
        var message: String? = null
    }
}

