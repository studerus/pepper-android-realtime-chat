package ch.fhnw.pepper_realtime.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ch.fhnw.pepper_realtime.data.LocationProvider
import ch.fhnw.pepper_realtime.manager.ApiKeyManager
import ch.fhnw.pepper_realtime.manager.PermissionManager
import ch.fhnw.pepper_realtime.manager.SessionImageManager
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

