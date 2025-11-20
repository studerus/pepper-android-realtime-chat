package io.github.anonymous.pepper_realtime.manager;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Manages Android permissions for the app.
 * Handles permission requests and results for microphone and camera.
 */
public class PermissionManager {
    private static final String TAG = "PermissionManager";

    private static final int MICROPHONE_PERMISSION_REQUEST_CODE = 2;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 3;

    private PermissionCallback callback;

    public interface PermissionCallback {
        void onMicrophoneGranted();

        void onMicrophoneDenied();

        void onCameraGranted();

        void onCameraDenied();
    }

    public void setCallback(PermissionCallback callback) {
        this.callback = callback;
    }

    /**
     * Check and request necessary permissions
     */
    public void checkAndRequestPermissions(Activity activity) {
        // Check microphone permission
        if (!hasMicrophonePermission(activity)) {
            ActivityCompat.requestPermissions(activity,
                    new String[] { Manifest.permission.RECORD_AUDIO },
                    MICROPHONE_PERMISSION_REQUEST_CODE);
        } else {
            Log.i(TAG, "Microphone permission available - STT will be initialized during warmup");
        }

        // Check camera permission
        if (!hasCameraPermission(activity)) {
            ActivityCompat.requestPermissions(activity,
                    new String[] { Manifest.permission.CAMERA },
                    CAMERA_PERMISSION_REQUEST_CODE);
        }
    }

    /**
     * Handle permission request results
     */
    public boolean handlePermissionResult(int requestCode, int[] grantResults) {
        if (requestCode == MICROPHONE_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Microphone permission granted - STT will be initialized during warmup");
                if (callback != null) {
                    callback.onMicrophoneGranted();
                }
            } else {
                Log.w(TAG, "Microphone permission denied");
                if (callback != null) {
                    callback.onMicrophoneDenied();
                }
            }
            return true;
        } else if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Camera permission granted - vision analysis now available");
                if (callback != null) {
                    callback.onCameraGranted();
                }
            } else {
                Log.w(TAG, "Camera permission denied - vision analysis will not work");
                if (callback != null) {
                    callback.onCameraDenied();
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Check if microphone permission is granted
     */
    public boolean hasMicrophonePermission(Context context) {
        return ContextCompat.checkSelfPermission(context,
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Check if camera permission is granted
     */
    public boolean hasCameraPermission(Context context) {
        return ContextCompat.checkSelfPermission(context,
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }
}
