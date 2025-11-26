package io.github.anonymous.pepper_realtime.di

import android.app.Activity
import androidx.lifecycle.ViewModelProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.scopes.ActivityScoped
import io.github.anonymous.pepper_realtime.controller.ChatRealtimeHandler
import io.github.anonymous.pepper_realtime.controller.RobotFocusManager
import io.github.anonymous.pepper_realtime.manager.AudioPlayer
import io.github.anonymous.pepper_realtime.manager.TurnManager
import io.github.anonymous.pepper_realtime.network.RealtimeEventHandler
import io.github.anonymous.pepper_realtime.tools.ToolRegistry
import io.github.anonymous.pepper_realtime.ui.ChatActivity
import io.github.anonymous.pepper_realtime.ui.ChatViewModel
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
        toolRegistry: ToolRegistry
    ): RealtimeEventHandler {
        // ToolContext is set later
        val handler = ChatRealtimeHandler(
            viewModel, audioPlayer, turnManager,
            ioDispatcher, applicationScope, toolRegistry, null
        )
        return RealtimeEventHandler(handler)
    }
}
