package ch.fhnw.pepper_realtime.network

import com.google.gson.annotations.SerializedName

/**
 * Data classes for Google Gemini Live API events.
 * Based on the BidiGenerateContent WebSocket protocol.
 * 
 * Reference: https://ai.google.dev/api/live
 * Example App: docs/liveapi_example_app
 */
object GoogleLiveEvents {

    // ==================== SERVER → CLIENT EVENTS ====================

    /**
     * Main wrapper for all server-to-client messages.
     * Google Live API uses top-level fields instead of "type" field.
     */
    data class ServerMessage(
        @SerializedName("setupComplete")
        val setupComplete: SetupComplete? = null,

        @SerializedName("serverContent")
        val serverContent: ServerContent? = null,

        @SerializedName("toolCall")
        val toolCall: ToolCall? = null,

        @SerializedName("toolCallCancellation")
        val toolCallCancellation: ToolCallCancellation? = null
    )

    /**
     * Confirmation that setup was successful.
     */
    class SetupComplete

    /**
     * Server content containing model output (audio, text, transcriptions).
     */
    data class ServerContent(
        @SerializedName("modelTurn")
        val modelTurn: ModelTurn? = null,

        @SerializedName("outputTranscription")
        val outputTranscription: Transcription? = null,

        @SerializedName("inputTranscription")
        val inputTranscription: Transcription? = null,

        @SerializedName("interrupted")
        val interrupted: Boolean? = null,

        @SerializedName("turnComplete")
        val turnComplete: Boolean? = null,

        @SerializedName("generationComplete")
        val generationComplete: Boolean? = null
    )

    /**
     * Model turn containing parts (text or audio).
     */
    data class ModelTurn(
        @SerializedName("parts")
        val parts: List<Part>? = null
    )

    /**
     * Content part - can be text or inline audio data.
     */
    data class Part(
        @SerializedName("text")
        val text: String? = null,

        @SerializedName("inlineData")
        val inlineData: InlineData? = null,

        @SerializedName("thought")
        val thought: Boolean? = null  // True if this is a "thinking" part (internal reasoning)
    )

    /**
     * Inline binary data (audio).
     */
    data class InlineData(
        @SerializedName("mimeType")
        val mimeType: String? = null,

        @SerializedName("data")
        val data: String? = null  // Base64 encoded
    )

    /**
     * Transcription (input or output).
     */
    data class Transcription(
        @SerializedName("text")
        val text: String? = null
    )

    /**
     * Tool call request from model.
     */
    data class ToolCall(
        @SerializedName("functionCalls")
        val functionCalls: List<FunctionCall>? = null
    )

    /**
     * Individual function call.
     */
    data class FunctionCall(
        @SerializedName("id")
        val id: String? = null,

        @SerializedName("name")
        val name: String? = null,

        @SerializedName("args")
        val args: Map<String, Any>? = null
    )

    /**
     * Tool call cancellation (when user interrupts during tool execution).
     */
    data class ToolCallCancellation(
        @SerializedName("ids")
        val ids: List<String>? = null
    )

    // ==================== CLIENT → SERVER MESSAGES ====================

    /**
     * Setup message - must be first message after WebSocket connection.
     * Contains model, generation config, tools, and system instruction.
     */
    data class SetupMessage(
        @SerializedName("setup")
        val setup: Setup
    )

    data class Setup(
        @SerializedName("model")
        val model: String,

        @SerializedName("generationConfig")
        val generationConfig: GenerationConfig? = null,

        @SerializedName("realtimeInputConfig")
        val realtimeInputConfig: RealtimeInputConfig? = null,

        @SerializedName("systemInstruction")
        val systemInstruction: SystemInstruction? = null,

        @SerializedName("tools")
        val tools: List<ToolDeclaration>? = null
    )

    data class GenerationConfig(
        @SerializedName("responseModalities")
        val responseModalities: List<String>? = null,  // ["AUDIO"] or ["TEXT"]

        @SerializedName("speechConfig")
        val speechConfig: SpeechConfig? = null
    )

    data class SpeechConfig(
        @SerializedName("voiceConfig")
        val voiceConfig: VoiceConfig? = null
    )

    data class VoiceConfig(
        @SerializedName("prebuiltVoiceConfig")
        val prebuiltVoiceConfig: PrebuiltVoiceConfig? = null
    )

    data class PrebuiltVoiceConfig(
        @SerializedName("voiceName")
        val voiceName: String? = null  // Puck, Charon, Kore, Fenrir, Aoede
    )

    data class RealtimeInputConfig(
        @SerializedName("automaticActivityDetection")
        val automaticActivityDetection: AutomaticActivityDetection? = null
    )

    data class AutomaticActivityDetection(
        @SerializedName("disabled")
        val disabled: Boolean? = null,

        @SerializedName("startOfSpeechSensitivity")
        val startOfSpeechSensitivity: String? = null,  // START_SENSITIVITY_HIGH

        @SerializedName("endOfSpeechSensitivity")
        val endOfSpeechSensitivity: String? = null  // END_SENSITIVITY_HIGH
    )

    data class SystemInstruction(
        @SerializedName("parts")
        val parts: List<TextPart>? = null
    )

    data class TextPart(
        @SerializedName("text")
        val text: String? = null
    )

    data class ToolDeclaration(
        @SerializedName("functionDeclarations")
        val functionDeclarations: List<FunctionDeclaration>? = null
    )

    data class FunctionDeclaration(
        @SerializedName("name")
        val name: String,

        @SerializedName("description")
        val description: String? = null,

        @SerializedName("parameters")
        val parameters: Map<String, Any>? = null
    )

    /**
     * Realtime input message - for sending audio or text input.
     */
    data class RealtimeInputMessage(
        @SerializedName("realtimeInput")
        val realtimeInput: RealtimeInput
    )

    data class RealtimeInput(
        @SerializedName("audio")
        val audio: AudioInput? = null,

        @SerializedName("text")
        val text: String? = null
    )

    data class AudioInput(
        @SerializedName("data")
        val data: String,  // Base64 encoded PCM16

        @SerializedName("mimeType")
        val mimeType: String  // "audio/pcm;rate=16000"
    )

    /**
     * Client content message - for context updates without triggering response.
     */
    data class ClientContentMessage(
        @SerializedName("clientContent")
        val clientContent: ClientContent
    )

    data class ClientContent(
        @SerializedName("turns")
        val turns: List<Turn>? = null,

        @SerializedName("turnComplete")
        val turnComplete: Boolean? = null
    )

    data class Turn(
        @SerializedName("role")
        val role: String,  // "user" or "model"

        @SerializedName("parts")
        val parts: List<TextPart>? = null
    )

    /**
     * Tool response message - for returning tool execution results.
     */
    data class ToolResponseMessage(
        @SerializedName("toolResponse")
        val toolResponse: ToolResponse
    )

    data class ToolResponse(
        @SerializedName("functionResponses")
        val functionResponses: List<FunctionResponse>
    )

    data class FunctionResponse(
        @SerializedName("id")
        val id: String,

        @SerializedName("name")
        val name: String,

        @SerializedName("response")
        val response: Map<String, Any>
    )

    // ==================== AVAILABLE VOICES ====================

    // Note: Available voices: Puck, Charon, Kore, Fenrir, Aoede
    // Audio: Input 16kHz, Output 24kHz (PCM format)
}

