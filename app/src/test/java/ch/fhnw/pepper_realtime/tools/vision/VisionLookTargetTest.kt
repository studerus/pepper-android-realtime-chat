package ch.fhnw.pepper_realtime.tools.vision

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VisionLookTargetTest {
    @Test fun `omitted target keeps current view`() {
        assertNull(VisionLookTarget.parse(JSONObject()))
    }

    @Test fun `left and right retain robot relative sign`() {
        for (y in listOf(-1.0, 1.0)) {
            val target = VisionLookTarget.parse(JSONObject().put("look_at", JSONObject().put("x", 0).put("y", y).put("z", 1.2)))!!
            assertEquals(y, target.y, 0.0)
        }
    }

    @Test fun `invalid targets are rejected instead of taking forward photo`() {
        for (json in listOf("null", "[]", "{}", "{\"x\":0,\"y\":1}",
            "{\"x\":\"0\",\"y\":1,\"z\":1.2}", "{\"x\":0,\"y\":0,\"z\":0}",
            "{\"x\":0,\"y\":6,\"z\":1.2}")) {
            try {
                VisionLookTarget.parse(JSONObject("{\"look_at\":$json}"))
                fail("Accepted invalid target: $json")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun `alignment requires target near positive gaze axis`() {
        assertTrue(VisionLookTarget.isAligned(1.0, 0.0, 0.0))
        assertTrue(VisionLookTarget.isAligned(2.0, 0.1, 0.1))
        assertFalse(VisionLookTarget.isAligned(0.0, 1.0, 0.0))
        assertFalse(VisionLookTarget.isAligned(-1.0, 0.0, 0.0))
        assertFalse(VisionLookTarget.isAligned(1.0, 0.2, 0.0))
        assertFalse(VisionLookTarget.isAligned(0.0, 0.0, 0.0))
        assertFalse(VisionLookTarget.isAligned(Double.NaN, 0.0, 0.0))
    }

    @Test fun `schema requires all coordinates`() {
        val schema = VisionLookTarget.schema()
        assertEquals("object", schema.getString("type"))
        assertEquals(3, schema.getJSONArray("required").length())
        assertEquals(-5, schema.getJSONObject("properties").getJSONObject("y").getInt("minimum"))
    }

    @Test fun `schema omits additionalProperties rejected by Gemini Live`() {
        fun checkSchema(schema: JSONObject) {
            assertFalse(schema.has("additionalProperties"))
            val properties = schema.optJSONObject("properties") ?: return
            for (name in properties.keys()) checkSchema(properties.getJSONObject(name))
        }
        checkSchema(VisionLookTarget.schema())
    }
}
