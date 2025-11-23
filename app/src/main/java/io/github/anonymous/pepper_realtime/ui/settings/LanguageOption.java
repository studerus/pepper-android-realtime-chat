package io.github.anonymous.pepper_realtime.ui.settings;

/**
 * Helper class representing a language option with a display name and language
 * code.
 */
public class LanguageOption {
    private final String displayName;
    private final String code;

    public LanguageOption(String displayName, String code) {
        this.displayName = displayName;
        this.code = code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getCode() {
        return code;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
