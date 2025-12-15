package ch.fhnw.pepper_realtime.ui

import java.util.UUID

/**
 * Represents a chat message in the conversation UI.
 * Supports regular messages, image messages, and function call messages.
 */
class ChatMessage private constructor(
    val sender: Sender,
    val type: Type,
    val uuid: String
) {
    enum class Sender {
        USER, ROBOT
    }

    enum class Type {
        REGULAR_MESSAGE,
        FUNCTION_CALL,
        IMAGE_MESSAGE,
        EVENT_TRIGGER
    }

    var message: String = ""
    var imagePath: String? = null
    var itemId: String? = null
    var functionName: String? = null
    var functionArgs: String? = null
    var functionResult: String? = null
    var isExpanded: Boolean = false
    var functionStartTime: Long = 0L  // System.currentTimeMillis() when function call starts
    var functionEndTime: Long = 0L    // System.currentTimeMillis() when result arrives
    
    // Event trigger fields
    var eventRuleName: String? = null
    var eventType: String? = null
    var eventActionType: String? = null
    var eventTemplate: String? = null      // The template before placeholder replacement
    var eventResolvedText: String? = null  // The text sent to AI (after placeholder replacement)
    var eventPersonName: String? = null    // Name of the person that triggered the event

    /**
     * Creates a shallow copy of the message with updated text.
     * Preserves the UUID to maintain item identity for DiffUtil.
     */
    fun copyWithNewText(newText: String): ChatMessage {
        return ChatMessage(sender, type, uuid).also {
            it.message = newText
            it.imagePath = this.imagePath
            it.itemId = this.itemId
            it.functionName = this.functionName
            it.functionArgs = this.functionArgs
            it.functionResult = this.functionResult
            it.isExpanded = this.isExpanded
            it.functionStartTime = this.functionStartTime
            it.functionEndTime = this.functionEndTime
        }
    }

    /**
     * Creates a shallow copy of the message with updated function result.
     * Preserves the UUID to maintain item identity for DiffUtil.
     * Automatically sets the end time to measure execution duration.
     */
    fun copyWithFunctionResult(result: String): ChatMessage {
        return ChatMessage(sender, type, uuid).also {
            it.message = this.message
            it.imagePath = this.imagePath
            it.itemId = this.itemId
            it.functionName = this.functionName
            it.functionArgs = this.functionArgs
            it.functionResult = result
            it.isExpanded = this.isExpanded
            it.functionStartTime = this.functionStartTime
            it.functionEndTime = System.currentTimeMillis()
        }
    }

    companion object {
        /**
         * Static factory for function call messages.
         * Automatically sets the start time for duration measurement.
         */
        fun createFunctionCall(functionName: String, functionArgs: String, sender: Sender): ChatMessage {
            return ChatMessage(sender, Type.FUNCTION_CALL, UUID.randomUUID().toString()).also {
                it.functionName = functionName
                it.functionArgs = functionArgs
                it.message = "" // Will be generated in display
                it.functionStartTime = System.currentTimeMillis()
            }
        }
        
        /**
         * Static factory for event trigger messages.
         * Shows when an event rule was triggered.
         */
        fun createEventTrigger(
            ruleName: String,
            eventType: String,
            actionType: String,
            template: String,
            resolvedText: String,
            personName: String?
        ): ChatMessage {
            return ChatMessage(Sender.ROBOT, Type.EVENT_TRIGGER, UUID.randomUUID().toString()).also {
                it.eventRuleName = ruleName
                it.eventType = eventType
                it.eventActionType = actionType
                it.eventTemplate = template
                it.eventResolvedText = resolvedText
                it.eventPersonName = personName
                it.message = "" // Will be generated in display
            }
        }
    }

    // Secondary constructors for backward compatibility

    /**
     * Constructor for regular text messages
     */
    constructor(message: String, sender: Sender) : this(sender, Type.REGULAR_MESSAGE, UUID.randomUUID().toString()) {
        this.message = message
    }

    /**
     * Constructor for image messages
     */
    constructor(message: String, imagePath: String?, sender: Sender) : this(sender, Type.IMAGE_MESSAGE, UUID.randomUUID().toString()) {
        this.message = message
        this.imagePath = imagePath
    }
}


