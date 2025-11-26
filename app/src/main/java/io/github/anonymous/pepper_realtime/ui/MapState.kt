package io.github.anonymous.pepper_realtime.ui

/**
 * Represents the current state of map localization
 */
enum class MapState {
    NO_MAP,
    MAP_LOADED_NOT_LOCALIZED,
    LOCALIZING,
    LOCALIZED,
    LOCALIZATION_FAILED
}

