package ch.fhnw.pepper_realtime.network

import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * Unit tests for RealtimeEventHandler.
 * 
 * Note: Audio delta tests are skipped because they require android.util.Base64
 * which is not available in unit tests. These should be tested in instrumentation tests.
 */
@RunWith(MockitoJUnitRunner::class)
class RealtimeEventHandlerTest {

    @Mock
    private lateinit var mockListener: RealtimeEventHandler.Listener

    private lateinit var eventHandler: RealtimeEventHandler

    @Before
    fun setUp() {
        eventHandler = RealtimeEventHandler(mockListener)
    }

    // ========== Session Events ==========

    @Test
    fun `handles session_created event`() {
        val event = """
            {
                "type": "session.created",
                "session": {
                    "id": "sess_123",
                    "model": "gpt-4o-realtime-preview"
                }
            }
        """.trimIndent()

        eventHandler.handle(event)

        // session.created doesn't trigger listener callback, just logs
        verify(mockListener, never()).onError(any())
    }

    @Test
    fun `handles session_updated event`() {
        val event = """
            {
                "type": "session.updated",
                "session": {
                    "id": "sess_123",
                    "model": "gpt-4o-realtime-preview"
                }
            }
        """.trimIndent()

        eventHandler.handle(event)

        verify(mockListener).onSessionUpdated(any())
    }

    // ========== Response Events ==========

    @Test
    fun `handles response_created event`() {
        val event = """
            {
                "type": "response.created",
                "response": {
                    "id": "resp_abc123"
                }
            }
        """.trimIndent()

        eventHandler.handle(event)

        verify(mockListener).onResponseCreated(eq("resp_abc123"))
        verify(mockListener).onResponseBoundary(eq("resp_abc123"))
    }

    @Test
    fun `handles response_done event`() {
        val event = """
            {
                "type": "response.done",
                "response": {
                    "id": "resp_abc123",
                    "status": "completed"
                }
            }
        """.trimIndent()

        eventHandler.handle(event)

        verify(mockListener).onResponseDone(any())
    }

    @Test
    fun `handles response_audio_done event`() {
        val event = """{"type": "response.audio.done"}"""

        eventHandler.handle(event)

        verify(mockListener).onAudioDone()
    }

    @Test
    fun `handles response_output_audio_done event`() {
        val event = """{"type": "response.output_audio.done"}"""

        eventHandler.handle(event)

        verify(mockListener).onAudioDone()
    }

    // ========== Transcript Events ==========

    @Test
    fun `handles audio_transcript_delta event`() {
        val event = """
            {
                "type": "response.audio_transcript.delta",
                "delta": "Hello ",
                "response_id": "resp_123"
            }
        """.trimIndent()

        eventHandler.handle(event)

        verify(mockListener).onAudioTranscriptDelta(eq("Hello "), anyOrNull())
    }

    @Test
    fun `handles audio_transcript_done event`() {
        val event = """
            {
                "type": "response.audio_transcript.done",
                "transcript": "Hello world",
                "response_id": "resp_123"
            }
        """.trimIndent()

        eventHandler.handle(event)

        verify(mockListener).onAudioTranscriptDone(eq("Hello world"), anyOrNull())
    }

    @Test
    fun `handles GA output_audio_transcript_delta event`() {
        val event = """
            {
                "type": "response.output_audio_transcript.delta",
                "delta": "Hi ",
                "response_id": "resp_456"
            }
        """.trimIndent()

        eventHandler.handle(event)

        verify(mockListener).onAudioTranscriptDelta(eq("Hi "), anyOrNull())
    }

    @Test
    fun `handles GA output_audio_transcript_done event`() {
        val event = """
            {
                "type": "response.output_audio_transcript.done",
                "transcript": "Hi there",
                "response_id": "resp_456"
            }
        """.trimIndent()

        eventHandler.handle(event)

        verify(mockListener).onAudioTranscriptDone(eq("Hi there"), anyOrNull())
    }

    // ========== User Speech Events (Realtime API Audio Mode) ==========

    @Test
    fun `handles speech_started event`() {
        val event = """
            {
                "type": "input_audio_buffer.speech_started",
                "item_id": "item_abc"
            }
        """.trimIndent()

        eventHandler.handle(event)

        verify(mockListener).onUserSpeechStarted(eq("item_abc"))
    }

