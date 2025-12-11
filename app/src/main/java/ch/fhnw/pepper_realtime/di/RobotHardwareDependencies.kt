package ch.fhnw.pepper_realtime.di

import ch.fhnw.pepper_realtime.controller.GestureController
import ch.fhnw.pepper_realtime.controller.MovementController
import ch.fhnw.pepper_realtime.controller.RobotFocusManager
import ch.fhnw.pepper_realtime.manager.TouchSensorManager
import ch.fhnw.pepper_realtime.service.PerceptionService
import ch.fhnw.pepper_realtime.service.VisionService

/**
 * Groups all robot hardware-related dependencies.
 * Includes sensors, movement, gestures, and perception.
 */
data class RobotHardwareDependencies(
    val robotFocusManager: RobotFocusManager,
    val gestureController: GestureController,
    val movementController: MovementController,
    val perceptionService: PerceptionService,
    val visionService: VisionService,
    val touchSensorManager: TouchSensorManager
)

