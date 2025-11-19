package io.github.anonymous.pepper_realtime.ui;

import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;

import io.github.anonymous.pepper_realtime.ui.ChatActivity;
import io.github.anonymous.pepper_realtime.R;
import io.github.anonymous.pepper_realtime.manager.DashboardManager;
import io.github.anonymous.pepper_realtime.manager.SettingsManager;

public class ChatMenuController {
    private final ChatActivity activity;
    private final DrawerLayout drawerLayout;
    private final MapUiManager mapUiManager;
    private final DashboardManager dashboardManager;
    private final SettingsManager settingsManager;

    public interface Listener {
        void onNewChatRequested();
    }

    private Listener listener;

    public ChatMenuController(ChatActivity activity, 
                            DrawerLayout drawerLayout, 
                            MapUiManager mapUiManager,
                            DashboardManager dashboardManager,
                            SettingsManager settingsManager) {
        this.activity = activity;
        this.drawerLayout = drawerLayout;
        this.mapUiManager = mapUiManager;
        this.dashboardManager = dashboardManager;
        this.settingsManager = settingsManager;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setupSettingsMenu() {
        NavigationView navigationView = activity.findViewById(R.id.navigation_view);
        // SettingsManager initialization delegated from Activity or passed in?
        // Assuming SettingsManager is already initialized in Activity and passed here
        
        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerClosed(@NonNull View drawerView) {
                if (settingsManager != null) {
                    settingsManager.onDrawerClosed();
                }
            }
        });
    }

    public boolean onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.chat_toolbar_menu, menu);
        return true;
    }

    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem settingsItem = menu.findItem(R.id.action_settings);
        MenuItem newChatItem = menu.findItem(R.id.action_new_chat);
        MenuItem mapItem = menu.findItem(R.id.action_navigation_status);
        MenuItem dashboardItem = menu.findItem(R.id.action_dashboard);

        boolean isLandscape = activity.getResources().getConfiguration().orientation == 
            android.content.res.Configuration.ORIENTATION_LANDSCAPE;

        if (settingsItem != null) settingsItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        if (newChatItem != null) newChatItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        
        if (mapItem != null) {
            mapItem.setShowAsAction(isLandscape ? 
                MenuItem.SHOW_AS_ACTION_ALWAYS : MenuItem.SHOW_AS_ACTION_NEVER);
        }
        if (dashboardItem != null) {
            dashboardItem.setShowAsAction(isLandscape ? 
                MenuItem.SHOW_AS_ACTION_ALWAYS : MenuItem.SHOW_AS_ACTION_NEVER);
        }

        return true;
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.action_navigation_status) {
            mapUiManager.togglePreviewVisibility();
            mapUiManager.showNavigationStatusPopup();
            return true;
        } else if (itemId == R.id.action_new_chat) {
            if (listener != null) {
                listener.onNewChatRequested();
            }
            return true;
        } else if (itemId == R.id.action_dashboard) {
            if (dashboardManager != null) {
                dashboardManager.toggleDashboard();
            }
            return true;
        } else if (itemId == R.id.action_settings) {
            drawerLayout.openDrawer(GravityCompat.END);
            return true;
        }
        return false;
    }
}

