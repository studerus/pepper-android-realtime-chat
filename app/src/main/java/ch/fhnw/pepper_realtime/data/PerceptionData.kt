package ch.fhnw.pepper_realtime.data

import java.util.Locale

/**
 * Data structures for perception information from Pepper's sensors
 */
class PerceptionData {

    /**
     * Information about a detected human
     */
    class HumanInfo {
        var id: Int = 0                     // Human ID (for Pepper QiSDK compatibility)
        var estimatedAge: Int = -1
        var gender: String = "Unknown"
        var pleasureState: String = "Unknown"
        var excitementState: String = "Unknown"
        var engagementState: String = "Unknown"
        var attentionState: String = "Unknown"
        var smileState: String = "Unknown"
        var distanceMeters: Double = -1.0

        // --- Position relative to robot (for bird's eye view) ---
        var positionX: Double = 0.0  // Meters in front of robot (positive = in front)
        var positionY: Double = 0.0  // Meters to the side (positive = left of robot)

        // --- Data from local face recognition ---
        var recognizedName: String? = null // Name from local face recognition on Pepper's head

        // --- Head-Based Perception System data ---
        var trackId: Int = -1               // Stable track ID from head-based tracker
        var lookingAtRobot: Boolean = false // Is person looking at the robot?
        var headDirection: Float = 0f       // Head direction: -1=right, 0=center, +1=left
        var worldYaw: Float = 0f            // Position angle relative to torso (degrees)
        var worldPitch: Float = 0f          // Vertical position angle (degrees)
        var lastSeenMs: Long = 0L           // Timestamp when last seen (milliseconds)
        var trackAgeMs: Long = 0L           // How long this person has been tracked (milliseconds)
        var gazeDurationMs: Long = 0L       // How long this person has been looking at robot (milliseconds, 0 if not looking)

        /**
         * Get gaze indicator for UI display
         */
        fun getGazeDisplay(): String {
            return if (lookingAtRobot) {
                val durationStr = when {
                    gazeDurationMs < 1000 -> ""
                    gazeDurationMs < 60000 -> " (${gazeDurationMs / 1000}s)"
                    else -> " (${gazeDurationMs / 60000}m)"
                }
                "👀 Looking$durationStr"
            } else {
                when {
                    headDirection > 0.2f -> "← Away"
                    headDirection < -0.2f -> "→ Away"
                    else -> "↔ Away"
                }
            }
        }

        /**
         * Get position description for UI display
         */
        fun getPositionDisplay(): String {
            return when {
                worldYaw > 15f -> "Left (${worldYaw.toInt()}°)"
                worldYaw < -15f -> "Right (${(-worldYaw).toInt()}°)"
                else -> "Front"
            }
        }

        /**
         * Get tracking duration for UI display.
         * Shows how long this person has been tracked.
         */
        fun getTrackingDurationDisplay(): String {
            if (trackAgeMs <= 0L) return "—"
            return when {
                trackAgeMs < 1000 -> "<1s"
                trackAgeMs < 60000 -> "${trackAgeMs / 1000}s"
                trackAgeMs < 3600000 -> "${trackAgeMs / 60000}m ${(trackAgeMs % 60000) / 1000}s"
                else -> "${trackAgeMs / 3600000}h ${(trackAgeMs % 3600000) / 60000}m"
            }
        }

        /**
         * Get formatted distance string
         */
        fun getDistanceString(): String {
            return when {
                distanceMeters < 0 -> "Unknown"
                distanceMeters < 1.0 -> String.format(Locale.US, "%.0fcm", distanceMeters * 100)
                else -> String.format(Locale.US, "%.1fm", distanceMeters)
            }
        }
    }
}
