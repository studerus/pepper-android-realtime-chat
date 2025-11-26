package io.github.anonymous.pepper_realtime.robot

import android.util.Log
import com.aldebaran.qi.sdk.QiContext

/**
 * Centralizes pre-movement safety checks (charging flap, QiContext availability, etc.).
 */
object RobotSafetyGuard {

    enum class SafetyStatus {
        OK,
        ROBOT_NOT_READY,
        CHARGING_FLAP_OPEN,
        UNKNOWN
    }

    class Result private constructor(
        @JvmField val status: SafetyStatus,
        @JvmField val message: String?
    ) {
        fun isOk(): Boolean = status == SafetyStatus.OK

        companion object {
            @JvmStatic
            fun ok(): Result = Result(SafetyStatus.OK, null)

            @JvmStatic
            fun robotNotReady(): Result = Result(SafetyStatus.ROBOT_NOT_READY, "Robot not ready")

            @JvmStatic
            fun flapOpen(): Result = Result(
                SafetyStatus.CHARGING_FLAP_OPEN,
                "Cannot move while charging flap is open. Please close it for safety."
            )

            @JvmStatic
            fun unknown(message: String?): Result = Result(SafetyStatus.UNKNOWN, message)
        }
    }

    private const val TAG = "RobotSafetyGuard"

    @JvmStatic
    fun evaluateMovementSafety(qiContext: QiContext?): Result {
        if (qiContext == null) {
            return Result.robotNotReady()
        }

        return try {
            val power = qiContext.power
            if (power != null) {
                val flapSensor = power.chargingFlap
                if (flapSensor != null) {
                    val flapState = flapSensor.state
                    if (flapState?.open == true) {
                        Log.d(TAG, "Charging flap is open - movement blocked")
                        return Result.flapOpen()
                    }
                }
            }
            Result.ok()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to check charging flap status", e)
            Result.unknown("Safety check failed: ${e.message}")
        }
    }
}

