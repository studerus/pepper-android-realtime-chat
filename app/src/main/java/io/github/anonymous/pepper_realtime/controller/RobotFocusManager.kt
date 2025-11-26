package io.github.anonymous.pepper_realtime.controller

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.anonymous.pepper_realtime.R
import io.github.anonymous.pepper_realtime.robot.RobotController
import io.github.anonymous.pepper_realtime.robot.RobotLifecycleBridge
import io.github.anonymous.pepper_realtime.robot.RobotLifecycleBridgeImpl
import io.github.anonymous.pepper_realtime.ui.ChatActivity

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
                if (robotController.isRobotHardwareAvailable()) {
                    Log.e(TAG, "🤖 DIAGNOSTIC: Check: 1) Robot is awake, 2) Robot is enabled, 3) QiSDK service is running")
                }
                listener?.onRobotInitializationFailed(activity.getString(R.string.robot_initialization_timeout))
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
}

