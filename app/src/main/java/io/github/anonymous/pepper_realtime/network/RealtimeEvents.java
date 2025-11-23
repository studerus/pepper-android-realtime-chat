package io.github.anonymous.pepper_realtime.network;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class RealtimeEvents {

    public static class BaseEvent {
        @SerializedName("type")
        public String type;
        @SerializedName("event_id")
        public String eventId;
    }

    public static class SessionCreated extends BaseEvent {
        @SerializedName("session")
        public Session session;
    }

    public static class SessionUpdated extends BaseEvent {
        @SerializedName("session")
        public Session session;
    }

    public static class Session {
        @SerializedName("id")
        public String id;
        @SerializedName("model")
        public String model;
        @SerializedName("voice")
        public String voice;
        @SerializedName("instructions")
        public String instructions;
        @SerializedName("temperature")
        public Double temperature;
        @SerializedName("output_audio_format")
        public String outputAudioFormat;
        @SerializedName("tools")
        public List<Object> tools;
        @SerializedName("audio")
        public AudioConfig audio;
    }

    public static class AudioConfig {
        @SerializedName("output")
        public OutputConfig output;
    }

    public static class OutputConfig {
        @SerializedName("voice")
        public String voice;
    }

    public static class AudioTranscriptDelta extends BaseEvent {
        @SerializedName("delta")
        public String delta;
        @SerializedName("response_id")
        public String responseId;
    }

    public static class AudioDelta extends BaseEvent {
        @SerializedName("delta")
        public String delta;
        @SerializedName("response_id")
        public String responseId;
    }

    public static class ResponseCreated extends BaseEvent {
        @SerializedName("response")
        public Response response;
    }

    public static class ResponseDone extends BaseEvent {
        @SerializedName("response")
        public Response response;
    }

    public static class Response {
        @SerializedName("id")
        public String id;
        @SerializedName("status")
        public String status;
        @SerializedName("output")
        public List<Item> output;
    }

    public static class ResponseOutputItemAdded extends BaseEvent {
        @SerializedName("item")
        public Item item;
    }

    public static class ConversationItemCreated extends BaseEvent {
        @SerializedName("item")
        public Item item;
    }

    public static class Item {
        @SerializedName("id")
        public String id;
        @SerializedName("type")
        public String type;
        @SerializedName("role")
        public String role;
        @SerializedName("content")
        public List<Content> content;

        // Function call fields
        @SerializedName("name")
        public String name;
        @SerializedName("call_id")
        public String callId;
        @SerializedName("arguments")
        public String arguments;
    }

    public static class Content {
        @SerializedName("type")
        public String type;
        @SerializedName("text")
        public String text;
    }

    public static class AudioTranscriptDone extends BaseEvent {
        @SerializedName("transcript")
        public String transcript;
        @SerializedName("response_id")
        public String responseId;
    }

    public static class UserSpeechStarted extends BaseEvent {
        @SerializedName("item_id")
        public String itemId;
    }

    public static class UserSpeechStopped extends BaseEvent {
        @SerializedName("item_id")
        public String itemId;
    }

    public static class UserTranscriptCompleted extends BaseEvent {
        @SerializedName("item_id")
        public String itemId;
        @SerializedName("transcript")
        public String transcript;
    }

    public static class UserTranscriptFailed extends BaseEvent {
        @SerializedName("item_id")
        public String itemId;
        @SerializedName("error")
        public Error error;
    }

    public static class ErrorEvent extends BaseEvent {
        @SerializedName("error")
        public Error error;
    }

    public static class Error {
        @SerializedName("type")
        public String type;
        @SerializedName("code")
        public String code;
        @SerializedName("message")
        public String message;
    }
}
