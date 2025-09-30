package io.github.hrilab.pepper_realtime;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Manages the perception dashboard overlay UI and coordinates data from various services
 */
public class DashboardManager {
    private static final String TAG = "DashboardManager";
    
    private final Context context;
    private final Handler uiHandler;
    private final SimpleDateFormat timeFormat;
    
    // UI Components
    private final View dashboardOverlay;
    private TextView humanCountBadge;
    private RecyclerView humansRecyclerView;
    private TextView noHumansText;
    private TextView lastUpdateText;
    private View humanDetectionHeaders;
    
    // Data components
    private HumanDetectionAdapter humanAdapter;
    
    // Service references
    private PerceptionService perceptionService;
    
    // State
    private boolean isDashboardVisible = false;
    
    public DashboardManager(Context context, View dashboardOverlay) {
        this.context = context;
        this.dashboardOverlay = dashboardOverlay;
        this.uiHandler = new Handler(Looper.getMainLooper());
        this.timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        
        initializeViews();
        setupRecyclerView();
        setupClickListeners();
    }
    
    private void initializeViews() {
        humanCountBadge = dashboardOverlay.findViewById(R.id.human_count_badge);
        humansRecyclerView = dashboardOverlay.findViewById(R.id.humans_recycler_view);
        noHumansText = dashboardOverlay.findViewById(R.id.no_humans_text);
        lastUpdateText = dashboardOverlay.findViewById(R.id.last_update_text);
        humanDetectionHeaders = dashboardOverlay.findViewById(R.id.human_detection_headers);
    }
    
    private void setupRecyclerView() {
        humanAdapter = new HumanDetectionAdapter();
        humansRecyclerView.setLayoutManager(new LinearLayoutManager(context));
        humansRecyclerView.setAdapter(humanAdapter);
    }
    
    private void setupClickListeners() {
        View closeButton = dashboardOverlay.findViewById(R.id.dashboard_close_button);
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> hideDashboard());
        }
    }
    
    /**
     * Initialize dashboard with perception service
     */
    public void initialize(PerceptionService perceptionService) {
        this.perceptionService = perceptionService;
        
        if (perceptionService != null) {
            perceptionService.setListener(new PerceptionService.PerceptionListener() {
                @Override
                public void onHumansDetected(List<PerceptionData.HumanInfo> humans) {
                    uiHandler.post(() -> updateHumanDetection(humans));
                }
                
                @Override
                public void onPerceptionError(String error) {
                    // Log error but don't show in UI anymore
                    Log.w(TAG, "Perception error: " + error);
                }
                
                @Override
                public void onServiceStatusChanged(boolean isActive) {
                    // Service status changes are logged but not displayed in UI
                    Log.i(TAG, "Human awareness service active: " + isActive);
                }
            });
        }
        
        Log.i(TAG, "Dashboard initialized");
    }
    
    /**
     * Show the dashboard overlay
     */
    public void showDashboard() {
        isDashboardVisible = true;
        dashboardOverlay.setVisibility(View.VISIBLE);
        
        // Start perception monitoring if available
        if (perceptionService != null && perceptionService.isInitialized()) {
            perceptionService.startMonitoring();
        }
        
        Log.i(TAG, "Dashboard shown");
    }
    
    /**
     * Hide the dashboard overlay
     */
    public void hideDashboard() {
        isDashboardVisible = false;
        dashboardOverlay.setVisibility(View.GONE);
        
        // Stop perception monitoring to save resources
        if (perceptionService != null) {
            perceptionService.stopMonitoring();
        }
        
        Log.i(TAG, "Dashboard hidden");
    }
    
    /**
     * Toggle dashboard visibility
     */
    public void toggleDashboard() {
        if (isDashboardVisible) {
            hideDashboard();
        } else {
            showDashboard();
        }
    }
    
    
    
    /**
     * Update human detection display
     */
    private void updateHumanDetection(List<PerceptionData.HumanInfo> humans) {
        // Update UI
        int humanCount = humans != null ? humans.size() : 0;
        humanCountBadge.setText(String.valueOf(humanCount));
        
        if (humanCount > 0) {
            // Show headers and human list
            noHumansText.setVisibility(View.GONE);
            humanDetectionHeaders.setVisibility(View.VISIBLE);
            humansRecyclerView.setVisibility(View.VISIBLE);
            humanAdapter.updateHumans(humans);
        } else {
            // Hide headers and show "no humans" message
            noHumansText.setVisibility(View.VISIBLE);
            humanDetectionHeaders.setVisibility(View.GONE);
            humansRecyclerView.setVisibility(View.GONE);
        }
        
        updateLastUpdateTime();
    }
    

    
    /**
     * Update last update timestamp
     */
    private void updateLastUpdateTime() {
        String timeString = timeFormat.format(new Date());
        // Use resource with placeholder if available; fallback kept simple
        lastUpdateText.setText(context.getString(R.string.last_update_format, timeString));
    }
    

    
    /**
     * Clean up resources
     */
    public void shutdown() {
        if (perceptionService != null) {
            perceptionService.stopMonitoring();
        }
        isDashboardVisible = false;
        Log.i(TAG, "Dashboard manager shutdown");
    }
}
