package io.github.anonymous.pepper_realtime.di;

import android.content.Context;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;
import io.github.anonymous.pepper_realtime.data.LocationProvider;
import io.github.anonymous.pepper_realtime.manager.ApiKeyManager;
import io.github.anonymous.pepper_realtime.manager.PermissionManager;
import io.github.anonymous.pepper_realtime.manager.SessionImageManager;

@Module
@InstallIn(SingletonComponent.class)
public class AppModule {

    @Provides
    @Singleton
    public ApiKeyManager provideApiKeyManager(@ApplicationContext Context context) {
        return new ApiKeyManager(context);
    }

    @Provides
    @Singleton
    public PermissionManager providePermissionManager() {
        return new PermissionManager();
    }

    @Provides
    @Singleton
    public SessionImageManager provideSessionImageManager() {
        return new SessionImageManager();
    }

    @Provides
    @Singleton
    public LocationProvider provideLocationProvider() {
        return new LocationProvider();
    }
}
