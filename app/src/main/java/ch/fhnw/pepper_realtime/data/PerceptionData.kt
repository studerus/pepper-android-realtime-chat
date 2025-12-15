package ch.fhnw.pepper_realtime.data

import android.graphics.Bitmap
import java.util.Locale

/**
 * Data structures for perception information from Pepper's sensors
 */
class PerceptionData {

    /**
     * Information about a detected human
     */
    class HumanInfo {
        var id: Int = 0
        var estimatedAge: Int = -1
        var gender: String = "Unknown"
        var pleasureState: String = "Unknown"
        var excitementState: String = "Unknown"
        var engagementState: String = "Unknown"
        var attentionState: String = "Unknown"
        var smileState: String = "Unknown"
        var distanceMeters: Double = -1.0
        var basicEmotion: BasicEmotion = BasicEmotion.UNKNOWN
        var facePicture: Bitmap? = null // Face picture from QiSDK

        // --- Position relative to robot (for bird's eye view) ---
        var positionX: Double = 0.0  // Meters in front of robot (positive = in front)
        var positionY: Double = 0.0  // Meters to the side (positive = left of robot)

        // --- Data from local face recognition ---
        var recognizedName: String? = null // Name from local face recognition on Pepper's head

        /**
         * Get attention level for UI display
         */
        fun getAttentionLevel(): String {
            return when (attentionState.uppercase(Locale.US)) {
                "LOOKING_AT_ROBOT" -> "Looking at Robot"
                "LOOKING_ELSEWHERE" -> "Looking Elsewhere"
                "LOOKING_UP" -> "Looking Up"
                "LOOKING_DOWN" -> "Looking Down"
                "LOOKING_LEFT" -> "Looking Left"
                "LOOKING_RIGHT" -> "Looking Right"
                "UNKNOWN" -> "Unknown"
                else -> capitalize(attentionState.replace("_", " "))
            }
        }

        /**
         * Get engagement level for UI display
         */
        fun getEngagementLevel(): String {
            return when (engagementState.uppercase(Locale.US)) {
                "INTERESTED" -> "Interested"
                "NOT_INTERESTED" -> "Not Interested"
                "SEEKING_ENGAGEMENT" -> "Seeking Engagement"
                "UNKNOWN" -> "Unknown"
                else -> capitalize(engagementState.replace("_", " "))
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

        /**
         * Get age and gender summary
         */
        fun getDemographics(): String {
            val demo = StringBuilder()

            if (estimatedAge > 0) {
                demo.append(estimatedAge).append("y")
            }

            if (gender != "Unknown") {
                if (demo.isNotEmpty()) demo.append(", ")
                when (gender.uppercase(Locale.US)) {
                    "MALE" -> demo.append("♂️")
                    "FEMALE" -> demo.append("♀️")
                    else -> demo.append(capitalize(gender))
                }
            }

            return if (demo.isNotEmpty()) demo.toString() else "Unknown"
        }

        /**
         * Get basic emotion for display
         */
        fun getBasicEmotionDisplay(): String {
            return basicEmotion.getFormattedDisplay()
        }

        /**
         * Get smile state for UI display
         */
        fun getSmileStateDisplay(): String {
            return when (smileState.uppercase(Locale.US)) {
                "GENUINE" -> "😊 Genuine"
                "FAKE" -> "😏 Fake"
                "NOT_SMILING" -> "😐 Not Smiling"
                "UNKNOWN" -> "❓ Unknown"
                else -> "❓ ${capitalize(smileState.replace("_", " "))}"
            }
        }

        /**
         * Get pleasure state for UI display with emojis
         */
        fun getPleasureStateDisplay(): String {
            return when (pleasureState.uppercase(Locale.US)) {
                "POSITIVE" -> "😊 Positive"
                "NEGATIVE" -> "😔 Negative"
                "NEUTRAL" -> "😐 Neutral"
                "UNKNOWN" -> "❓ Unknown"
                else -> "❓ ${capitalize(pleasureState.replace("_", " "))}"
            }
        }

        /**
         * Get excitement state for UI display with emojis
         */
        fun getExcitementStateDisplay(): String {
            return when (excitementState.uppercase(Locale.US)) {
                "EXCITED" -> "⚡ Excited"
                "CALM" -> "😌 Calm"
                "UNKNOWN" -> "❓ Unknown"
                else -> "❓ ${capitalize(excitementState.replace("_", " "))}"
            }
        }

        /**
         * Capitalize first letter of each word
         */
        private fun capitalize(input: String?): String {
            if (input.isNullOrEmpty()) {
                return input ?: ""
            }

            return input.lowercase(Locale.US)
                .split("\\s+".toRegex())
                .joinToString(" ") { word ->
                    word.replaceFirstChar { 
                        if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() 
                    }
                }
        }

        companion object {
            /**
             * Compute basic emotion from excitement and pleasure states
             * Based on James Russell's emotion model as used in QiSDK
             */
            fun computeBasicEmotion(excitementState: String, pleasureState: String): BasicEmotion {
                if (excitementState.equals("UNKNOWN", ignoreCase = true) || 
                    pleasureState.equals("UNKNOWN", ignoreCase = true)) {
                    return BasicEmotion.UNKNOWN
                }

                if (pleasureState.equals("NEUTRAL", ignoreCase = true)) {
                    return BasicEmotion.NEUTRAL
                }

                if (pleasureState.equals("POSITIVE", ignoreCase = true)) {
                    return if (excitementState.equals("CALM", ignoreCase = true)) {
                        BasicEmotion.CONTENT
                    } else {
                        BasicEmotion.JOYFUL
                    }
                }

                if (pleasureState.equals("NEGATIVE", ignoreCase = true)) {
                    return if (excitementState.equals("CALM", ignoreCase = true)) {
                        BasicEmotion.SAD
                    } else {
                        BasicEmotion.ANGRY
                    }
                }

                return BasicEmotion.NEUTRAL
            }
        }
    }

    // Snapshot and status classes removed for now; can be reintroduced when dashboard requires them
}


