package io.github.anonymous.pepper_realtime;

import android.util.Log;

import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.object.power.FlapSensor;
import com.aldebaran.qi.sdk.object.power.FlapState;
import com.aldebaran.qi.sdk.object.power.Power;

/**
 * Centralizes pre-movement safety checks (charging flap, QiContext availability, etc.).
 */
public final class RobotSafetyGuard {

    public enum SafetyStatus {
        OK,
        ROBOT_NOT_READY,
        CHARGING_FLAP_OPEN,
        UNKNOWN
    }

    public static class Result {
        public final SafetyStatus status;
        public final String message;

        private Result(SafetyStatus status, String message) {
            this.status = status;
            this.message = message;
        }

        public static Result ok() {
            return new Result(SafetyStatus.OK, null);
        }

        public static Result robotNotReady() {
            return new Result(SafetyStatus.ROBOT_NOT_READY, "Robot not ready");
        }

        public static Result flapOpen() {
            return new Result(SafetyStatus.CHARGING_FLAP_OPEN,
                    "Cannot move while charging flap is open. Please close it for safety.");
        }

        public static Result unknown(String message) {
            return new Result(SafetyStatus.UNKNOWN, message);
        }

        public boolean isOk() {
            return status == SafetyStatus.OK;
        }
    }

    private static final String TAG = "RobotSafetyGuard";

    private RobotSafetyGuard() {
        // Utility class
    }

    public static Result evaluateMovementSafety(QiContext qiContext) {
        if (qiContext == null) {
            return Result.robotNotReady();
        }

        try {
            Power power = qiContext.getPower();
            if (power != null) {
                FlapSensor flapSensor = power.getChargingFlap();
                if (flapSensor != null) {
                    FlapState flapState = flapSensor.getState();
                    if (flapState != null && Boolean.TRUE.equals(flapState.getOpen())) {
                        Log.d(TAG, "Charging flap is open - movement blocked");
                        return Result.flapOpen();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to check charging flap status", e);
            return Result.unknown("Safety check failed: " + e.getMessage());
        }

        return Result.ok();
    }
}
