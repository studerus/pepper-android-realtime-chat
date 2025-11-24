package io.github.anonymous.pepper_realtime.tools.interfaces;

import android.app.Activity;
import android.content.Context;

public interface ToolHost {
    void runOnUiThread(Runnable action);

    Context getAppContext();

    Activity getActivity();

    boolean isFinishing();

    void handleServiceStateChange(String mode);

    void addImageMessage(String imagePath);

    io.github.anonymous.pepper_realtime.manager.SessionImageManager getSessionImageManager();
}
