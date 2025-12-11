package ch.fhnw.pepper_realtime.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ch.fhnw.pepper_realtime.controller.GestureController
import ch.fhnw.pepper_realtime.controller.MovementController
import ch.fhnw.pepper_realtime.manager.AudioPlayer
import ch.fhnw.pepper_realtime.manager.NavigationServiceManager
import ch.fhnw.pepper_realtime.manager.TouchSensorManager
import ch.fhnw.pepper_realtime.service.PerceptionService
import ch.fhnw.pepper_realtime.service.VisionService
import ch.fhnw.pepper_realtime.tools.ToolRegistry
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RobotModule {

    @Provides
    @Singleton
    fun provideGestureController(): GestureController {
        return GestureController()
    }

    @Provides
    @Singleton
    fun provideAudioPlayer(): AudioPlayer {
        return AudioPlayer()
    }

    @Provides
    @Singleton
    fun provideToolRegistry(): ToolRegistry {
        return ToolRegistry()
    }

    @Provides
    @Singleton
    fun providePerceptionService(): PerceptionService {
        return PerceptionService()
    }

    @Provides
    @Singleton
    fun provideVisionService(@ApplicationContext context: Context): VisionService {
        return VisionService(context)
    }

    @Provides
    @Singleton
    fun provideTouchSensorManager(): TouchSensorManager {
        return TouchSensorManager()
    }

    @Provides
    @Singleton
    fun provideMovementController(): MovementController {
        return MovementController()
    }

    @Provides
    @Singleton
    fun provideNavigationServiceManager(movementController: MovementController): NavigationServiceManager {
        return NavigationServiceManager(movementController)
    }
}

