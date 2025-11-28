package io.github.anonymous.pepper_realtime.manager

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.anonymous.pepper_realtime.data.MapGraphInfo
import io.github.anonymous.pepper_realtime.data.SavedLocation

/**
 * Data class representing the UI state for navigation and mapping.
 */
data class NavigationUiState(
    val isVisible: Boolean = false,
    val mapStatus: String = "No Map",
    val localizationStatus: String = "Waiting for localization...",
    val mapState: MapState = MapState.NO_MAP,
    val mapBitmap: Bitmap? = null,
    val mapGfx: MapGraphInfo? = null, // Decoupled from QiSDK
    val savedLocations: List<SavedLocation> = emptyList()
)

/**
 * Enum for the internal state of the map system
 */
enum class MapState {
    NO_MAP,
    MAP_LOADED_NOT_LOCALIZED,
    LOCALIZING,
    LOCALIZATION_FAILED,
    LOCALIZED
}

/**
 * Singleton manager for the Navigation UI state.
 * Replaces the old class-based MapUiManager that held View references.
 */
object MapUiManager {
    
    var state by mutableStateOf(NavigationUiState())
        private set

    fun show() {
        state = state.copy(isVisible = true)
    }

    fun hide() {
        state = state.copy(isVisible = false)
    }

    fun toggle() {
        state = state.copy(isVisible = !state.isVisible)
    }

    fun updateMapStatus(status: String) {
        state = state.copy(mapStatus = status)
    }

    fun updateLocalizationStatus(status: String) {
        state = state.copy(localizationStatus = status)
    }

    fun updateMapData(
        mapState: MapState,
        mapBitmap: Bitmap?,
        mapGfx: MapGraphInfo?,
        locations: List<SavedLocation>
    ) {
        state = state.copy(
            mapState = mapState,
            mapBitmap = mapBitmap,
            mapGfx = mapGfx,
            savedLocations = locations
        )
    }
}
