package ch.fhnw.pepper_realtime.di

import android.app.Activity
import androidx.lifecycle.ViewModelProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.scopes.ActivityScoped
import ch.fhnw.pepper_realtime.controller.AudioInputController
import ch.fhnw.pepper_realtime.controller.ChatInterruptController
import ch.fhnw.pepper_realtime.controller.ChatRealtimeHandler
import ch.fhnw.pepper_realtime.controller.ChatSessionController
import ch.fhnw.pepper_realtime.controller.ChatTurnListener
import ch.fhnw.pepper_realtime.controller.GestureController
import ch.fhnw.pepper_realtime.controller.MovementController
import ch.fhnw.pepper_realtime.controller.RobotFocusManager
import ch.fhnw.pepper_realtime.data.LocationProvider
import ch.fhnw.pepper_realtime.manager.AudioPlayer
import ch.fhnw.pepper_realtime.manager.NavigationServiceManager
import ch.fhnw.pepper_realtime.manager.SettingsRepository
import ch.fhnw.pepper_realtime.manager.TouchSensorManager
import ch.fhnw.pepper_realtime.manager.TurnManager
import ch.fhnw.pepper_realtime.network.RealtimeEventHandler
import ch.fhnw.pepper_realtime.network.RealtimeSessionManager
import ch.fhnw.pepper_realtime.service.PerceptionService
import ch.fhnw.pepper_realtime.service.VisionService
import ch.fhnw.pepper_realtime.tools.ToolRegistry
import ch.fhnw.pepper_realtime.ui.ChatActivity
import ch.fhnw.pepper_realtime.ui.ChatViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

@Module
@InstallIn(ActivityComponent::class)
object ControllerModule {

    @Provides
    @ActivityScoped
    fun provideChatActivity(activity: Activity): ChatActivity {
        return activity as ChatActivity
    }

    @Provides
    @ActivityScoped
    fun provideChatViewModel(activity: ChatActivity): ChatViewModel {
        return ViewModelProvider(activity)[ChatViewModel::class.java]
    }

    @Provides
    @ActivityScoped
    fun provideTurnManager(): TurnManager {
        // Listener is set later in Activity
        return TurnManager(null)
    }

    @Provides
    @ActivityScoped
    fun provideRobotFocusManager(activity: ChatActivity): RobotFocusManager {
        return RobotFocusManager(activity)
    }

    @Provides
    @ActivityScoped
    fun provideRealtimeEventHandler(
        viewModel: ChatViewModel,
        audioPlayer: AudioPlayer,
        turnManager: TurnManager,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        @ApplicationScope applicationScope: CoroutineScope,
        toolRegistry: ToolRegistry,
        sessionManager: RealtimeSessionManager,
        settingsRepository: SettingsRepository
    ): RealtimeEventHandler {
        // ToolContext is set later
        val handler = ChatRealtimeHandler(
            viewModel, audioPlayer, turnManager,
            ioDispatcher, applicationScope, toolRegistry, null,
            sessionManager, settingsRepository
        )
        return RealtimeEventHandler(handler)
    }

    @Provides
    @ActivityScoped
    fun provideChatDependencies(
        sessionController: ChatSessionController,
        sessionManager: RealtimeSessionManager,
        turnManager: TurnManager,
        audioInputController: AudioInputController,
        audioPlayer: AudioPlayer,
        interruptController: ChatInterruptController,
        eventHandler: RealtimeEventHandler,
        turnListener: ChatTurnListener
    ): ChatDependencies {
        return ChatDependencies(
            sessionController = sessionController,
            sessionManager = sessionManager,
            turnManager = turnManager,
            audioInputController = audioInputController,
            audioPlayer = audioPlayer,
            interruptController = interruptController,
            eventHandler = eventHandler,
            turnListener = turnListener
        )
    }

    @Provides
    @ActivityScoped
    fun provideRobotHardwareDependencies(
        robotFocusManager: RobotFocusManager,
        gestureController: GestureController,
        movementController: MovementController,
        perceptionService: PerceptionService,
        visionService: VisionService,
        touchSensorManager: TouchSensorManager
    ): RobotHardwareDependencies {
        return RobotHardwareDependencies(
            robotFocusManager = robotFocusManager,
            gestureController = gestureController,
            movementController = movementController,
            perceptionService = perceptionService,
            visionService = visionService,
            touchSensorManager = touchSensorManager
        )
    }

    @Provides
    @ActivityScoped
    fun provideNavigationDependencies(
        navigationServiceManager: NavigationServiceManager,
        locationProvider: LocationProvider
    ): NavigationDependencies {
        return NavigationDependencies(
            navigationServiceManager = navigationServiceManager,
            locationProvider = locationProvider
        )
    }
}
