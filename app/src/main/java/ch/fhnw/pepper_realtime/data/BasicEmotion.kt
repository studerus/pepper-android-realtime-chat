package ch.fhnw.pepper_realtime.data

/**
 * Represents a basic emotion derived from excitement and pleasure states.
 * Based on James Russell's emotion model as implemented in QiSDK.
 */
enum class BasicEmotion(private val emoji: String, private val displayName: String) {
    UNKNOWN("❓", "Unknown"),
    NEUTRAL("😐", "Neutral"),
    CONTENT("😊", "Content"),
    JOYFUL("😄", "Joyful"),
    SAD("😢", "Sad"),
    ANGRY("😠", "Angry");

    /**
     * Get formatted string for dashboard display
     */
    fun getFormattedDisplay(): String {
        return "$emoji $displayName"
    }
}

