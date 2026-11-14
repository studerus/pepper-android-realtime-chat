package ch.fhnw.pepper_realtime.robot

import org.json.JSONObject

/** Runtime checks for robot commands; tool schemas alone do not enforce these limits. */
object MotionValidation {
    fun number(args: JSONObject, name: String, default: Double? = null): Double {
        if (!args.has(name) && default != null) return default
        val value = args.opt(name)
        require(value is Number) { "$name must be a number" }
        return value.toDouble().also { require(it.isFinite()) { "$name must be finite" } }
    }

    fun range(name: String, value: Double, min: Double, max: Double) {
        require(value.isFinite() && value in min..max) { "$name must be between $min and $max" }
    }

    fun speed(speed: Double) = range("Speed (m/s)", speed, 0.1, 0.55)

    fun move(forward: Double, sideways: Double, speed: Double) {
        range("distance_forward", forward, -4.0, 4.0)
        range("distance_sideways", sideways, -4.0, 4.0)
        require(forward != 0.0 || sideways != 0.0) { "At least one movement distance must be non-zero" }
        this.speed(speed)
    }

    fun turn(direction: String, degrees: Double, speed: Double) {
        require(direction == "left" || direction == "right") { "Direction must be left or right" }
        range("Degrees", degrees, 15.0, 180.0)
        this.speed(speed)
    }

    fun look(x: Double, y: Double, z: Double, policy: String, duration: Double) {
        range("x", x, -5.0, 5.0)
        range("y", y, -5.0, 5.0)
        range("z", z, -2.0, 5.0)
        require(x != 0.0 || y != 0.0 || z != 0.0) { "Look target must not be the robot base origin" }
        require(policy == "head_only" || policy == "whole_body") { "Movement policy must be head_only or whole_body" }
        range("Duration (seconds)", duration, 1.0, 15.0)
    }
}
