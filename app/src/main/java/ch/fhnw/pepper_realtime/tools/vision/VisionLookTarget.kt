package ch.fhnw.pepper_realtime.tools.vision

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.sqrt

data class VisionLookTarget(val x: Double, val y: Double, val z: Double) {
    companion object {
        fun parse(args: JSONObject): VisionLookTarget? {
            if (!args.has("look_at")) return null
            val target = args.optJSONObject("look_at")
                ?: throw IllegalArgumentException("look_at must be an object with numeric x, y, z coordinates")
            fun coordinate(name: String, min: Double, max: Double): Double {
                val raw = target.opt(name)
                require(raw is Number) { "look_at.$name must be a number" }
                return raw.toDouble().also { require(it.isFinite() && it in min..max) { "look_at.$name is out of range" } }
            }
            val result = VisionLookTarget(coordinate("x", -5.0, 5.0), coordinate("y", -5.0, 5.0), coordinate("z", -2.0, 5.0))
            require(result.x != 0.0 || result.y != 0.0 || result.z != 0.0) { "look_at must not be the robot base origin" }
            return result
        }

        fun schema() = JSONObject().apply {
            put("type", "object")
            put("description", "Optional head-only gaze target in meters relative to the robot base. X forward, Y robot-left positive / robot-right negative, Z height above ground. For directional vision use this argument, NOT a separate look_at_position call. Left: x=0,y=1,z=1.2; right: x=0,y=-1,z=1.2. Omit to photograph the current view.")
            put("properties", JSONObject().apply {
                for (axis in listOf("x", "y", "z")) put(axis, JSONObject().put("type", "number").put("minimum", if (axis == "z") -2 else -5).put("maximum", 5))
            })
            put("required", JSONArray(listOf("x", "y", "z")))
            // Shared with Gemini Live's Schema, which rejects additionalProperties.
        }

        fun isAligned(x: Double, y: Double, z: Double): Boolean =
            x.isFinite() && y.isFinite() && z.isFinite() && x > 0 &&
                atan2(sqrt(y * y + z * z), x) <= Math.toRadians(8.0)
    }
}
