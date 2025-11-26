package io.github.anonymous.pepper_realtime.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.anonymous.pepper_realtime.controller.GestureController
import io.github.anonymous.pepper_realtime.controller.MovementController
import io.github.anonymous.pepper_realtime.manager.AudioPlayer
import io.github.anonymous.pepper_realtime.manager.NavigationServiceManager
import io.github.anonymous.pepper_realtime.manager.TouchSensorManager
import io.github.anonymous.pepper_realtime.service.PerceptionService
import io.github.anonymous.pepper_realtime.service.VisionService
import io.github.anonymous.pepper_realtime.tools.ToolRegistry
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

