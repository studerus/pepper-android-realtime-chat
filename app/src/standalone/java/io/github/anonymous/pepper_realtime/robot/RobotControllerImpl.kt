package io.github.anonymous.pepper_realtime.robot

import android.util.Log

/**
 * Standalone implementation of RobotController for Android smartphones/tablets.
 * Returns null for robot context - services will provide stub/simulated behavior.
 */
class RobotControllerImpl : RobotController {

    companion object {
        private const val TAG = "RobotControllerImpl"
    }

    private var isInitialized = false

    /**
     * Initialize the standalone controller
     */
    fun initialize() {
        Log.i(TAG, "🤖 STANDALONE MODE: Initializing simulation environment")
        isInitialized = true
        Log.i(TAG, "🤖 STANDALONE MODE: Ready - robot features will be simulated")
    }

    override fun getRobotContext(): Any? = null // No QiContext in standalone mode

    override fun isRobotHardwareAvailable(): Boolean = false // Running on generic Android device

    override fun isReady(): Boolean = isInitialized

    override fun getModeName(): String = "Standalone Mode (Simulation)"
}

