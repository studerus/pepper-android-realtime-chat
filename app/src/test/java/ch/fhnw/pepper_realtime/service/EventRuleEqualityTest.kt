package ch.fhnw.pepper_realtime.service

import ch.fhnw.pepper_realtime.data.*
import ch.fhnw.pepper_realtime.data.ConditionOperator.EQUALS
import ch.fhnw.pepper_realtime.data.ConditionOperator.NOT_EQUALS
import org.junit.Assert.*
import org.junit.Test

class EventRuleEqualityTest {
    private val human = PerceptionData.HumanInfo().apply { recognizedName = "01" }
    private fun matches(field: String, operator: ConditionOperator, value: String): Boolean {
        val event = PersonEvent(PersonEventType.PERSON_APPEARED)
        val engine = EventRuleEngine()
        engine.addRule(EventRule(name = "Test", eventType = event.type,
            conditions = listOf(RuleCondition(field, operator, value)),
            actionType = RuleActionType.SILENT_UPDATE, template = "matched"))
        return engine.evaluate(event, human, listOf(human)) != null
    }

    @Test fun `numeric equality ignores formatting but not differences`() {
        assertTrue(matches("peopleCount", EQUALS, "1.0"))
        assertFalse(matches("peopleCount", NOT_EQUALS, "1.0"))
        assertTrue(matches("peopleCount", NOT_EQUALS, "1.1"))
    }

    @Test fun `unknown distance and invalid numbers match neither operator`() {
        for (operator in listOf(EQUALS, NOT_EQUALS)) {
            assertFalse(matches("distance", operator, "1"))
            assertFalse(matches("peopleCount", operator, "invalid"))
        }
    }

    @Test fun `names are compared as text`() {
        assertFalse(matches("personName", EQUALS, "1"))
        assertTrue(matches("personName", NOT_EQUALS, "1"))
        human.recognizedName = "Alex"
        assertTrue(matches("personName", EQUALS, "alex"))
    }
}
