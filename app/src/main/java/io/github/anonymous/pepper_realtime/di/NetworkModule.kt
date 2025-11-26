package io.github.anonymous.pepper_realtime.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.anonymous.pepper_realtime.network.RealtimeSessionManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideRealtimeSessionManager(): RealtimeSessionManager {
        return RealtimeSessionManager()
    }
}

