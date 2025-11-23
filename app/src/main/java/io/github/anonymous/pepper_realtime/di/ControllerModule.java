package io.github.anonymous.pepper_realtime.di;

import android.app.Activity;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.components.ActivityComponent;
import dagger.hilt.android.scopes.ActivityScoped;
import io.github.anonymous.pepper_realtime.controller.RobotFocusManager;
import io.github.anonymous.pepper_realtime.manager.AudioPlayer;
import io.github.anonymous.pepper_realtime.manager.ThreadManager;
import io.github.anonymous.pepper_realtime.manager.TurnManager;
import io.github.anonymous.pepper_realtime.network.RealtimeEventHandler;
import io.github.anonymous.pepper_realtime.ui.ChatActivity;
import io.github.anonymous.pepper_realtime.ui.ChatViewModel;
import io.github.anonymous.pepper_realtime.controller.ChatRealtimeHandler;
import io.github.anonymous.pepper_realtime.tools.ToolRegistry;

@Module
@InstallIn(ActivityComponent.class)
public class ControllerModule {

    @Provides
    @ActivityScoped
    public ChatActivity provideChatActivity(Activity activity) {
        return (ChatActivity) activity;
    }

    @Provides
    @ActivityScoped
    public ChatViewModel provideChatViewModel(ChatActivity activity) {
        return new androidx.lifecycle.ViewModelProvider(activity).get(ChatViewModel.class);
    }

    @Provides
    @ActivityScoped
    public TurnManager provideTurnManager() {
        // Listener is set later in Activity
        return new TurnManager(null);
    }

    @Provides
    @ActivityScoped
    public RobotFocusManager provideRobotFocusManager(ChatActivity activity) {
        return new RobotFocusManager(activity);
    }

    @Provides
    @ActivityScoped
    public RealtimeEventHandler provideRealtimeEventHandler(
            ChatViewModel viewModel,
            AudioPlayer audioPlayer,
            TurnManager turnManager,
            ThreadManager threadManager,
            ToolRegistry toolRegistry) {
        // ToolContext is set later
        ChatRealtimeHandler handler = new ChatRealtimeHandler(viewModel, audioPlayer, turnManager,
                threadManager, toolRegistry, null);
        return new RealtimeEventHandler(handler);
    }

}
