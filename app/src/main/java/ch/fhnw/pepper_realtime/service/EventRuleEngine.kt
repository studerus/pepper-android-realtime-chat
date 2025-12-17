package ch.fhnw.pepper_realtime.service

import android.util.Log
import ch.fhnw.pepper_realtime.data.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Engine for evaluating event rules and generating context updates.
 * Handles condition checking, cooldown management, and placeholder replacement.
 */
@Singleton
class EventRuleEngine @Inject constructor() {

    companion object {
        private const val TAG = "EventRuleEngine"
    }

    /**
     * Listener for matched rules that should trigger actions.
     */
    interface RuleMatchListener {
        fun onRuleMatched(matchedRule: MatchedRule)
    }

    /**
     * Provider for robot state information.
     * Returns current state as string: IDLE, LISTENING, THINKING, SPEAKING
     */
    interface RobotStateProvider {
        fun getCurrentState(): String
    }

    private var rules: List<EventRule> = emptyList()
    private val lastTriggerTimes = mutableMapOf<String, Long>() // ruleId -> lastTriggerTime
    private var listener: RuleMatchListener? = null
    private var robotStateProvider: RobotStateProvider? = null

    /**
     * Set the robot state provider for condition checks.
     */
    fun setRobotStateProvider(provider: RobotStateProvider?) {
        this.robotStateProvider = provider
    }

    /**
     * Set the listener for matched rules.
     */
    fun setListener(listener: RuleMatchListener?) {
        this.listener = listener
    }

    /**
     * Load rules from persistence.
     */
    fun loadRules(rulesList: List<EventRule>) {
        this.rules = rulesList
        Log.i(TAG, "Loaded ${rules.size} rules")
    }

    /**
     * Get all currently loaded rules.
     */
    fun getRules(): List<EventRule> = rules

    /**
     * Update a single rule (for enable/disable toggle).
     */
    fun updateRule(updatedRule: EventRule) {
        rules = rules.map { if (it.id == updatedRule.id) updatedRule else it }
    }

    /**
     * Add a new rule.
     */
    fun addRule(rule: EventRule) {
        rules = rules + rule
    }

    /**
     * Remove a rule by ID.
     */
    fun removeRule(ruleId: String) {
        rules = rules.filter { it.id != ruleId }
        lastTriggerTimes.remove(ruleId)
    }

    /**
     * Evaluate an event against all enabled rules.
     * Returns the first matching rule (first-match-wins), or null if none match.
     */
    fun evaluate(
        event: PersonEvent,
        humanInfo: PerceptionData.HumanInfo,
        allHumans: List<PerceptionData.HumanInfo>
    ): MatchedRule? {
        val now = System.currentTimeMillis()

        for (rule in rules) {
            // Skip disabled rules
            if (!rule.enabled) continue

            // Check event type match
            if (rule.eventType != event.type) continue

            // Check cooldown
            val lastTrigger = lastTriggerTimes[rule.id] ?: 0L
            if (now - lastTrigger < rule.cooldownMs) {
                Log.d(TAG, "Rule '${rule.name}' on cooldown (${now - lastTrigger}ms < ${rule.cooldownMs}ms)")
                continue
            }

            // Check all conditions
            if (!checkConditions(rule, humanInfo, allHumans.size)) {
                Log.d(TAG, "Rule '${rule.name}' conditions not met")
                continue
            }

            // Rule matched!
            val resolvedTemplate = replacePlaceholders(rule.template, humanInfo, allHumans.size)
            lastTriggerTimes[rule.id] = now

            Log.i(TAG, "Rule '${rule.name}' matched! Template: $resolvedTemplate")

            val matchedRule = MatchedRule(rule, resolvedTemplate, event)
            
            // Notify listener
            listener?.onRuleMatched(matchedRule)
            
            return matchedRule
        }

        return null
    }

