package io.github.anonymous.pepper_realtime.ui

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.drawerlayout.widget.DrawerLayout
import io.github.anonymous.pepper_realtime.R
import io.github.anonymous.pepper_realtime.data.LocationProvider
import io.github.anonymous.pepper_realtime.manager.NavigationServiceManager

class MapUiManager(
    private val activity: Activity,
    private val mapStatusTextView: TextView?,
    private val localizationStatusTextView: TextView?,
    private val mapPreviewContainer: FrameLayout?,
    private val mapPreviewView: MapPreviewView?,
    private val drawerLayout: DrawerLayout?,
    private val anchorView: View? // TopAppBar or similar for popup anchor
) {

    companion object {
        private const val TAG = "MapUiManager"
    }

    private var navigationStatusPopup: PopupWindow? = null

    fun updateMapStatus(status: String) {
        activity.runOnUiThread { mapStatusTextView?.text = status }
    }

    fun updateLocalizationStatus(status: String) {
        activity.runOnUiThread {
            localizationStatusTextView?.text = status
            // We assume updateMapPreview is called separately or we trigger it here if we have refs
        }
    }

    fun togglePreviewVisibility() {
        mapPreviewContainer?.let {
            it.visibility = if (it.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }

    fun showNavigationStatusPopup() {
        navigationStatusPopup?.let {
            if (it.isShowing) {
                it.dismiss()
                return
            }
        }

        // Inflate popup layout
        val popupView = activity.layoutInflater.inflate(R.layout.navigation_status_popup, drawerLayout, false)

        // Update status in popup
        val popupMapStatus: TextView? = popupView.findViewById(R.id.popup_map_status)
        val popupLocalizationStatus: TextView? = popupView.findViewById(R.id.popup_localization_status)

        mapStatusTextView?.let { textView ->
            popupMapStatus?.let { popup ->
                val mapText = textView.text.toString()
                // Extract just the status part after the emoji and "Map: "
                popup.text = mapText.replaceFirst(activity.getString(R.string.popup_map_status_prefix), "")
            }
        }

        localizationStatusTextView?.let { textView ->
            popupLocalizationStatus?.let { popup ->
                val localizationText = textView.text.toString()
                // Extract just the status part after the emoji and "Localization: "
                popup.text = localizationText.replaceFirst(activity.getString(R.string.popup_localization_status_prefix), "")
            }
        }

        // Create popup window
        navigationStatusPopup = PopupWindow(
            popupView,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        )

        // Show popup below the toolbar
        anchorView?.let {
            navigationStatusPopup?.showAsDropDown(it, 16, 8)
        }

        // Auto dismiss after 5 seconds
        popupView.postDelayed({
            navigationStatusPopup?.let {
                if (it.isShowing) {
                    it.dismiss()
                }
            }
        }, 5000)
    }

    fun updateMapPreview(navigationServiceManager: NavigationServiceManager?, locationProvider: LocationProvider?) {
        if (mapPreviewView == null || locationProvider == null || navigationServiceManager == null) {
            return
        }

        activity.runOnUiThread {
            val state = when {
                !navigationServiceManager.isMapSavedOnDisk(activity) -> MapState.NO_MAP
                !navigationServiceManager.isMapLoaded() -> MapState.MAP_LOADED_NOT_LOCALIZED
                !navigationServiceManager.isLocalizationReady() -> {
                    // Determine if it's localizing or failed
                    val locStatus = localizationStatusTextView?.text?.toString() ?: ""
                    if (locStatus.contains("Failed")) {
                        MapState.LOCALIZATION_FAILED
                    } else {
                        MapState.LOCALIZING
                    }
                }
                else -> MapState.LOCALIZED
            }

            mapPreviewView.updateData(
                locationProvider.getSavedLocations(),
                state,
                navigationServiceManager.getMapBitmap(),
                navigationServiceManager.getMapTopGraphicalRepresentation()
            )
        }
    }
}

