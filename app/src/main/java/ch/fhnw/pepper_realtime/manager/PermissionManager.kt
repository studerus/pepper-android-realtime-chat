package ch.fhnw.pepper_realtime.manager

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Manages Android permissions for the app.
 * Handles permission requests and results for microphone and camera.
 */
class PermissionManager {

    interface PermissionCallback {
        fun onMicrophoneGranted()
        fun onMicrophoneDenied()
        fun onCameraGranted()
        fun onCameraDenied()
    }

    private var callback: PermissionCallback? = null

    fun setCallback(callback: PermissionCallback?) {
        this.callback = callback
    }

    /**
     * Check and request necessary permissions
     */
    fun checkAndRequestPermissions(activity: Activity) {
        val missing = buildList {
            if (!hasMicrophonePermission(activity)) add(Manifest.permission.RECORD_AUDIO)
            if (!hasCameraPermission(activity)) add(Manifest.permission.CAMERA)
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, missing.toTypedArray(), MEDIA_PERMISSION_REQUEST)
        }
    }

    fun handlePermissionResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray): Boolean {
        if (requestCode != MEDIA_PERMISSION_REQUEST) return false
        permissions.forEachIndexed { index, permission ->
            val result = grantResults.getOrNull(index) ?: return@forEachIndexed
            val granted = result == PackageManager.PERMISSION_GRANTED
            when (permission) {
                Manifest.permission.RECORD_AUDIO -> if (granted) callback?.onMicrophoneGranted() else callback?.onMicrophoneDenied()
                Manifest.permission.CAMERA -> if (granted) callback?.onCameraGranted() else callback?.onCameraDenied()
            }
        }
        return true
    }

    /**
     * Check if microphone permission is granted
     */
    fun hasMicrophonePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if camera permission is granted
     */
    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        internal const val MEDIA_PERMISSION_REQUEST = 2
    }
}

