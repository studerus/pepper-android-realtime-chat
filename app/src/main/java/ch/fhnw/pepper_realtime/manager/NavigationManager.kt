package ch.fhnw.pepper_realtime.manager

import android.graphics.Bitmap
import ch.fhnw.pepper_realtime.data.MapGraphInfo
import ch.fhnw.pepper_realtime.data.SavedLocation
import ch.fhnw.pepper_realtime.ui.NavigationUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for navigation and map overlay state.
 * Extracted from ChatViewModel for better separation of concerns.
 */
@Singleton
class NavigationManager @Inject constructor() {

    private val _state = MutableStateFlow(NavigationUiState())
    val state: StateFlow<NavigationUiState> = _state.asStateFlow()

    fun setLocalizationStatus(status: String) {
        _state.update { it.copy(localizationStatus = status) }
    }

    fun showNavigationOverlay() {
        _state.update { it.copy(isVisible = true) }
    }

    fun hideNavigationOverlay() {
        _state.update { it.copy(isVisible = false) }
    }

    fun toggleNavigationOverlay() {
        _state.update { it.copy(isVisible = !it.isVisible) }
    }

    fun updateMapData(
        hasMapOnDisk: Boolean,
        mapBitmap: Bitmap?,
        mapGfx: MapGraphInfo?,
        locations: List<SavedLocation>
    ) {
        _state.update {
            it.copy(
                hasMapOnDisk = hasMapOnDisk,
                mapBitmap = mapBitmap,
                mapGfx = mapGfx,
                savedLocations = locations
            )
        }
    }
}
