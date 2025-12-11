package ch.fhnw.pepper_realtime.tools.interfaces

import android.app.Activity
import android.content.Context
import ch.fhnw.pepper_realtime.manager.SessionImageManager

/**
 * Interface for tool host functionality, typically implemented by ChatActivity
 */
interface ToolHost {
    fun runOnUiThread(action: Runnable)
    fun getAppContext(): Context
    fun getActivity(): Activity?
    fun isFinishing(): Boolean
    fun handleServiceStateChange(mode: String)
    fun addImageMessage(imagePath: String)
    fun getSessionImageManager(): SessionImageManager
    fun updateMapPreview()
    fun updateNavigationStatus(mapStatus: String, localizationStatus: String)
    fun muteMicrophone()
    fun unmuteMicrophone()
    fun refreshChatMessages()
}

