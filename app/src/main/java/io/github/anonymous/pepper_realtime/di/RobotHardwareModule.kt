package io.github.anonymous.pepper_realtime.di

import io.github.anonymous.pepper_realtime.controller.GestureController
import io.github.anonymous.pepper_realtime.controller.MovementController
import io.github.anonymous.pepper_realtime.controller.RobotFocusManager
import io.github.anonymous.pepper_realtime.manager.TouchSensorManager
import io.github.anonymous.pepper_realtime.service.PerceptionService
import io.github.anonymous.pepper_realtime.service.VisionService

/**
 * Groups all robot hardware-related dependencies.
 * Includes sensors, movement, gestures, and perception.
 */
data class RobotHardwareModule(
    val robotFocusManager: RobotFocusManager,
    val gestureController: GestureController,
    val movementController: MovementController,
    val perceptionService: PerceptionService,
    val visionService: VisionService,
    val touchSensorManager: TouchSensorManager
)

