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
        private const val KEY_IDLE_DEFAULT_INSTALLED = "idle_default_installed"
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun installIdleDefaultOnUpgrade() {
        if (prefs.getBoolean(KEY_IDLE_DEFAULT_INSTALLED, false)) return
        val json = prefs.getString(KEY_RULES, null) ?: return
        try {
            val existing = RuleImport.parse(json, allowEmpty = true)
            val edit = prefs.edit()
            if (existing.isNotEmpty() && existing.none { it.id == EventRule.idleReminder.id || it.eventType == PersonEventType.CONVERSATION_IDLE }) {
                val array = JSONArray(json)
                array.put(JSONArray(rulesToJson(listOf(EventRule.idleReminder))).getJSONObject(0))
                edit.putString(KEY_RULES, array.toString())
            }
            edit.putBoolean(KEY_IDLE_DEFAULT_INSTALLED, true).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Leaving existing rules unchanged during idle reminder upgrade", e)
        }
    }

    /**
     * Load rules from SharedPreferences.
     * Returns default rules on first launch.
     */
    @Synchronized
    fun loadRules(): List<EventRule> {
        // Check if this is first launch
        if (!prefs.getBoolean(KEY_INITIALIZED, false) && !prefs.contains(KEY_RULES)) {
            Log.i(TAG, "First launch - saving default rules")
            saveRules(EventRule.defaultRules)
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
    @Synchronized
    fun saveRules(rules: List<EventRule>) {
        try {
            val json = rulesToJson(rules)
            prefs.edit().putString(KEY_RULES, json).putBoolean(KEY_INITIALIZED, true)
                .putBoolean(KEY_IDLE_DEFAULT_INSTALLED, true).apply()
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
     * @return Committed rules and actual added/skipped counts, or an actionable failure.
     */
    @Synchronized
    fun importFromJson(json: String, merge: Boolean = false): RuleImportResult {
        return try {
            val imported = RuleImport.parse(json)
            // Read without first-launch writes or best-effort parsing that could silently discard old rules.
            val existing = if (!merge) emptyList() else if (prefs.contains(KEY_RULES)) {
                try { RuleImport.parse(prefs.getString(KEY_RULES, null) ?: error("Missing stored rules"), allowEmpty = true) }
                catch (error: Exception) { throw IllegalArgumentException("Existing rules cannot be merged: ${error.message}", error) }
            } else if (!prefs.getBoolean(KEY_INITIALIZED, false)) EventRule.defaultRules else emptyList()
            val existingIds = existing.map { it.id }.toSet()
            val added = imported.filter { it.id !in existingIds }
            val result = RuleImportResult.Success(existing + added, added.size, imported.size - added.size)
            if (merge && added.isEmpty()) return result
            val serialized = rulesToJson(result.rules)
            if (commitImport(serialized)) result
            else RuleImportResult.Failure("Could not save the imported rules. The previous rules remain active; please retry.")
        } catch (error: IllegalArgumentException) {
            RuleImportResult.Failure(error.message ?: "Invalid rule import.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import rules from JSON", e)
            RuleImportResult.Failure("Could not import rules. Existing rules were kept.")
        }
    }

    private fun commitImport(json: String): Boolean {
        val previousJson = prefs.getString(KEY_RULES, null)
        val hadInitialized = prefs.contains(KEY_INITIALIZED)
        val previousInitialized = prefs.getBoolean(KEY_INITIALIZED, false)
        try {
            if (prefs.edit().putString(KEY_RULES, json).putBoolean(KEY_INITIALIZED, true).commit()) return true
        } catch (error: Exception) { Log.e(TAG, "Rule import write failed", error) }
        // commit() also changes in-memory preferences when its disk write fails. Restore the raw snapshot.
        try {
            val restore = prefs.edit()
            if (previousJson == null) restore.remove(KEY_RULES) else restore.putString(KEY_RULES, previousJson)
            if (hadInitialized) restore.putBoolean(KEY_INITIALIZED, previousInitialized) else restore.remove(KEY_INITIALIZED)
            if (!restore.commit()) Log.e(TAG, "Could not persist the restored rule snapshot")
        } catch (error: Exception) { Log.e(TAG, "Could not restore rule preferences", error) }
        return false
    }

    /**
     * Reset to default rules.
     */
    @Synchronized
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

