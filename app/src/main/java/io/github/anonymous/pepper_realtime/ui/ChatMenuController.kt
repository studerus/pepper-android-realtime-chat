package io.github.anonymous.pepper_realtime.ui

import android.content.res.Configuration
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import io.github.anonymous.pepper_realtime.R
import io.github.anonymous.pepper_realtime.manager.DashboardManager
import io.github.anonymous.pepper_realtime.manager.SettingsManagerCompat

class ChatMenuController(
    private val activity: ChatActivity,
    private val drawerLayout: DrawerLayout,
    private val mapUiManager: MapUiManager,
    private val dashboardManager: DashboardManager?,
    private val settingsManager: SettingsManagerCompat?
) {

    fun interface Listener {
        fun onNewChatRequested()
    }

    private var listener: Listener? = null

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun setupSettingsMenu() {
        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                settingsManager?.onDrawerOpened()
            }

            override fun onDrawerClosed(drawerView: View) {
                settingsManager?.onDrawerClosed()
            }
        })
    }

    fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater): Boolean {
        inflater.inflate(R.menu.chat_toolbar_menu, menu)
        return true
    }

    fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val settingsItem = menu.findItem(R.id.action_settings)
        val newChatItem = menu.findItem(R.id.action_new_chat)
        val mapItem = menu.findItem(R.id.action_navigation_status)
        val dashboardItem = menu.findItem(R.id.action_dashboard)

        val isLandscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        settingsItem?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        newChatItem?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)

        mapItem?.setShowAsAction(
            if (isLandscape) MenuItem.SHOW_AS_ACTION_ALWAYS else MenuItem.SHOW_AS_ACTION_NEVER
        )
        dashboardItem?.setShowAsAction(
            if (isLandscape) MenuItem.SHOW_AS_ACTION_ALWAYS else MenuItem.SHOW_AS_ACTION_NEVER
        )

        return true
    }

    fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_navigation_status -> {
                mapUiManager.togglePreviewVisibility()
                mapUiManager.showNavigationStatusPopup()
                true
            }
            R.id.action_new_chat -> {
                listener?.onNewChatRequested()
                true
            }
            R.id.action_dashboard -> {
                dashboardManager?.toggleDashboard()
                true
            }
            R.id.action_settings -> {
                drawerLayout.openDrawer(GravityCompat.END)
                true
            }
            else -> false
        }
    }
}

