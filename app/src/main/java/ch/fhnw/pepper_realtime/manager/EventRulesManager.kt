package ch.fhnw.pepper_realtime.manager

import android.util.Log
import ch.fhnw.pepper_realtime.data.EventRule
import ch.fhnw.pepper_realtime.data.MatchedRule
import ch.fhnw.pepper_realtime.data.RulePersistence
import ch.fhnw.pepper_realtime.data.RuleActionType
import ch.fhnw.pepper_realtime.service.EventRuleEngine
import ch.fhnw.pepper_realtime.ui.EventRulesState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for event rules state and operations.
 * Extracted from ChatViewModel for better separation of concerns.
 */
@Singleton
class EventRulesManager @Inject constructor(
    private val eventRuleEngine: EventRuleEngine,
    private val rulePersistence: RulePersistence
) {

    companion object {
        private const val TAG = "EventRulesManager"
    }

    // Expose eventRuleEngine for external access (e.g., for evaluate() calls from PerceptionService)
    val engine: EventRuleEngine get() = eventRuleEngine

    private val _state = MutableStateFlow(EventRulesState())
    val state: StateFlow<EventRulesState> = _state.asStateFlow()

    /**
     * Callback interface for rule actions that affect the chat.
     */
    interface RuleActionHandler {
        fun onAddEventMessage(matchedRule: MatchedRule)
        fun onSendToRealtimeAPI(text: String, requestResponse: Boolean, allowInterrupt: Boolean)
    }

    private var ruleActionHandler: RuleActionHandler? = null

    /**
     * Set the handler for rule actions.
     * Should be called during ViewModel initialization.
     */
    fun setRuleActionHandler(handler: RuleActionHandler) {
        ruleActionHandler = handler
    }

    /**
     * Initialize the event rule engine with persisted rules.
     */
    fun initialize() {
        val rules = rulePersistence.loadRules()
        eventRuleEngine.loadRules(rules)
        _state.update { it.copy(rules = rules) }

        // Set up listener for matched rules
        eventRuleEngine.setListener(object : EventRuleEngine.RuleMatchListener {
            override fun onRuleMatched(matchedRule: MatchedRule) {
                handleRuleAction(matchedRule)
            }
        })

        Log.i(TAG, "Event rules initialized with ${rules.size} rules")
    }

    /**
     * Set the robot state provider for rule condition checks.
     */
    fun setRobotStateProvider(provider: EventRuleEngine.RobotStateProvider) {
        eventRuleEngine.setRobotStateProvider(provider)
    }

    /**
     * Handle a matched rule by executing the appropriate action.
     */
    private fun handleRuleAction(matchedRule: MatchedRule) {
        Log.i(TAG, "Handling rule action: ${matchedRule.rule.name} (${matchedRule.rule.actionType})")

        // Add to recent triggered rules for UI feedback
        _state.update { state ->
            val newRecent = (listOf(matchedRule) + state.recentTriggeredRules).take(10)
            state.copy(recentTriggeredRules = newRecent)
        }

        // Notify handler to add event message to chat
        ruleActionHandler?.onAddEventMessage(matchedRule)

        // Execute the action via callback
        when (matchedRule.rule.actionType) {
            RuleActionType.INTERRUPT_AND_RESPOND -> {
                ruleActionHandler?.onSendToRealtimeAPI(
                    text = matchedRule.resolvedTemplate,
                    requestResponse = true,
                    allowInterrupt = true
                )
            }
            RuleActionType.APPEND_AND_RESPOND -> {
                ruleActionHandler?.onSendToRealtimeAPI(
                    text = matchedRule.resolvedTemplate,
                    requestResponse = true,
                    allowInterrupt = false
                )
            }
            RuleActionType.SILENT_UPDATE -> {
                ruleActionHandler?.onSendToRealtimeAPI(
                    text = matchedRule.resolvedTemplate,
                    requestResponse = false,
                    allowInterrupt = false
                )
            }
        }
    }

    // ==================== UI State Methods ====================

    fun showEventRules() {
        _state.update { it.copy(isVisible = true) }
    }

    fun hideEventRules() {
        _state.update { it.copy(isVisible = false) }
    }

    fun toggleEventRules() {
        _state.update { it.copy(isVisible = !it.isVisible) }
    }

    // ==================== CRUD Operations ====================

    fun addEventRule(rule: EventRule) {
        eventRuleEngine.addRule(rule)
        saveEventRules()
        _state.update { it.copy(rules = eventRuleEngine.getRules()) }
    }

    fun updateEventRule(rule: EventRule) {
        eventRuleEngine.updateRule(rule)
        saveEventRules()
        _state.update { it.copy(rules = eventRuleEngine.getRules()) }
    }

    fun deleteEventRule(ruleId: String) {
        eventRuleEngine.removeRule(ruleId)
        saveEventRules()
        _state.update { it.copy(rules = eventRuleEngine.getRules()) }
    }

    fun toggleEventRuleEnabled(ruleId: String) {
        val rules = eventRuleEngine.getRules()
        val rule = rules.find { it.id == ruleId } ?: return
        val updatedRule = rule.copy(enabled = !rule.enabled)
        updateEventRule(updatedRule)
    }

    private fun saveEventRules() {
        rulePersistence.saveRules(eventRuleEngine.getRules())
    }

    fun resetEventRulesToDefaults() {
        rulePersistence.resetToDefaults()
        val rules = rulePersistence.loadRules()
        eventRuleEngine.loadRules(rules)
        eventRuleEngine.resetCooldowns()
        _state.update { it.copy(rules = rules) }
    }

    // ==================== Import/Export ====================

    fun exportEventRules(): String {
        return rulePersistence.exportToJson()
    }

    fun importEventRules(json: String, merge: Boolean = false): Int {
        val count = rulePersistence.importFromJson(json, merge)
        if (count >= 0) {
            val rules = rulePersistence.loadRules()
            eventRuleEngine.loadRules(rules)
            _state.update { it.copy(rules = rules) }
        }
        return count
    }

    fun setEditingRule(rule: EventRule?) {
        _state.update { it.copy(editingRule = rule) }
    }
}
