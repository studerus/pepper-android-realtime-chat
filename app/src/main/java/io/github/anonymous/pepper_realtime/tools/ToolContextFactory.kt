package io.github.anonymous.pepper_realtime.tools

import io.github.anonymous.pepper_realtime.controller.ChatSessionController
import io.github.anonymous.pepper_realtime.controller.GestureController
import io.github.anonymous.pepper_realtime.controller.MovementController
import io.github.anonymous.pepper_realtime.controller.RobotFocusManager
import io.github.anonymous.pepper_realtime.data.LocationProvider
import io.github.anonymous.pepper_realtime.manager.ApiKeyManager
import io.github.anonymous.pepper_realtime.manager.DashboardManager
import io.github.anonymous.pepper_realtime.manager.NavigationServiceManager
import io.github.anonymous.pepper_realtime.manager.TouchSensorManager
import io.github.anonymous.pepper_realtime.service.PerceptionService
import io.github.anonymous.pepper_realtime.tools.interfaces.ToolHost
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
    fun create(toolHost: ToolHost, dashboardManager: DashboardManager?): ToolContext {
        return ToolContext(
            toolHost,
            robotFocusManager,
            apiKeyManager,
            movementController,
            navigationServiceManager,
            perceptionService,
            dashboardManager,
            touchSensorManager,
            gestureController,
            locationProvider,
            sessionController
        )
    }
}

