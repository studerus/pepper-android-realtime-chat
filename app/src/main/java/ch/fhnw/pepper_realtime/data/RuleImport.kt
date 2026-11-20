package ch.fhnw.pepper_realtime.data

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader
import java.math.BigDecimal

sealed class RuleImportResult {
    data class Success(val rules: List<EventRule>, val importedCount: Int, val skippedCount: Int = 0) : RuleImportResult()
    data class Failure(val message: String) : RuleImportResult()
}

/** Import validation is deliberately separate from best-effort loading of existing installations. */
internal object RuleImport {
    fun parse(json: String, allowEmpty: Boolean = false): List<EventRule> {
        // Android's JSONTokener accepts malformed JSON and trailing input; validate the whole document first.
        try {
            JsonReader(StringReader(json)).use { reader ->
                reader.isLenient = false
                require(reader.peek() == JsonToken.BEGIN_ARRAY) { "Expected an array of rules." }
                checkJson(reader, 0)
                require(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected content after the rule list." }
            }
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid JSON: ${error.message}", error)
        }
        val array = JSONArray(json)
        require(allowEmpty || array.length() > 0) { "The import contains no rules. Existing rules were kept." }
        val ids = mutableSetOf<String>()
        return (0 until array.length()).map { index ->
            val path = "Rule ${index + 1}"
            val rule = array.opt(index) as? JSONObject ?: invalid(path, "expected an object")
            checkFields(rule, setOf("id", "name", "enabled", "eventType", "conditions", "actionType", "template", "cooldownMs"), path)
            val id = string(rule, "id", path)
            require(ids.add(id)) { "$path: duplicate ID '$id'." }
            val conditions = if (!rule.has("conditions")) JSONArray() else
                rule.opt("conditions") as? JSONArray ?: invalid("$path.conditions", "expected an array")
            val eventType = enumValue<PersonEventType>(string(rule, "eventType", path), "$path.eventType")
            val idle = eventType == PersonEventType.CONVERSATION_IDLE
            val cooldown = if (!rule.has("cooldownMs")) (if (idle) EventRule.idleReminder.cooldownMs else 5000L) else {
                val value = rule.opt("cooldownMs") as? Number ?: invalid("$path.cooldownMs", "expected a nonnegative integer")
                try { BigDecimal(value.toString()).longValueExact().also { require(it >= 0) } }
                catch (_: Exception) { invalid("$path.cooldownMs", "expected a nonnegative integer within the supported range") }
            }
            if (idle && cooldown < 1000L) invalid("$path.cooldownMs", "idle wait must be at least one second")
            EventRule(
                id = id,
                name = string(rule, "name", path),
                enabled = if (!rule.has("enabled")) true else
                    rule.opt("enabled") as? Boolean ?: invalid("$path.enabled", "expected true or false"),
                eventType = eventType,
                actionType = enumValue<RuleActionType>(string(rule, "actionType", path), "$path.actionType"),
                template = string(rule, "template", path),
                cooldownMs = cooldown,
                conditions = (0 until conditions.length()).map { parseCondition(conditions.opt(it), "$path, condition ${it + 1}") }
            )
        }
    }

    private fun parseCondition(raw: Any?, path: String): RuleCondition {
        val condition = raw as? JSONObject ?: invalid(path, "expected an object")
        checkFields(condition, setOf("field", "operator", "value"), path)
        val field = string(condition, "field", path)
        val info = RuleCondition.availableFields.entries.firstOrNull { it.key.equals(field, ignoreCase = true) }?.value
            ?: invalid("$path.field", "unknown field '$field'")
        val operator = enumValue<ConditionOperator>(string(condition, "operator", path), "$path.operator")
        val value = string(condition, "value", path, allowBlank = true)
        if (operator == ConditionOperator.GREATER_THAN || operator == ConditionOperator.LESS_THAN) {
            require(info.type == FieldType.NUMBER) { "$path.operator: numeric comparison requires a numeric field." }
        }
        // CONTAINS remains a textual operation, matching the current rule engine and editor.
        if (operator != ConditionOperator.CONTAINS) when (info.type) {
            FieldType.NUMBER -> require(value.toDoubleOrNull()?.isFinite() == true) { "$path.value: expected a finite number as text." }
            FieldType.BOOLEAN -> require(value.equals("true", true) || value.equals("false", true)) { "$path.value: expected 'true' or 'false'." }
            FieldType.STRING -> Unit
        }
        return RuleCondition(field, operator, value)
    }

    private fun string(json: JSONObject, key: String, path: String, allowBlank: Boolean = false): String {
        val value = json.opt(key) as? String ?: invalid("$path.$key", "expected text")
        require(allowBlank || value.isNotBlank()) { "$path.$key: must not be blank." }
        return value
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, path: String): T =
        enumValues<T>().firstOrNull { it.name == value } ?: invalid(path, "unknown value '$value'")

    private fun checkFields(json: JSONObject, allowed: Set<String>, path: String) {
        json.keys().forEach { key -> require(key in allowed) { "$path: unknown field '$key'." } }
    }

    private fun invalid(path: String, detail: String): Nothing = throw IllegalArgumentException("$path: $detail.")

    private fun checkJson(reader: JsonReader, depth: Int) {
        require(depth < 32) { "JSON is nested too deeply." }
        when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) checkJson(reader, depth + 1)
                reader.endArray()
            }
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                val names = mutableSetOf<String>()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(names.add(name)) { "Duplicate field '$name' at ${reader.path}." }
                    checkJson(reader, depth + 1)
                }
                reader.endObject()
            }
            JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> reader.nextNull()
            else -> invalid(reader.path, "unexpected JSON token")
        }
    }
}
