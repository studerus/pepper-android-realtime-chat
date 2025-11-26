package io.github.anonymous.pepper_realtime.di;

import android.content.Context;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;
import io.github.anonymous.pepper_realtime.controller.GestureController;
import io.github.anonymous.pepper_realtime.controller.MovementController;
import io.github.anonymous.pepper_realtime.manager.AudioPlayer;
import io.github.anonymous.pepper_realtime.manager.NavigationServiceManager;
import io.github.anonymous.pepper_realtime.manager.TouchSensorManager;
import io.github.anonymous.pepper_realtime.service.PerceptionService;
import io.github.anonymous.pepper_realtime.service.VisionService;
import io.github.anonymous.pepper_realtime.tools.ToolRegistry;

@Module
@InstallIn(SingletonComponent.class)
public class RobotModule {

    @Provides
    @Singleton
    public GestureController provideGestureController() {
        return new GestureController();
    }

    @Provides
    @Singleton
    public AudioPlayer provideAudioPlayer() {
        return new AudioPlayer();
    }

    // ThreadManager is now provided via @Inject constructor with coroutine dispatchers
    // See ThreadManager.kt - it uses constructor injection from CoroutineModule

    @Provides
    @Singleton
    public ToolRegistry provideToolRegistry() {
        return new ToolRegistry();
    }

    @Provides
    @Singleton
    public PerceptionService providePerceptionService() {
        return new PerceptionService();
    }

    @Provides
    @Singleton
    public VisionService provideVisionService(@ApplicationContext Context context) {
        return new VisionService(context);
    }

    @Provides
    @Singleton
    public TouchSensorManager provideTouchSensorManager() {
        return new TouchSensorManager();
    }

    @Provides
    @Singleton
    public MovementController provideMovementController() {
        return new MovementController();
    }

    @Provides
    @Singleton
    public NavigationServiceManager provideNavigationServiceManager(MovementController movementController) {
        return new NavigationServiceManager(movementController);
    }
}
