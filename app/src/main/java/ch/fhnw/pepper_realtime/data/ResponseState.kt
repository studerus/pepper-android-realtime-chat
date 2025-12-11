package ch.fhnw.pepper_realtime.data

/**
 * Internal response state for tracking conversation flow.
 * Replaces individual @Volatile variables with a single atomic state object.
 */
data class ResponseState(
    val currentResponseId: String? = null,
    val cancelledResponseId: String? = null,
    val lastChatBubbleResponseId: String? = null,
    val isExpectingFinalAnswerAfterToolCall: Boolean = false,
    val lastAssistantItemId: String? = null
)

