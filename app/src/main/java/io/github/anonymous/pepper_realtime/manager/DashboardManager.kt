package io.github.anonymous.pepper_realtime.manager

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.anonymous.pepper_realtime.R
import io.github.anonymous.pepper_realtime.data.PerceptionData
import io.github.anonymous.pepper_realtime.service.PerceptionService
import io.github.anonymous.pepper_realtime.ui.HumanDetectionAdapter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages the perception dashboard overlay UI and coordinates data from various services
 */
class DashboardManager(
    private val context: Context,
    private val dashboardOverlay: View
) {
    private val uiHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // UI Components
    private lateinit var humanCountBadge: TextView
    private lateinit var humansRecyclerView: RecyclerView
    private lateinit var noHumansText: TextView
    private lateinit var lastUpdateText: TextView
    private lateinit var humanDetectionHeaders: View

    // Data components
    private lateinit var humanAdapter: HumanDetectionAdapter

    // Service references
    private var perceptionService: PerceptionService? = null

    // State
    private var isDashboardVisible = false

    init {
        initializeViews()
        setupRecyclerView()
        setupClickListeners()
    }

    private fun initializeViews() {
        humanCountBadge = dashboardOverlay.findViewById(R.id.human_count_badge)
        humansRecyclerView = dashboardOverlay.findViewById(R.id.humans_recycler_view)
        noHumansText = dashboardOverlay.findViewById(R.id.no_humans_text)
        lastUpdateText = dashboardOverlay.findViewById(R.id.last_update_text)
        humanDetectionHeaders = dashboardOverlay.findViewById(R.id.human_detection_headers)
    }

    private fun setupRecyclerView() {
        humanAdapter = HumanDetectionAdapter()
        humansRecyclerView.layoutManager = LinearLayoutManager(context)
        humansRecyclerView.adapter = humanAdapter
    }

    private fun setupClickListeners() {
        dashboardOverlay.findViewById<View>(R.id.dashboard_close_button)?.setOnClickListener {
            hideDashboard()
        }
    }

    /**
     * Initialize dashboard with perception service
     */
    fun initialize(perceptionService: PerceptionService?) {
        this.perceptionService = perceptionService

        perceptionService?.setListener(object : PerceptionService.PerceptionListener {
            override fun onHumansDetected(humans: List<PerceptionData.HumanInfo>) {
                uiHandler.post { updateHumanDetection(humans) }
            }

            override fun onPerceptionError(error: String) {
                // Log error but don't show in UI anymore
                Log.w(TAG, "Perception error: $error")
            }

            override fun onServiceStatusChanged(isActive: Boolean) {
                // Service status changes are logged but not displayed in UI
                Log.i(TAG, "Human awareness service active: $isActive")
            }
        })

        Log.i(TAG, "Dashboard initialized")
    }

    /**
     * Show the dashboard overlay
     */
    fun showDashboard() {
        isDashboardVisible = true
        dashboardOverlay.visibility = View.VISIBLE

        // Start perception monitoring if available
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
        isDashboardVisible = false
        dashboardOverlay.visibility = View.GONE

        // Stop perception monitoring to save resources
        perceptionService?.stopMonitoring()

        Log.i(TAG, "Dashboard hidden")
    }

    /**
     * Toggle dashboard visibility
     */
    fun toggleDashboard() {
        if (isDashboardVisible) {
            hideDashboard()
        } else {
            showDashboard()
        }
    }

    /**
     * Update human detection display
     */
    private fun updateHumanDetection(humans: List<PerceptionData.HumanInfo>?) {
        val humanCount = humans?.size ?: 0
        humanCountBadge.text = humanCount.toString()

        if (humanCount > 0) {
            // Show headers and human list
            noHumansText.visibility = View.GONE
            humanDetectionHeaders.visibility = View.VISIBLE
            humansRecyclerView.visibility = View.VISIBLE
            humanAdapter.updateHumans(humans)
        } else {
            // Hide headers and show "no humans" message
            noHumansText.visibility = View.VISIBLE
            humanDetectionHeaders.visibility = View.GONE
            humansRecyclerView.visibility = View.GONE
        }

        updateLastUpdateTime()
    }

    /**
     * Update last update timestamp
     */
    private fun updateLastUpdateTime() {
        val timeString = timeFormat.format(Date())
        lastUpdateText.text = context.getString(R.string.last_update_format, timeString)
    }

    /**
     * Clean up resources
     */
    fun shutdown() {
        perceptionService?.stopMonitoring()
        isDashboardVisible = false
        Log.i(TAG, "Dashboard manager shutdown")
    }

    companion object {
        private const val TAG = "DashboardManager"
    }
}

