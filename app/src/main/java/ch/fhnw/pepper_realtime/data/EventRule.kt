package ch.fhnw.pepper_realtime.data

import java.util.UUID

/**
 * Event types that can trigger rules.
 * Currently focused on person-related events from HumanAwareness.
 */
enum class PersonEventType(val displayName: String, val description: String) {
    PERSON_RECOGNIZED("Person Recognized", "A known person was identified by face recognition"),
    PERSON_APPEARED("Person Appeared", "A new person entered the robot's field of view"),
    PERSON_DISAPPEARED("Person Disappeared", "A person left the robot's field of view"),
    PERSON_LOOKING("Person Looking", "A person started looking at the robot"),
    PERSON_STOPPED_LOOKING("Person Stopped Looking", "A person stopped looking at the robot"),
    PERSON_APPROACHED_CLOSE("Person Approached Close", "A person came within 1.5 meters"),
    PERSON_APPROACHED_INTERACTION("Person Approached Interaction", "A person came within 3 meters")
}

/**
 * Operators for rule conditions.
 */
enum class ConditionOperator(val displayName: String, val symbol: String) {
    EQUALS("equals", "="),
    NOT_EQUALS("not equals", "≠"),
    GREATER_THAN("greater than", ">"),
    LESS_THAN("less than", "<"),
    CONTAINS("contains", "∋")
}

/**
 * A condition that must be met for a rule to trigger.
 * Multiple conditions are AND-linked.
 */
data class RuleCondition(
    val field: String,
    val operator: ConditionOperator,
    val value: String
) {
    companion object {
        /**
         * Available fields for conditions with their expected types.
         */
        val availableFields = mapOf(
            "personName" to FieldInfo("Person Name", FieldType.STRING),
            "distance" to FieldInfo("Distance (m)", FieldType.NUMBER),
            "age" to FieldInfo("Age", FieldType.NUMBER),
            "gender" to FieldInfo("Gender", FieldType.STRING),
            "emotion" to FieldInfo("Emotion", FieldType.STRING),
            "attention" to FieldInfo("Attention", FieldType.STRING),
            "engagement" to FieldInfo("Engagement", FieldType.STRING),
            "smile" to FieldInfo("Smile State", FieldType.STRING),
            "peopleCount" to FieldInfo("People Count", FieldType.NUMBER),
            "isLooking" to FieldInfo("Is Looking", FieldType.BOOLEAN),
            "robotState" to FieldInfo("Robot State", FieldType.STRING)
        )
        
        /**
         * Possible values for robotState field.
         * IDLE, LISTENING, THINKING, SPEAKING
         */
        val robotStateValues = listOf("IDLE", "LISTENING", "THINKING", "SPEAKING")
    }
}

/**
 * Information about a condition field.
 */
data class FieldInfo(
    val displayName: String,
    val type: FieldType
)

/**
 * Types of field values for condition validation.
 */
enum class FieldType {
    STRING,
    NUMBER,
    BOOLEAN
}

/**
 * Action types that determine how the AI responds to a triggered rule.
 */
enum class RuleActionType(val displayName: String, val description: String) {
    INTERRUPT_AND_RESPOND(
        "Interrupt & Respond",
        "Stops current speech, sends context, triggers new response"
    ),
    APPEND_AND_RESPOND(
        "Append & Respond",
        "Adds context without interruption, triggers response"
    ),
    SILENT_UPDATE(
        "Silent Update",
        "Adds context silently, no response triggered"
    )
}

/**
 * An event rule that defines when and how to send context updates to the AI.
 */
data class EventRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val enabled: Boolean = true,
    val eventType: PersonEventType,
    val conditions: List<RuleCondition> = emptyList(),
    val actionType: RuleActionType,
    val template: String,
    val cooldownMs: Long = 5000L
) {
    companion object {
        /**
         * Available placeholders for templates.
         */
        val availablePlaceholders = listOf(
            "{personName}" to "Name of the recognized person",
            "{distance}" to "Distance in meters (e.g., '1.2m')",
            "{age}" to "Estimated age",
            "{gender}" to "Gender (Male/Female)",
            "{emotion}" to "Basic emotion (Joyful, Sad, etc.)",
            "{attention}" to "Attention state",
            "{engagement}" to "Engagement level",
            "{smile}" to "Smile state",
            "{peopleCount}" to "Number of people detected",
            "{robotState}" to "Robot state (LISTENING, SPEAKING, THINKING, IDLE)",
            "{timestamp}" to "Current time"
        )

        /**
         * Default rules that ship with the app.
         */
        val defaultRules = listOf(
            EventRule(
                id = "default-greet-recognized",
                name = "Greet recognized person",
                eventType = PersonEventType.PERSON_RECOGNIZED,
                conditions = listOf(
                    RuleCondition("distance", ConditionOperator.LESS_THAN, "2.5")
                ),
                actionType = RuleActionType.INTERRUPT_AND_RESPOND,
                template = "A person you know named {personName} has appeared at {distance} distance and is looking at you.",
                cooldownMs = 30000L
            ),
            EventRule(
                id = "default-welcome-new",
                name = "Welcome new visitor",
                enabled = false, // Disabled by default to avoid spam
                eventType = PersonEventType.PERSON_APPEARED,
                conditions = emptyList(),
                actionType = RuleActionType.APPEND_AND_RESPOND,
                template = "A new person has entered your field of view. They are {distance} away.",
                cooldownMs = 10000L
            )
        )
    }
}

/**
 * Represents a detected event with associated human info.
 */
data class PersonEvent(
    val type: PersonEventType,
    val humanId: Int,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Result of a matched rule evaluation.
 */
data class MatchedRule(
    val rule: EventRule,
    val resolvedTemplate: String,
    val event: PersonEvent
)

