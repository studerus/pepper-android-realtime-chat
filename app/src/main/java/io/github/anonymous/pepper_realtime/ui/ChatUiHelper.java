package io.github.anonymous.pepper_realtime.ui;

import io.github.anonymous.pepper_realtime.controller.AudioInputController;
import io.github.anonymous.pepper_realtime.controller.ChatSessionController;
import io.github.anonymous.pepper_realtime.manager.TurnManager;
import io.github.anonymous.pepper_realtime.R;

import android.app.Activity;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

public class ChatUiHelper {
    private static final String TAG = "ChatUiHelper";

    private final Activity activity;
    private final ChatViewModel viewModel;
    private final ChatMessageAdapter chatAdapter;
    private final RecyclerView chatRecyclerView;

    private final Map<String, ChatMessage> pendingUserTranscripts;

    public ChatUiHelper(Activity activity, ChatViewModel viewModel) {
        this.activity = activity;
        this.viewModel = viewModel;
        this.pendingUserTranscripts = null; // No longer used here
        this.chatAdapter = null; // No longer used here
        this.chatRecyclerView = null; // No longer used here
    }

    public void addMessage(String text, ChatMessage.Sender sender) {
        activity.runOnUiThread(() -> {
            viewModel.addMessage(new ChatMessage(text, sender));
            if (sender == ChatMessage.Sender.USER) {
                viewModel.setStatusText(activity.getString(R.string.status_thinking));
            }
        });
    }

    public void addFunctionCall(String functionName, String args) {
        activity.runOnUiThread(() -> {
            ChatMessage functionCall = ChatMessage.createFunctionCall(functionName, args, ChatMessage.Sender.ROBOT);
            viewModel.addMessage(functionCall);
        });
    }

    public void updateFunctionCallResult(String result) {
        activity.runOnUiThread(() -> viewModel.updateLatestFunctionCallResult(result));
    }

    public void handleUserSpeechStopped(String itemId) {
        activity.runOnUiThread(() -> viewModel.handleUserSpeechStopped(itemId));
    }

    public void handleUserTranscriptCompleted(String itemId, String transcript) {
        activity.runOnUiThread(() -> viewModel.handleUserTranscriptCompleted(itemId, transcript));
    }

    public void handleUserTranscriptFailed(String itemId, JSONObject error) {
        activity.runOnUiThread(() -> viewModel.handleUserTranscriptFailed(itemId, error));
    }

    public void addImageMessage(String imagePath) {
        activity.runOnUiThread(() -> {
            ChatMessage msg = new ChatMessage("", imagePath, ChatMessage.Sender.ROBOT);
            viewModel.addMessage(msg);
        });
    }

    public void setStatusText(String text) {
        activity.runOnUiThread(() -> viewModel.setStatusText(text));
    }

    public void clearMessages() {
        activity.runOnUiThread(() -> viewModel.clearMessages());
    }
}
