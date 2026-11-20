package ch.fhnw.pepper_realtime.controller

import ch.fhnw.pepper_realtime.data.EventRule
import ch.fhnw.pepper_realtime.service.EventRuleEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationIdleTimerTest {
    @Test fun `idle message reports elapsed seconds without prescribing a reply`() {
        val engine = EventRuleEngine()
        engine.loadRules(listOf(EventRule.idleReminder.copy(cooldownMs = 30_000)))
        assertEquals("There has been no user input for 31 seconds.", engine.evaluateIdle(31_750)?.resolvedTemplate)
    }

    @Test fun `quiet listening repeats and real input or ineligible periods restart the wait`() {
        val engine = EventRuleEngine().apply { loadRules(EventRule.defaultRules) }
        val timer = ConversationIdleTimer()
        val fired = mutableListOf<Long>()
        fun tick(time: Long, listening: Boolean = true, lastInput: Long = 0L) {
            timer.tick(time, listening, lastInput) { quiet ->
                (engine.evaluateIdle(quiet) != null).also { if (it) fired.add(time) }
            }
        }
        tick(0); tick(89_999); tick(90_000); tick(179_999); tick(180_000)
        tick(269_999, lastInput = 260_000); tick(349_999); tick(350_000)
        tick(439_999, listening = false); tick(540_000, listening = false)
        tick(550_000); tick(639_999); tick(640_000)
        engine.updateRule(EventRule.idleReminder.copy(enabled = false))
        tick(730_000)
        assertEquals(listOf(90_000L, 180_000L, 350_000L, 640_000L), fired)
    }
}