    @Test
    fun `handles speech_stopped event`() {
        val event = """
            {
                "type": "input_audio_buffer.speech_stopped",
                "item_id": "item_abc"
            }
        """.trimIndent()

        eventHandler.handle(event)

        verify(mockListener).onUserSpeechStopped(eq("item_abc"))
    }

    @Test
    fun `handles audio_buffer_committed event`() {
        val event = """
            {
                "type": "input_audio_buffer.committed",
                "item_id": "item_xyz"
            }
        """.trimIndent()

        eventHandler.handle(event)

        verify(mockListener).onAudioBufferCommitted(eq("item_xyz"))
    }

    @Test
    fun `handles user_transcript_completed event`() {
        val event = """
            {
                "type": "conversation.item.input_audio_transcription.completed",
                "item_id": "item_123",
                "transcript": "What's the weather?"
            }
        """.trimIndent()

        eventHandler.handle(event)

        verify(mockListener).onUserTranscriptCompleted(eq("item_123"), eq("What's the weather?"))
    }

    @Test
    fun `handles user_transcript_failed event`() {
        val event = """
            {
                "type": "conversation.item.input_audio_transcription.failed",
                "item_id": "item_456",
                "error": {
                    "message": "Audio too short"
                }
            }
        """.trimIndent()

        eventHandler.handle(event)

        verify(mockListener).onUserTranscriptFailed(eq("item_456"), any())
    }

    // ========== Conversation Item Events ==========

    @Test
    fun `handles conversation_item_created for user message`() {
        val event = """
            {
                "type": "conversation.item.created",
                "item": {
                    "id": "item_user_123",
                    "role": "user",
                    "type": "message"
                }
            }
        """.trimIndent()

        eventHandler.handle(event)

        verify(mockListener).onUserItemCreated(eq("item_user_123"), any())
    }

    @Test
    fun `handles conversation_item_created for assistant does not trigger user callback`() {
        val event = """
            {
                "type": "conversation.item.created",
                "item": {
                    "id": "item_asst_123",
                    "role": "assistant",
                    "type": "message"
                }
            }
        """.trimIndent()

        eventHandler.handle(event)

        // Should not call onUserItemCreated for assistant messages
        verify(mockListener, never()).onUserItemCreated(any(), any())
    }

    @Test
    fun `handles response_output_item_added for assistant`() {
        val event = """
            {
                "type": "response.output_item.added",
                "item": {
                    "id": "item_asst_456",
                    "type": "message",
                    "role": "assistant"
                }
            }
        """.trimIndent()

        eventHandler.handle(event)

        verify(mockListener).onAssistantItemAdded(eq("item_asst_456"))
    }

    // ========== Error Events ==========

    @Test
    fun `handles error event`() {
        val event = """
            {
                "type": "error",
                "error": {
                    "message": "Rate limit exceeded",
                    "code": "rate_limit_error"
                }
            }
        """.trimIndent()

        eventHandler.handle(event)

        verify(mockListener).onError(any())
    }

    // ========== Unknown Events ==========

    @Test
    fun `handles unknown event type`() {
        val event = """
            {
                "type": "some.unknown.event",
                "data": "test"
            }
        """.trimIndent()

        eventHandler.handle(event)

        verify(mockListener).onUnknown(eq("some.unknown.event"), any())
    }

    // ========== Malformed Events ==========

    @Test
    fun `handles malformed JSON gracefully`() {
        val malformedJson = "{ this is not valid json }"

        eventHandler.handle(malformedJson)

        verify(mockListener).onUnknown(eq("parse_error"), anyOrNull())
    }

    @Test
    fun `handles empty string gracefully`() {
        eventHandler.handle("")

        // Should trigger parse error or unknown
        verify(mockListener).onUnknown(any(), anyOrNull())
    }

    @Test
    fun `handles missing type field`() {
        val event = """{"data": "no type field"}"""

        eventHandler.handle(event)

        // Empty type should be treated as unknown
        verify(mockListener).onUnknown(eq(""), any())
    }

    // ========== Rate Limits Event ==========

    @Test
    fun `handles rate_limits_updated event without error`() {
        val event = """
            {
                "type": "rate_limits.updated",
                "rate_limits": {
                    "requests": 100,
                    "tokens": 10000
                }
            }
        """.trimIndent()

        eventHandler.handle(event)

        // Should not trigger error
        verify(mockListener, never()).onError(any())
    }

    // Note: Audio delta tests (response.audio.delta, response.output_audio.delta)
    // are skipped because they require android.util.Base64 which is unavailable
    // in unit tests. These should be tested in Android instrumentation tests.
}
