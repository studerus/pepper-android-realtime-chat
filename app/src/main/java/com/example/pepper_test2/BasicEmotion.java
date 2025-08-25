package com.example.pepper_test2;

/**
 * Represents a basic emotion derived from excitement and pleasure states.
 * Based on James Russell's emotion model as implemented in QiSDK.
 */
public enum BasicEmotion {
    UNKNOWN("❓", "Unknown"),
    NEUTRAL("😐", "Neutral"),
    CONTENT("😊", "Content"),
    JOYFUL("😄", "Joyful"),
    SAD("😢", "Sad"),
    ANGRY("😠", "Angry");

    private final String emoji;
    private final String displayName;

    BasicEmotion(String emoji, String displayName) {
        this.emoji = emoji;
        this.displayName = displayName;
    }

    /**
     * Get formatted string for dashboard display
     */
    public String getFormattedDisplay() {
        return emoji + " " + displayName;
    }
}

