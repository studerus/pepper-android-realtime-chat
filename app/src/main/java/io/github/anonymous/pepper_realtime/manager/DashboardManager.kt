package io.github.anonymous.pepper_realtime.manager

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.anonymous.pepper_realtime.data.PerceptionData
import io.github.anonymous.pepper_realtime.service.PerceptionService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages the perception dashboard overlay UI and coordinates data from various services.
 * Now uses Jetpack Compose State.
 */
object DashboardManager {
    private const val TAG = "DashboardManager"
    private val uiHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // State
    data class DashboardState(
        val isVisible: Boolean = false,
        val humans: List<PerceptionData.HumanInfo> = emptyList(),
        val lastUpdate: String = "",
        val isMonitoring: Boolean = false
    )

    var state by mutableStateOf(DashboardState())

    // Service references
    private var perceptionService: PerceptionService? = null

    /**
     * Initialize dashboard with perception service
     */
    fun initialize(perceptionService: PerceptionService?) {
        this.perceptionService = perceptionService

        perceptionService?.setListener(object : PerceptionService.PerceptionListener {
            override fun onHumansDetected(humans: List<PerceptionData.HumanInfo>) {
                val timeString = timeFormat.format(Date())
                uiHandler.post {
                    state = state.copy(humans = humans, lastUpdate = timeString)
                }
            }

            override fun onPerceptionError(error: String) {
                Log.w(TAG, "Perception error: $error")
            }

            override fun onServiceStatusChanged(isActive: Boolean) {
                Log.i(TAG, "Human awareness service active: $isActive")
            }
        })

        Log.i(TAG, "Dashboard initialized")
    }

    /**
     * Show the dashboard overlay
     */
    fun showDashboard() {
        state = state.copy(isVisible = true, isMonitoring = true)
        perceptionService?.let {
            if (it.isInitialized) {
                it.startMonitoring()
            }
        }
        Log.i(TAG, "Dashboard shown")
    }

    /**
     * Hide the dashboard overlay
     */
    fun hideDashboard() {
        state = state.copy(isVisible = false, isMonitoring = false)
        perceptionService?.stopMonitoring()
        Log.i(TAG, "Dashboard hidden")
    }

    /**
     * Toggle dashboard visibility
     */
    fun toggleDashboard() {
        if (state.isVisible) {
            hideDashboard()
        } else {
            showDashboard()
        }
    }

    /**
     * Clean up resources
     */
    fun shutdown() {
        perceptionService?.stopMonitoring()
        state = DashboardState() // Reset state
        Log.i(TAG, "Dashboard manager shutdown")
    }
}
