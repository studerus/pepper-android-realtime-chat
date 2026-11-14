package ch.fhnw.pepper_realtime.robot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MotionValidationTest {
    @Test fun `move accepts boundaries and omitted optional defaults`() {
        MotionValidation.move(-4.0, 4.0, 0.1)
        MotionValidation.move(4.0, -4.0, 0.55)
        val args = JSONObject().put("distance_forward", 1)
        MotionValidation.move(MotionValidation.number(args, "distance_forward"),
            MotionValidation.number(args, "distance_sideways", 0.0), MotionValidation.number(args, "speed", 0.4))
    }

    @Test fun `move rejects invalid distances and zero movement`() {
        for (value in listOf(-4.001, 4.001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { MotionValidation.move(value, 0.0, 0.4) }
            assertThrows(IllegalArgumentException::class.java) { MotionValidation.move(1.0, value, 0.4) }
        }
        assertThrows(IllegalArgumentException::class.java) { MotionValidation.move(0.0, 0.0, 0.4) }
    }

    @Test fun `move and turn reject invalid speeds instead of substituting defaults`() {
        for (speed in listOf(0.0, 0.099, 0.551, 1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { MotionValidation.move(1.0, 0.0, speed) }
            assertThrows(IllegalArgumentException::class.java) { MotionValidation.turn("left", 90.0, speed) }
        }
    }

    @Test fun `numeric parser rejects missing required fields and malformed optional fields`() {
        assertThrows(IllegalArgumentException::class.java) { MotionValidation.number(JSONObject(), "degrees") }
        for (value in listOf("NaN", "Infinity", "0.4", "fast", true, JSONObject.NULL, JSONObject())) {
            assertThrows(IllegalArgumentException::class.java) {
                MotionValidation.number(JSONObject().put("speed", value), "speed", 0.4)
            }
        }
    }

    @Test fun `turn validates angle and direction`() {
        MotionValidation.turn("left", 15.0, 0.1)
        MotionValidation.turn("right", 180.0, 0.55)
        for (degrees in listOf(0.0, 14.9, 180.1, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { MotionValidation.turn("left", degrees, 0.5) }
        }
        assertThrows(IllegalArgumentException::class.java) { MotionValidation.turn("backwards", 90.0, 0.5) }
    }

    @Test fun `look validates coordinates policy and duration`() {
        MotionValidation.look(-5.0, 5.0, -2.0, "whole_body", 15.0)
        MotionValidation.look(0.0, 0.0, 1.2, "head_only", 1.0)
        for (x in listOf(-5.01, 5.01, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { MotionValidation.look(x, 0.0, 1.2, "head_only", 3.0) }
        }
        assertThrows(IllegalArgumentException::class.java) { MotionValidation.look(1.0, 6.0, 1.2, "head_only", 3.0) }
        assertThrows(IllegalArgumentException::class.java) { MotionValidation.look(1.0, 0.0, -3.0, "head_only", 3.0) }
        assertThrows(IllegalArgumentException::class.java) { MotionValidation.look(0.0, 0.0, 0.0, "head_only", 3.0) }
        assertThrows(IllegalArgumentException::class.java) { MotionValidation.look(1.0, 0.0, 1.2, "typo", 3.0) }
        for (duration in listOf(0.0, 15.01, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { MotionValidation.look(1.0, 0.0, 1.2, "head_only", duration) }
        }
    }
}
