package io.github.anonymous.pepper_realtime.ui;

import android.app.Activity;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.PopupWindow;
import android.widget.LinearLayout;

import androidx.drawerlayout.widget.DrawerLayout;

import io.github.anonymous.pepper_realtime.data.LocationProvider;
import io.github.anonymous.pepper_realtime.manager.NavigationServiceManager;
import io.github.anonymous.pepper_realtime.R;
// MapPreviewView and MapState are in the same package now

public class MapUiManager {
    private static final String TAG = "MapUiManager";

    private final Activity activity;
    private final TextView mapStatusTextView;
    private final TextView localizationStatusTextView;
    private final FrameLayout mapPreviewContainer;
    private final MapPreviewView mapPreviewView;
    private final DrawerLayout drawerLayout;
    private final View anchorView; // TopAppBar or similar for popup anchor

    private PopupWindow navigationStatusPopup;

    public MapUiManager(Activity activity, 
                        TextView mapStatusTextView, 
                        TextView localizationStatusTextView, 
                        FrameLayout mapPreviewContainer, 
                        MapPreviewView mapPreviewView,
                        DrawerLayout drawerLayout,
                        View anchorView) {
        this.activity = activity;
        this.mapStatusTextView = mapStatusTextView;
        this.localizationStatusTextView = localizationStatusTextView;
        this.mapPreviewContainer = mapPreviewContainer;
        this.mapPreviewView = mapPreviewView;
        this.drawerLayout = drawerLayout;
        this.anchorView = anchorView;
    }

    public void updateMapStatus(String status) {
        activity.runOnUiThread(() -> mapStatusTextView.setText(status));
    }

    public void updateLocalizationStatus(String status) {
        activity.runOnUiThread(() -> {
            localizationStatusTextView.setText(status);
            // We assume updateMapPreview is called separately or we trigger it here if we have refs
        });
    }

    public void togglePreviewVisibility() {
        if (mapPreviewContainer != null) {
            mapPreviewContainer.setVisibility(
                mapPreviewContainer.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE
            );
        }
    }

    public void showNavigationStatusPopup() {
        if (navigationStatusPopup != null && navigationStatusPopup.isShowing()) {
            navigationStatusPopup.dismiss();
            return;
        }
        
        // Inflate popup layout
        View popupView = activity.getLayoutInflater().inflate(R.layout.navigation_status_popup, drawerLayout, false);
        
        // Update status in popup
        TextView popupMapStatus = popupView.findViewById(R.id.popup_map_status);
        TextView popupLocalizationStatus = popupView.findViewById(R.id.popup_localization_status);
        
        if (mapStatusTextView != null && popupMapStatus != null) {
            String mapText = mapStatusTextView.getText().toString();
            // Extract just the status part after the emoji and "Map: "
            popupMapStatus.setText(mapText.replaceFirst(activity.getString(R.string.popup_map_status_prefix), ""));
        }
        
        if (localizationStatusTextView != null && popupLocalizationStatus != null) {
            String localizationText = localizationStatusTextView.getText().toString();
            // Extract just the status part after the emoji and "Localization: "
            popupLocalizationStatus.setText(localizationText.replaceFirst(activity.getString(R.string.popup_localization_status_prefix), ""));
        }
        
        // Create popup window
        navigationStatusPopup = new PopupWindow(
            popupView,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        );
        
        // Show popup below the toolbar
        if (anchorView != null) {
            navigationStatusPopup.showAsDropDown(anchorView, 16, 8);
        }
        
        // Auto dismiss after 5 seconds
        popupView.postDelayed(() -> {
            if (navigationStatusPopup != null && navigationStatusPopup.isShowing()) {
                navigationStatusPopup.dismiss();
            }
        }, 5000);
    }

    public void updateMapPreview(NavigationServiceManager navigationServiceManager, LocationProvider locationProvider) {
        if (mapPreviewView == null || locationProvider == null || navigationServiceManager == null) {
            return;
        }

        activity.runOnUiThread(() -> {
            MapState state;
            if (!navigationServiceManager.isMapSavedOnDisk(activity)) {
                state = MapState.NO_MAP;
            } else if (!navigationServiceManager.isMapLoaded()) {
                state = MapState.MAP_LOADED_NOT_LOCALIZED;
            } else if (!navigationServiceManager.isLocalizationReady()) {
                // Determine if it's localizing or failed
                String locStatus = localizationStatusTextView.getText().toString();
                if (locStatus.contains("Failed")) {
                    state = MapState.LOCALIZATION_FAILED;
                } else {
                    state = MapState.LOCALIZING;
                }
            } else {
                state = MapState.LOCALIZED;
            }

            mapPreviewView.updateData(
                locationProvider.getSavedLocations(), 
                state,
                navigationServiceManager.getMapBitmap(),
                navigationServiceManager.getMapTopGraphicalRepresentation()
            );
        });
    }
}

