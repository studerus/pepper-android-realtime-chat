package io.github.anonymous.pepper_realtime.di;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;
import io.github.anonymous.pepper_realtime.network.RealtimeSessionManager;

@Module
@InstallIn(SingletonComponent.class)
public class NetworkModule {

    @Provides
    @Singleton
    public RealtimeSessionManager provideRealtimeSessionManager() {
        return new RealtimeSessionManager();
    }
}
