package io.github.anonymous.pepper_realtime.robot

import com.aldebaran.qi.sdk.QiContext

/**
 * Pepper-specific implementation of RobotController.
 * Provides access to real QiContext for full robot functionality.
 */
class RobotControllerImpl : RobotController {

    private var qiContext: QiContext? = null

    /**
     * Set the QiContext when robot focus is gained
     */
    fun setQiContext(qiContext: QiContext?) {
        this.qiContext = qiContext
    }

    override fun getRobotContext(): Any? = qiContext

    override fun isRobotHardwareAvailable(): Boolean = true // Running on Pepper hardware

    override fun isReady(): Boolean = qiContext != null

    override fun getModeName(): String = "Pepper Robot Mode"
}

