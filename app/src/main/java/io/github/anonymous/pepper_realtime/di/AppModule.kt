package io.github.anonymous.pepper_realtime.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.anonymous.pepper_realtime.data.LocationProvider
import io.github.anonymous.pepper_realtime.manager.ApiKeyManager
import io.github.anonymous.pepper_realtime.manager.PermissionManager
import io.github.anonymous.pepper_realtime.manager.SessionImageManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideApiKeyManager(@ApplicationContext context: Context): ApiKeyManager {
        return ApiKeyManager(context)
    }

    @Provides
    @Singleton
    fun providePermissionManager(): PermissionManager {
        return PermissionManager()
    }

    @Provides
    @Singleton
    fun provideSessionImageManager(): SessionImageManager {
        return SessionImageManager()
    }

    @Provides
    @Singleton
    fun provideLocationProvider(): LocationProvider {
        return LocationProvider()
    }
}

