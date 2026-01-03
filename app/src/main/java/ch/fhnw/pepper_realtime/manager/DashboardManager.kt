package ch.fhnw.pepper_realtime.manager

import ch.fhnw.pepper_realtime.data.PerceptionData
import ch.fhnw.pepper_realtime.ui.DashboardState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for perception dashboard overlay state.
 * Extracted from ChatViewModel for better separation of concerns.
 */
@Singleton
class DashboardManager @Inject constructor() {

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    // Callback for refreshing face list when dashboard opens
    private var onDashboardOpened: (() -> Unit)? = null

    /**
     * Set callback to be invoked when dashboard opens.
     * Used to trigger face list refresh.
     */
    fun setOnDashboardOpenedCallback(callback: () -> Unit) {
        onDashboardOpened = callback
    }

    fun showDashboard() {
        _state.update { it.copy(isVisible = true, isMonitoring = true) }
        onDashboardOpened?.invoke()
    }

    fun hideDashboard() {
        _state.update { it.copy(isVisible = false, isMonitoring = false) }
    }

    fun toggleDashboard() {
        val willBeVisible = !_state.value.isVisible
        _state.update { it.copy(isVisible = willBeVisible, isMonitoring = willBeVisible) }
        if (willBeVisible) {
            onDashboardOpened?.invoke()
        }
    }

    fun updateDashboardHumans(humans: List<PerceptionData.HumanInfo>, timestamp: String) {
        _state.update { it.copy(humans = humans, lastUpdate = timestamp) }
    }

    fun resetDashboard() {
        _state.value = DashboardState()
    }
}
