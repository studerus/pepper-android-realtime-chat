package io.github.anonymous.pepper_realtime.network

import com.google.gson.annotations.SerializedName

/**
 * Data classes for OpenAI Realtime API events
 */
object RealtimeEvents {

    open class BaseEvent {
        @SerializedName("type")
        var type: String? = null

        @SerializedName("event_id")
        var eventId: String? = null
    }

    class SessionCreated : BaseEvent() {
        @SerializedName("session")
        var session: Session? = null
    }

    class SessionUpdated : BaseEvent() {
        @SerializedName("session")
        var session: Session? = null
    }

    class Session {
        @SerializedName("id")
        var id: String? = null

        @SerializedName("model")
        var model: String? = null

        @SerializedName("voice")
        var voice: String? = null

        @SerializedName("instructions")
        var instructions: String? = null

        @SerializedName("temperature")
        var temperature: Double? = null

        @SerializedName("output_audio_format")
        var outputAudioFormat: String? = null

        @SerializedName("tools")
        var tools: List<Any>? = null

        @SerializedName("audio")
        var audio: AudioConfig? = null
    }

    class AudioConfig {
        @SerializedName("output")
        var output: OutputConfig? = null
    }

    class OutputConfig {
        @SerializedName("voice")
        var voice: String? = null
    }

    class AudioTranscriptDelta : BaseEvent() {
        @SerializedName("delta")
        var delta: String? = null

        @SerializedName("response_id")
        var responseId: String? = null
    }

    class AudioDelta : BaseEvent() {
        @SerializedName("delta")
        var delta: String? = null

        @SerializedName("response_id")
        var responseId: String? = null
    }

    class ResponseCreated : BaseEvent() {
        @SerializedName("response")
        var response: Response? = null
    }

    class ResponseDone : BaseEvent() {
        @SerializedName("response")
        var response: Response? = null
    }

    class Response {
        @SerializedName("id")
        var id: String? = null

        @SerializedName("status")
        var status: String? = null

        @SerializedName("output")
        var output: List<Item>? = null
    }

    class ResponseOutputItemAdded : BaseEvent() {
        @SerializedName("item")
        var item: Item? = null
    }

    class ConversationItemCreated : BaseEvent() {
        @SerializedName("item")
        var item: Item? = null
    }

    class Item {
        @SerializedName("id")
        var id: String? = null

        @SerializedName("type")
        var type: String? = null

        @SerializedName("role")
        var role: String? = null

        @SerializedName("content")
        var content: List<Content>? = null

        // Function call fields
        @SerializedName("name")
        var name: String? = null

        @SerializedName("call_id")
        var callId: String? = null

        @SerializedName("arguments")
        var arguments: String? = null
    }

    class Content {
        @SerializedName("type")
        var type: String? = null

        @SerializedName("text")
        var text: String? = null
    }

    class AudioTranscriptDone : BaseEvent() {
        @SerializedName("transcript")
        var transcript: String? = null

        @SerializedName("response_id")
        var responseId: String? = null
    }

    class UserSpeechStarted : BaseEvent() {
        @SerializedName("item_id")
        var itemId: String? = null
    }

    class UserSpeechStopped : BaseEvent() {
        @SerializedName("item_id")
        var itemId: String? = null
    }

    class AudioBufferCommitted : BaseEvent() {
        @SerializedName("item_id")
        var itemId: String? = null

        @SerializedName("previous_item_id")
        var previousItemId: String? = null
    }

    class UserTranscriptCompleted : BaseEvent() {
        @SerializedName("item_id")
        var itemId: String? = null

        @SerializedName("transcript")
        var transcript: String? = null
    }

    class UserTranscriptFailed : BaseEvent() {
        @SerializedName("item_id")
        var itemId: String? = null

        @SerializedName("error")
        var error: Error? = null
    }

    class ErrorEvent : BaseEvent() {
        @SerializedName("error")
        var error: Error? = null
    }

    class Error {
        @SerializedName("type")
        var type: String? = null

        @SerializedName("code")
        var code: String? = null

        @SerializedName("message")
        var message: String? = null
    }
}


