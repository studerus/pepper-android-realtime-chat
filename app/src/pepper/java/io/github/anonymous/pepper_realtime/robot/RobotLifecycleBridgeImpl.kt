package io.github.anonymous.pepper_realtime.robot

import android.app.Activity
import android.util.Log
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.QiSDK
import com.aldebaran.qi.sdk.RobotLifecycleCallbacks

/**
 * Pepper-specific implementation using real QiSDK lifecycle callbacks.
 */
class RobotLifecycleBridgeImpl : RobotLifecycleBridge, RobotLifecycleCallbacks {

    companion object {
        private const val TAG = "RobotLifecycleBridge"
    }

    private val robotController = RobotControllerImpl()
    private var listener: RobotLifecycleBridge.RobotLifecycleListener? = null

    override fun register(activity: Activity, listener: RobotLifecycleBridge.RobotLifecycleListener) {
        this.listener = listener
        Log.i(TAG, "🤖 Registering with QiSDK for robot lifecycle callbacks...")
        try {
            QiSDK.register(activity, this)
            Log.i(TAG, "🤖 QiSDK registration successful")
        } catch (e: Exception) {
            Log.e(TAG, "QiSDK registration failed", e)
        }
    }

    override fun unregister(activity: Activity) {
        Log.i(TAG, "🤖 Unregistering from QiSDK")
        QiSDK.unregister(activity, this)
    }

    override fun getRobotController(): RobotController = robotController

    // ===== QiSDK RobotLifecycleCallbacks =====

    override fun onRobotFocusGained(qiContext: QiContext) {
        Log.i(TAG, "🤖 Robot focus gained - Pepper is ready")
        robotController.setQiContext(qiContext)
        listener?.onRobotReady(qiContext)
    }

    override fun onRobotFocusLost() {
        Log.i(TAG, "🤖 Robot focus lost")
        robotController.setQiContext(null)
        listener?.onRobotFocusLost()
    }

    override fun onRobotFocusRefused(reason: String) {
        Log.e(TAG, "🤖 Robot focus refused: $reason")
    }
}

