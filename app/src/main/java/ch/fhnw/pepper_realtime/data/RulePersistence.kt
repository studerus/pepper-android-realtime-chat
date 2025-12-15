package ch.fhnw.pepper_realtime.data

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles persistence of event rules using SharedPreferences.
 * Supports JSON import/export for sharing rules.
 */
@Singleton
class RulePersistence @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "RulePersistence"
        private const val PREFS_NAME = "event_rules_prefs"
        private const val KEY_RULES = "rules_json"
        private const val KEY_INITIALIZED = "rules_initialized"
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Load rules from SharedPreferences.
     * Returns default rules on first launch.
     */
    fun loadRules(): List<EventRule> {
        // Check if this is first launch
        if (!prefs.getBoolean(KEY_INITIALIZED, false)) {
            Log.i(TAG, "First launch - saving default rules")
            saveRules(EventRule.defaultRules)
            prefs.edit().putBoolean(KEY_INITIALIZED, true).apply()
            return EventRule.defaultRules
        }

        val json = prefs.getString(KEY_RULES, null)
        if (json.isNullOrEmpty()) {
            Log.d(TAG, "No rules found, returning empty list")
            return emptyList()
        }

        return try {
            parseRulesFromJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse rules from JSON", e)
            emptyList()
        }
    }

    /**
     * Save rules to SharedPreferences.
     */
    fun saveRules(rules: List<EventRule>) {
        try {
            val json = rulesToJson(rules)
            prefs.edit().putString(KEY_RULES, json).apply()
            Log.i(TAG, "Saved ${rules.size} rules")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save rules", e)
        }
    }

    /**
     * Export rules as a JSON string for sharing.
     */
    fun exportToJson(): String {
        val rules = loadRules()
        return rulesToJson(rules)
    }

    /**
     * Import rules from a JSON string.
     * @param json The JSON string to import
     * @param merge If true, merges with existing rules; if false, replaces all rules
     * @return Number of rules imported, or -1 on error
     */
    fun importFromJson(json: String, merge: Boolean = false): Int {
        return try {
            val importedRules = parseRulesFromJson(json)
            
            val finalRules = if (merge) {
                val existingRules = loadRules()
                val existingIds = existingRules.map { it.id }.toSet()
                // Add imported rules that don't have conflicting IDs
                existingRules + importedRules.filter { it.id !in existingIds }
            } else {
                importedRules
            }
            
            saveRules(finalRules)
            Log.i(TAG, "Imported ${importedRules.size} rules (merge=$merge)")
            importedRules.size
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import rules from JSON", e)
            -1
        }
    }

    /**
     * Reset to default rules.
     */
    fun resetToDefaults() {
        saveRules(EventRule.defaultRules)
        Log.i(TAG, "Reset to default rules")
    }

    /**
     * Convert a list of rules to JSON string.
     */
    private fun rulesToJson(rules: List<EventRule>): String {
        val jsonArray = JSONArray()
        
        for (rule in rules) {
            val ruleJson = JSONObject().apply {
                put("id", rule.id)
                put("name", rule.name)
                put("enabled", rule.enabled)
                put("eventType", rule.eventType.name)
                put("actionType", rule.actionType.name)
                put("template", rule.template)
                put("cooldownMs", rule.cooldownMs)
                
                // Conditions array
                val conditionsArray = JSONArray()
                for (condition in rule.conditions) {
                    val condJson = JSONObject().apply {
                        put("field", condition.field)
                        put("operator", condition.operator.name)
                        put("value", condition.value)
                    }
                    conditionsArray.put(condJson)
                }
                put("conditions", conditionsArray)
            }
            jsonArray.put(ruleJson)
        }
        
        return jsonArray.toString(2) // Pretty print with 2-space indent
    }

    /**
     * Parse rules from a JSON string.
     */
    private fun parseRulesFromJson(json: String): List<EventRule> {
        val rules = mutableListOf<EventRule>()
        val jsonArray = JSONArray(json)
        
        for (i in 0 until jsonArray.length()) {
            try {
                val ruleJson = jsonArray.getJSONObject(i)
                
                // Parse conditions
                val conditions = mutableListOf<RuleCondition>()
                val conditionsArray = ruleJson.optJSONArray("conditions")
                if (conditionsArray != null) {
                    for (j in 0 until conditionsArray.length()) {
                        val condJson = conditionsArray.getJSONObject(j)
                        conditions.add(
                            RuleCondition(
                                field = condJson.getString("field"),
                                operator = ConditionOperator.valueOf(condJson.getString("operator")),
                                value = condJson.getString("value")
                            )
                        )
                    }
                }
                
                val rule = EventRule(
                    id = ruleJson.getString("id"),
                    name = ruleJson.getString("name"),
                    enabled = ruleJson.optBoolean("enabled", true),
                    eventType = PersonEventType.valueOf(ruleJson.getString("eventType")),
                    conditions = conditions,
                    actionType = RuleActionType.valueOf(ruleJson.getString("actionType")),
                    template = ruleJson.getString("template"),
                    cooldownMs = ruleJson.optLong("cooldownMs", 5000L)
                )
                
                rules.add(rule)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse rule at index $i: ${e.message}")
            }
        }
        
        return rules
    }
}

