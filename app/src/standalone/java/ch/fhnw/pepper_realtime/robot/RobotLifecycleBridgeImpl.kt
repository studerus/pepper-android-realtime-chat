package ch.fhnw.pepper_realtime.robot

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Standalone implementation - simulates robot lifecycle without QiSDK.
 */
class RobotLifecycleBridgeImpl : RobotLifecycleBridge {

    companion object {
        private const val TAG = "RobotLifecycleBridge"
    }

    private val robotController = RobotControllerImpl()
    private var listener: RobotLifecycleBridge.RobotLifecycleListener? = null

    override fun register(activity: Activity, listener: RobotLifecycleBridge.RobotLifecycleListener) {
        this.listener = listener
        Log.i(TAG, "🤖 STANDALONE MODE: Simulating robot initialization...")

        // Initialize simulation controller
        robotController.initialize()

        // Simulate robot ready callback after a short delay (like real QiSDK would)
        Handler(Looper.getMainLooper()).postDelayed({
            Log.i(TAG, "🤖 STANDALONE MODE: Simulation ready")
            this.listener?.onRobotReady(null)
        }, 500)
    }

    override fun unregister(activity: Activity) {
        Log.i(TAG, "🤖 STANDALONE MODE: Unregistering simulation")
        // No cleanup needed for simulation
    }

    override fun getRobotController(): RobotController = robotController
}

