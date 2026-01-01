package ch.fhnw.pepper_realtime.di

import ch.fhnw.pepper_realtime.controller.VideoInputController
import ch.fhnw.pepper_realtime.controller.VideoInputControllerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.scopes.ActivityScoped

/**
 * Hilt module for binding VideoInputController to its Pepper implementation.
 */
@Module
@InstallIn(ActivityComponent::class)
abstract class VideoModule {

    @Binds
    @ActivityScoped
    abstract fun bindVideoInputController(
        impl: VideoInputControllerImpl
    ): VideoInputController
}

