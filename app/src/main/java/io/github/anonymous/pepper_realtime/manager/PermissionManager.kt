package io.github.anonymous.pepper_realtime.manager

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
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
        // Check microphone permission
        if (!hasMicrophonePermission(activity)) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                MICROPHONE_PERMISSION_REQUEST_CODE
            )
        } else {
            Log.i(TAG, "Microphone permission available - STT will be initialized during warmup")
        }

        // Check camera permission
        if (!hasCameraPermission(activity)) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }

    /**
     * Handle permission request results
     */
    fun handlePermissionResult(requestCode: Int, grantResults: IntArray): Boolean {
        return when (requestCode) {
            MICROPHONE_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "Microphone permission granted - STT will be initialized during warmup")
                    callback?.onMicrophoneGranted()
                } else {
                    Log.w(TAG, "Microphone permission denied")
                    callback?.onMicrophoneDenied()
                }
                true
            }
            CAMERA_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "Camera permission granted - vision analysis now available")
                    callback?.onCameraGranted()
                } else {
                    Log.w(TAG, "Camera permission denied - vision analysis will not work")
                    callback?.onCameraDenied()
                }
                true
            }
            else -> false
        }
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
        private const val TAG = "PermissionManager"
        private const val MICROPHONE_PERMISSION_REQUEST_CODE = 2
        private const val CAMERA_PERMISSION_REQUEST_CODE = 3
    }
}

