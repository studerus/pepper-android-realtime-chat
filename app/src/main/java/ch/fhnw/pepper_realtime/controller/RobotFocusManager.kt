package ch.fhnw.pepper_realtime.controller

import android.os.Handler
import android.os.Looper
import android.util.Log
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.robot.RobotController
import ch.fhnw.pepper_realtime.robot.RobotLifecycleBridge
import ch.fhnw.pepper_realtime.robot.RobotLifecycleBridgeImpl
import ch.fhnw.pepper_realtime.ui.ChatActivity

class RobotFocusManager(private val activity: ChatActivity) {

    companion object {
        private const val TAG = "RobotFocusManager"
        private const val FOCUS_TIMEOUT_MS = 30000L // 30 seconds
    }

    private val robotLifecycleBridge: RobotLifecycleBridge = RobotLifecycleBridgeImpl()
    val robotController: RobotController = robotLifecycleBridge.getRobotController()
    private val focusTimeoutHandler = Handler(Looper.getMainLooper())
    private var focusTimeoutRunnable: Runnable? = null

    var isFocusAvailable: Boolean = false
        private set
    var qiContext: Any? = null
        private set
    private var listener: Listener? = null
    private var lastFocusRefusedReason: String? = null

    interface Listener {
        fun onRobotReady(robotContext: Any?)
        fun onRobotFocusLost()
        fun onRobotInitializationFailed(error: String)
    }

    init {
        Log.i(TAG, "🤖 Initializing robot controller: ${robotController.getModeName()}")
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun register() {
        try {
            robotLifecycleBridge.register(activity, object : RobotLifecycleBridge.RobotLifecycleListener {
                override fun onRobotReady(robotContext: Any?) {
                    handleRobotReady(robotContext)
                }

                override fun onRobotFocusLost() {
                    handleRobotFocusLost()
                }

                override fun onRobotFocusRefused(reason: String) {
                    handleRobotFocusRefused(reason)
                }
            })

            startFocusTimeout()

        } catch (e: Exception) {
            Log.e(TAG, "Robot lifecycle registration failed", e)
            listener?.onRobotInitializationFailed("Robot initialization failed.")
        }
    }

    fun unregister() {
        cancelFocusTimeout()
        robotLifecycleBridge.unregister(activity)
    }

    private fun startFocusTimeout() {
        cancelFocusTimeout()
        focusTimeoutRunnable = Runnable {
            if (!isFocusAvailable) {
                Log.e(TAG, "🤖 DIAGNOSTIC: TIMEOUT - No robot focus response after 30 seconds!")
                Log.e(TAG, "🤖 DIAGNOSTIC: Mode: ${robotController.getModeName()}")
                lastFocusRefusedReason?.let {
                    Log.e(TAG, "🤖 DIAGNOSTIC: Last focus-refused reason reported by QiSDK: $it")
                }
                if (robotController.isRobotHardwareAvailable()) {
                    Log.e(TAG, "🤖 DIAGNOSTIC: Check: 1) Robot is awake, 2) Robot is enabled, 3) QiSDK service is running")
                }
                val message = lastFocusRefusedReason?.let { reason ->
                    activity.getString(R.string.robot_initialization_timeout) + " (reason: $reason)"
                } ?: activity.getString(R.string.robot_initialization_timeout)
                listener?.onRobotInitializationFailed(message)
            }
        }
        focusTimeoutHandler.postDelayed(focusTimeoutRunnable!!, FOCUS_TIMEOUT_MS)
    }

    private fun cancelFocusTimeout() {
        focusTimeoutRunnable?.let {
            focusTimeoutHandler.removeCallbacks(it)
            focusTimeoutRunnable = null
        }
    }

    private fun handleRobotReady(robotContext: Any?) {
        Log.i(TAG, "🤖 Robot ready - Mode: ${robotController.getModeName()}")

        cancelFocusTimeout()
        Log.i(TAG, "🤖 DIAGNOSTIC: Robot focus gained, cancelling startup timeout.")

        this.qiContext = robotContext
        this.isFocusAvailable = true

        listener?.onRobotReady(robotContext)
    }

    private fun handleRobotFocusLost() {
        Log.w(TAG, "🤖 Robot focus lost - Mode: ${robotController.getModeName()}")

        this.isFocusAvailable = false
        this.qiContext = null

        listener?.onRobotFocusLost()
    }

    private fun handleRobotFocusRefused(reason: String) {
        // Cache the latest refusal reason so the timeout path can surface it in the UI.
        // We do NOT fail fast here because QiSDK sometimes recovers after a refusal
        // and grants focus on a subsequent attempt within the timeout window.
        Log.e(TAG, "🤖 Robot focus refused by QiSDK: $reason")
        lastFocusRefusedReason = reason
    }
}

