package ch.fhnw.pepper_realtime.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HeadDirectionTest {
    @Test fun `numeric and legacy directions reach the dashboard with the same sign`() {
        for ((value, expected) in listOf(-0.42 to -0.42f, 0.65 to 0.65f, 0 to 0f,
            "left" to 1f, "right" to -1f, "looking" to 0f, "center" to 0f, "NaN" to 0f, JSONObject.NULL to 0f)) {
            val human = PerceptionData.HumanInfo().apply {
                headDirection = JSONObject().put("head_direction", value).readHeadDirection()
            }
            assertEquals(expected, human.headDirection, 0.0001f)
            assertEquals(when { expected < 0 -> "→ Away"; expected > 0 -> "← Away"; else -> "↔ Away" }, human.getGazeDisplay())
        }
        assertEquals(0f, JSONObject().readHeadDirection(), 0f)
    }
}
