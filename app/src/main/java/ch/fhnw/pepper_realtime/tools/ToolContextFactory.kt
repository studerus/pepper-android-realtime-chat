package ch.fhnw.pepper_realtime.tools

import ch.fhnw.pepper_realtime.controller.ChatSessionController
import ch.fhnw.pepper_realtime.controller.GestureController
import ch.fhnw.pepper_realtime.controller.MovementController
import ch.fhnw.pepper_realtime.controller.RobotFocusManager
import ch.fhnw.pepper_realtime.data.LocationProvider
import ch.fhnw.pepper_realtime.manager.ApiKeyManager
import ch.fhnw.pepper_realtime.manager.NavigationServiceManager
import ch.fhnw.pepper_realtime.manager.TouchSensorManager
import ch.fhnw.pepper_realtime.service.PerceptionService
import ch.fhnw.pepper_realtime.tools.interfaces.ToolHost
import javax.inject.Inject

class ToolContextFactory @Inject constructor(
    private val robotFocusManager: RobotFocusManager,
    private val apiKeyManager: ApiKeyManager,
    private val movementController: MovementController,
    private val navigationServiceManager: NavigationServiceManager,
    private val perceptionService: PerceptionService,
    private val touchSensorManager: TouchSensorManager,
    private val gestureController: GestureController,
    private val locationProvider: LocationProvider,
    private val sessionController: ChatSessionController
) {
    fun create(toolHost: ToolHost): ToolContext {
        return ToolContext(
            toolHost,
            robotFocusManager,
            apiKeyManager,
            movementController,
            navigationServiceManager,
            perceptionService,
            touchSensorManager,
            gestureController,
            locationProvider,
            sessionController
        )
    }
}

