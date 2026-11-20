package ch.fhnw.pepper_realtime.service

import ch.fhnw.pepper_realtime.data.*
import org.junit.Assert.*
import org.junit.Test

class PersonEventDetectorTest {
    private val detector = PersonEventDetector()
    private val engine = EventRuleEngine()
    private val matches = mutableListOf<MatchedRule>()
    private val listener = object : PerceptionService.EventListener {
        override fun onPersonEvent(event: PersonEvent, humanInfo: PerceptionData.HumanInfo, allHumans: List<PerceptionData.HumanInfo>) {
            val previous = event.previousGazeDurationMs
            if (previous == null || engine.hasGazeThresholdCrossing(previous, humanInfo.gazeDurationMs)) {
                engine.evaluate(event, humanInfo, allHumans)?.let(matches::add)
            }
        }
    }
    private fun frame(vararg people: PerceptionData.HumanInfo) = detector.detectEvents(people.toList(), listener)
    private fun person(id: Int, gaze: Long, name: String? = null, distance: Double = 2.0) =
        PerceptionData.HumanInfo().apply {
            trackId = id; recognizedName = name; distanceMeters = distance
            lookingAtRobot = gaze >= 0; gazeDurationMs = gaze.coerceAtLeast(0)
        }
    private fun rule(name: String, type: PersonEventType, vararg conditions: RuleCondition) =
        EventRule(name = name, eventType = type, actionType = RuleActionType.SILENT_UPDATE,
            template = "{personName} {distance} {peopleCount}", conditions = conditions.toList(), cooldownMs = 0)

    @Test fun `gaze onset and duration thresholds fire once per track and restart after looking away`() {
        val looking = PersonEventType.PERSON_LOOKING
        engine.loadRules(listOf(rule("onset", looking),
            rule("three", looking, RuleCondition("gazeDuration", ConditionOperator.GREATER_THAN, "3000")),
            rule("five", looking, RuleCondition("gazeDuration", ConditionOperator.GREATER_THAN, "5000"))))
        frame(person(1, 0))
        frame(person(1, 3000), person(2, 0))
        frame(person(1, 3200), person(2, 3000))
        frame(person(1, 4700), person(2, 3200))
        frame(person(1, 5400))
        frame(person(1, -1))
        frame(person(1, 50))
        frame(person(1, 3300))
        assertEquals(listOf("onset:1", "onset:2", "three:1", "three:2", "five:1", "onset:1", "three:1"),
            matches.map { "${it.rule.name}:${it.event.humanId}" })
    }

    @Test fun `farewell uses latest departing observation but current people count`() {
        engine.loadRules(listOf(rule("farewell", PersonEventType.PERSON_DISAPPEARED,
            RuleCondition("personName", ConditionOperator.EQUALS, "Alex"),
            RuleCondition("distance", ConditionOperator.LESS_THAN, "2"))))
        frame(person(1, -1))
        frame(person(1, -1, "Alex", 1.5), person(2, -1))
        frame(person(2, -1))
        frame()
        assertEquals("Alex 1.5m 1", matches.single().resolvedTemplate)
    }
}
