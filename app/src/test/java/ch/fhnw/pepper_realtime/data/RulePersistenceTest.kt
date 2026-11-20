package ch.fhnw.pepper_realtime.data

import android.content.Context
import android.content.SharedPreferences
import ch.fhnw.pepper_realtime.manager.EventRulesManager
import ch.fhnw.pepper_realtime.service.EventRuleEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RulePersistenceTest {
    private val memory = mutableMapOf<String, Any>()
    private val disk = mutableMapOf<String, Any>()
    private val prefs = mock<SharedPreferences>()
    private val context = mock<Context>()
    private lateinit var persistence: RulePersistence
    private lateinit var original: String
    private var edits = 0
    private var failingCommits = 0
    private var throwingCommits = 0
    private var beforeCommit: (() -> Unit)? = null

    private fun rule(id: String = "new") = JSONObject().apply {
        put("id", id); put("name", "Rule $id"); put("enabled", true)
        put("eventType", "PERSON_RECOGNIZED"); put("actionType", "APPEND_AND_RESPOND")
        put("template", "Hello {personName}!"); put("cooldownMs", 5000L); put("conditions", JSONArray())
    }
    private fun rules(vararg values: Any) = JSONArray(values.toList()).toString()
    private fun condition(field: String = "distance", operator: String = "LESS_THAN", value: Any = "2.5") =
        JSONObject().put("field", field).put("operator", operator).put("value", value)

    @Before fun setUp() {
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
        whenever(prefs.contains(any())).thenAnswer { memory.containsKey(it.getArgument<String>(0)) }
        whenever(prefs.getString(any(), anyOrNull())).thenAnswer { memory[it.getArgument<String>(0)] ?: it.getArgument<String?>(1) }
        whenever(prefs.getBoolean(any(), any())).thenAnswer { memory[it.getArgument<String>(0)] ?: it.getArgument<Boolean>(1) }
        whenever(prefs.edit()).thenAnswer { editor() }
        original = rules(rule("old"))
        memory["rules_json"] = original
        memory["rules_initialized"] = true
        disk.putAll(memory)
        persistence = RulePersistence(context)
    }

    /** Android commit mutates memory before reporting whether the disk write succeeded. */
    private fun editor(): SharedPreferences.Editor {
        edits++
        val pending = mutableMapOf<String, Any?>()
        val editor = mock<SharedPreferences.Editor>()
        whenever(editor.putString(any(), anyOrNull())).thenAnswer { pending[it.getArgument(0)] = it.getArgument(1); editor }
        whenever(editor.putBoolean(any(), any())).thenAnswer { pending[it.getArgument(0)] = it.getArgument<Boolean>(1); editor }
        whenever(editor.remove(any())).thenAnswer { pending[it.getArgument(0)] = null; editor }
        fun applyMemory() { pending.forEach { (key, value) -> if (value == null) memory.remove(key) else memory[key] = value } }
        whenever(editor.commit()).thenAnswer {
            beforeCommit?.invoke()
            applyMemory()
            when {
                throwingCommits > 0 -> { throwingCommits--; throw IOException("disk unavailable") }
                failingCommits > 0 -> { failingCommits--; false }
                else -> { disk.clear(); disk.putAll(memory); true }
            }
        }
        doAnswer { applyMemory(); disk.clear(); disk.putAll(memory); null }.whenever(editor).apply()
        return editor
    }

    @Test fun `upgrade adds the idle reminder once without changing or restoring other rules`() {
        persistence.installIdleDefaultOnUpgrade()
        val upgraded = persistence.loadRules()
        assertEquals(listOf("old", EventRule.idleReminder.id), upgraded.map { it.id })
        assertEquals(EventRule.idleReminder, upgraded.last())
        persistence.saveRules(upgraded.take(1))
        RulePersistence(context).installIdleDefaultOnUpgrade()
        assertEquals(upgraded.take(1), persistence.loadRules())
    }

    @Test fun `existing installation upgrades only the old stock template and keeps user settings`() {
        val oldText = "In the current conversation language, briefly ask whether the user wants to continue."
        val reminder = rule(EventRule.idleReminder.id).put("eventType", "CONVERSATION_IDLE")
            .put("enabled", false).put("cooldownMs", 45000L).put("template", oldText)
        memory["idle_default_installed"] = true
        memory["rules_json"] = rules(reminder)
        persistence.installIdleDefaultOnUpgrade()
        val updated = persistence.loadRules().single()
        assertFalse(updated.enabled)
        assertEquals(45000L, updated.cooldownMs)
        assertEquals(EventRule.idleReminder.template, updated.template)
    }

    @Test fun `template upgrade preserves custom text and a previously deleted reminder`() {
        memory["idle_default_installed"] = true
        val custom = rule(EventRule.idleReminder.id).put("eventType", "CONVERSATION_IDLE").put("template", "Custom")
        for (raw in listOf(rules(custom), original)) {
            memory.remove("idle_template_updated")
            memory["rules_json"] = raw
            persistence.installIdleDefaultOnUpgrade()
            assertEquals(raw, memory["rules_json"])
        }
    }

    private fun reject(json: String, merge: Boolean = false, cause: String? = null) {
        val oldMemory = memory.toMap()
        val oldDisk = disk.toMap()
        val oldEdits = edits
        val result = persistence.importFromJson(json, merge)
        assertTrue("Expected rejection: $result", result is RuleImportResult.Failure)
        if (cause != null) assertTrue(result.toString(), (result as RuleImportResult.Failure).message.contains(cause))
        assertEquals(oldMemory, memory)
        assertEquals(oldDisk, disk)
        assertEquals("Validation failure must not edit preferences", oldEdits, edits)
    }

    private fun success(json: String, merge: Boolean = false): RuleImportResult.Success {
        val result = persistence.importFromJson(json, merge)
        assertTrue(result.toString(), result is RuleImportResult.Success)
        return result as RuleImportResult.Success
    }

    @Test fun `empty object cannot erase existing rules`() { reject("[{}]", cause = "Rule 1.id") }

    @Test fun `one invalid rule rejects the whole replacement`() {
        reject(rules(rule(), JSONObject()), cause = "Rule 2.id")
    }

    @Test fun `one invalid rule rejects the whole merge`() {
        reject(rules(rule(), JSONObject()), merge = true, cause = "Rule 2.id")
    }

    @Test fun `empty arrays never clear existing rules`() {
        reject("[]", cause = "no rules")
        reject("[]", merge = true, cause = "no rules")
    }

    @Test fun `duplicate rule IDs reject both import modes including existing IDs`() {
        for (merge in listOf(false, true)) reject(rules(rule("old"), rule("old")), merge, "duplicate ID")
    }

    @Test fun `malformed documents and trailing content cannot partially import`() {
        val valid = rules(rule())
        listOf("", "{}", "null", "[null]", "[42]", "[\"rule\"]", "$valid {}", "$valid garbage",
            "$valid /* comment */", "[${rule()},]", "[{id:'new'}]", "[${rule()}").forEach { reject(it) }
    }

    @Test fun `duplicate JSON fields cannot silently override a value`() {
        val duplicated = rules(rule()).replace("\"enabled\":true", "\"enabled\":true,\"enabled\":false")
        reject(duplicated, cause = "Duplicate field 'enabled'")
    }

    @Test fun `required text fields reject missing blank null and wrong types`() {
        for (field in listOf("id", "name", "template", "eventType", "actionType")) {
            reject(rules(rule().apply { remove(field) }), cause = field)
            for (bad in listOf(" ", JSONObject.NULL, 7, true)) reject(rules(rule().put(field, bad)), cause = field)
        }
    }

    @Test fun `unknown enum values and misspelled fields are rejected`() {
        reject(rules(rule().put("eventType", "NEW_EVENT")), cause = "eventType")
        reject(rules(rule().put("actionType", "UNKNOWN")), cause = "actionType")
        reject(rules(rule().put("condition", JSONArray())), cause = "unknown field 'condition'")
    }

    @Test fun `optional fields use defaults only when absent`() {
        for (bad in listOf("false", 0, JSONObject.NULL)) reject(rules(rule().put("enabled", bad)), cause = "enabled")
        for (bad in listOf(JSONObject(), "[]", JSONObject.NULL)) reject(rules(rule().put("conditions", bad)), cause = "conditions")
        val result = success(rules(rule().apply { remove("enabled"); remove("conditions"); remove("cooldownMs") }))
        assertTrue(result.rules.single().enabled)
        assertEquals(emptyList<RuleCondition>(), result.rules.single().conditions)
        assertEquals(5000L, result.rules.single().cooldownMs)
    }

    @Test fun `cooldown rejects negative fractional string and overflowing numbers`() {
        for (bad in listOf(-1, 1.5, "5000", JSONObject.NULL, true)) reject(rules(rule().put("cooldownMs", bad)), cause = "cooldownMs")
        reject(rules(rule()).replace("\"cooldownMs\":5000", "\"cooldownMs\":9223372036854775808"), cause = "cooldownMs")
        assertEquals(0L, success(rules(rule().put("cooldownMs", 0))).rules.single().cooldownMs)
        assertEquals(Long.MAX_VALUE, success(rules(rule().put("cooldownMs", Long.MAX_VALUE))).rules.single().cooldownMs)
    }

    @Test fun `invalid conditions are reported with rule and condition index`() {
        val badConditions = listOf(JSONObject.NULL, JSONObject(), condition(field = "unknown"),
            condition(operator = "UNKNOWN"), condition(value = 2), condition(value = "NaN"), condition(value = "Infinity"),
            condition(field = "isLooking", operator = "EQUALS", value = "yes"),
            condition(field = "personName", operator = "LESS_THAN", value = "2"))
        for (bad in badConditions) reject(rules(rule().put("conditions", JSONArray().put(condition()).put(bad))), cause = "condition 2")
    }

    @Test fun `valid exports preserve order types templates and conditions on replacement`() {
        val first = rule("one").put("conditions", JSONArray().put(condition())
            .put(condition("isLooking", "EQUALS", "TRUE")).put(condition("personName", "NOT_EQUALS", "")))
        val second = rule("two").put("enabled", false).put("template", "Grüezi {personName}!\nWillkommen.")
        val result = success(rules(first, second))
        assertEquals(2, result.importedCount)
        assertEquals(0, result.skippedCount)
        assertEquals(listOf("one", "two"), result.rules.map { it.id })
        assertFalse(result.rules[1].enabled)
        assertEquals(result.rules, RulePersistence(context).loadRules())
        assertEquals(result.rules, RuleImport.parse(persistence.exportToJson()))
        memory.clear(); memory.putAll(disk)
        assertEquals(result.rules, RulePersistence(context).loadRules())
    }

    @Test fun `merge retains existing IDs and counts only newly added rules`() {
        val oldRules = persistence.loadRules()
        val result = success(rules(rule("old").put("template", "changed"), rule("new")), merge = true)
        assertEquals(1, result.importedCount)
        assertEquals(1, result.skippedCount)
        assertEquals(oldRules.single(), result.rules.first())
        assertEquals(listOf("old", "new"), persistence.loadRules().map { it.id })
    }

    @Test fun `merge with no additions does not write preferences`() {
        val result = success(rules(rule("old")), merge = true)
        assertEquals(0, result.importedCount)
        assertEquals(1, result.skippedCount)
        assertEquals(0, edits)
        assertEquals(original, memory["rules_json"])
    }

    @Test fun `corrupt existing rules cannot be silently dropped during merge`() {
        memory["rules_json"] = rules(rule("old"), JSONObject())
        disk.putAll(memory)
        reject(rules(rule()), merge = true, cause = "Existing rules cannot be merged")
    }

    @Test fun `successful import before first load survives default initialization`() {
        memory.clear(); disk.clear()
        val result = success(rules(rule()))
        assertEquals(true, memory["rules_initialized"])
        assertEquals(result.rules, RulePersistence(context).loadRules())
        memory.clear(); memory.putAll(disk)
        assertEquals(result.rules, RulePersistence(context).loadRules())
    }

    @Test fun `invalid first launch import does not create preferences or defaults`() {
        memory.clear(); disk.clear()
        reject("[{}]", merge = true)
        assertTrue(memory.isEmpty())
    }

    @Test fun `merge before first load includes defaults without a separate initialization write`() {
        memory.clear(); disk.clear()
        val result = success(rules(rule()), merge = true)
        assertEquals(EventRule.defaultRules + result.rules.last(), result.rules)
        assertEquals(1, edits)
        assertEquals(result.rules, RulePersistence(context).loadRules())
    }

    @Test fun `legacy rules without initialized flag are not overwritten by defaults`() {
        memory.remove("rules_initialized"); disk.remove("rules_initialized")
        assertEquals(listOf("old"), persistence.loadRules().map { it.id })
        assertEquals(0, edits)
        assertEquals(original, memory["rules_json"])
    }

    @Test fun `saving an intentionally empty list marks initialization in the same write`() {
        memory.clear(); disk.clear()
        persistence.saveRules(emptyList())
        assertEquals(1, edits)
        assertEquals(true, memory["rules_initialized"])
        assertTrue(RulePersistence(context).loadRules().isEmpty())
    }

    @Test fun `failed disk commit restores raw preferences and allows retry`() {
        memory["rules_initialized"] = false; disk.putAll(memory)
        val before = memory.toMap()
        failingCommits = 1
        assertTrue(persistence.importFromJson(rules(rule())) is RuleImportResult.Failure)
        assertEquals(before, memory)
        assertEquals(before, disk)
        assertEquals(listOf("old"), RulePersistence(context).loadRules().map { it.id })
        assertEquals(1, success(rules(rule())).importedCount)
    }

    @Test fun `write exception after changing memory restores previous snapshot`() {
        val before = memory.toMap()
        throwingCommits = 1
        assertTrue(persistence.importFromJson(rules(rule())) is RuleImportResult.Failure)
        assertEquals(before, memory)
        assertEquals(before, disk)
    }

    @Test fun `failed first import removes uncommitted initialization values`() {
        memory.clear(); disk.clear()
        failingCommits = 1
        assertTrue(persistence.importFromJson(rules(rule())) is RuleImportResult.Failure)
        assertTrue(memory.isEmpty())
        assertTrue(disk.isEmpty())
    }

    @Test fun `failed save preserves the active engine and UI rules`() {
        val engine = EventRuleEngine()
        val manager = EventRulesManager(engine, persistence)
        manager.initialize()
        val previous = manager.state.value.rules
        failingCommits = 1
        assertTrue(manager.importEventRules(rules(rule())) is RuleImportResult.Failure)
        assertEquals(previous, manager.state.value.rules)
        assertEquals(previous, engine.getRules())
        assertEquals(previous, persistence.loadRules())
    }

    @Test fun `concurrent merges preserve both new rule sets`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        beforeCommit = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        try {
            val first = executor.submit<RuleImportResult> { persistence.importFromJson(rules(rule("one")), true) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val second = executor.submit<RuleImportResult> { secondStarted.countDown(); persistence.importFromJson(rules(rule("two")), true) }
            assertTrue(secondStarted.await(3, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(first.get(3, TimeUnit.SECONDS) is RuleImportResult.Success)
            assertTrue(second.get(3, TimeUnit.SECONDS) is RuleImportResult.Success)
            assertEquals(listOf("old", "one", "two"), persistence.loadRules().map { it.id })
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