    /**
     * Check if all conditions of a rule are met (AND-linked).
     */
    private fun checkConditions(
        rule: EventRule,
        humanInfo: PerceptionData.HumanInfo,
        peopleCount: Int
    ): Boolean {
        if (rule.conditions.isEmpty()) return true

        for (condition in rule.conditions) {
            if (!checkCondition(condition, humanInfo, peopleCount)) {
                return false
            }
        }
        return true
    }

    /**
     * Check a single condition against the human info.
     */
    private fun checkCondition(
        condition: RuleCondition,
        humanInfo: PerceptionData.HumanInfo,
        peopleCount: Int
    ): Boolean {
        val fieldValue = getFieldValue(condition.field, humanInfo, peopleCount)

        return try {
            when (condition.operator) {
                ConditionOperator.EQUALS -> {
                    fieldValue.equals(condition.value, ignoreCase = true)
                }
                ConditionOperator.NOT_EQUALS -> {
                    !fieldValue.equals(condition.value, ignoreCase = true)
                }
                ConditionOperator.GREATER_THAN -> {
                    val numField = fieldValue.toDoubleOrNull() ?: return false
                    val numValue = condition.value.toDoubleOrNull() ?: return false
                    numField > numValue
                }
                ConditionOperator.LESS_THAN -> {
                    val numField = fieldValue.toDoubleOrNull() ?: return false
                    val numValue = condition.value.toDoubleOrNull() ?: return false
                    numField < numValue
                }
                ConditionOperator.CONTAINS -> {
                    fieldValue.contains(condition.value, ignoreCase = true)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Condition check failed for ${condition.field}: ${e.message}")
            false
        }
    }

    /**
     * Get the value of a field from HumanInfo as a string.
     */
    private fun getFieldValue(
        field: String,
        humanInfo: PerceptionData.HumanInfo,
        peopleCount: Int
    ): String {
        return when (field.lowercase()) {
            "personname" -> humanInfo.recognizedName ?: ""
            "distance" -> if (humanInfo.distanceMeters > 0) humanInfo.distanceMeters.toString() else ""
            "age" -> if (humanInfo.estimatedAge > 0) humanInfo.estimatedAge.toString() else ""
            "gender" -> humanInfo.gender
            "emotion" -> humanInfo.basicEmotion.name
            "attention" -> humanInfo.attentionState
            "engagement" -> humanInfo.engagementState
            "smile" -> humanInfo.smileState
            "peoplecount" -> peopleCount.toString()
            "islooking" -> humanInfo.attentionState.contains("LOOKING_AT_ROBOT", ignoreCase = true).toString()
            "robotstate" -> robotStateProvider?.getCurrentState() ?: "UNKNOWN"
            else -> ""
        }
    }

    /**
     * Replace placeholders in a template with actual values.
     */
    fun replacePlaceholders(
        template: String,
        humanInfo: PerceptionData.HumanInfo,
        peopleCount: Int
    ): String {
        var result = template

        // Person-related placeholders
        result = result.replace("{personName}", humanInfo.recognizedName ?: "Unknown")
        result = result.replace("{distance}", humanInfo.getDistanceString())
        result = result.replace("{age}", if (humanInfo.estimatedAge > 0) "${humanInfo.estimatedAge}" else "unknown")
        result = result.replace("{gender}", humanInfo.gender.lowercase().replaceFirstChar { it.uppercase() })
        result = result.replace("{emotion}", humanInfo.basicEmotion.getFormattedDisplay())
        result = result.replace("{attention}", humanInfo.getAttentionLevel())
        result = result.replace("{engagement}", humanInfo.getEngagementLevel())
        result = result.replace("{smile}", humanInfo.getSmileStateDisplay())
        result = result.replace("{peopleCount}", peopleCount.toString())

        // Robot state
        result = result.replace("{robotState}", robotStateProvider?.getCurrentState() ?: "unknown")

        // Timestamp
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        result = result.replace("{timestamp}", timeFormat.format(Date()))

        return result
    }

    /**
     * Reset cooldowns for all rules (e.g., after app restart).
     */
    fun resetCooldowns() {
        lastTriggerTimes.clear()
        Log.i(TAG, "All rule cooldowns reset")
    }
}

