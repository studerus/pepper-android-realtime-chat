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

    public ChatUiHelper(Activity activity,
            ChatViewModel viewModel,
            ChatMessageAdapter chatAdapter,
            RecyclerView chatRecyclerView,
            Map<String, ChatMessage> pendingUserTranscripts) {
        this.activity = activity;
        this.viewModel = viewModel;
        this.chatAdapter = chatAdapter;
        this.chatRecyclerView = chatRecyclerView;
        this.pendingUserTranscripts = pendingUserTranscripts;
    }

    public void addMessage(String text, ChatMessage.Sender sender) {
        activity.runOnUiThread(() -> {
            viewModel.addMessage(new ChatMessage(text, sender));
            // Adapter update handled by LiveData observer in Activity or we can do it here
            // if we share the list reference
            // For now, rely on Activity observer for consistency, or keep manual notify if
            // animation needed
            // But viewModel.addMessage posts value, so observer will fire.
            // To keep animations, we might need to know index.
            List<ChatMessage> list = viewModel.getMessageList().getValue();
            if (list != null) {
                chatAdapter.notifyItemInserted(list.size() - 1);
                chatRecyclerView.scrollToPosition(list.size() - 1);
            }
            if (sender == ChatMessage.Sender.USER) {
                viewModel.setStatusText(activity.getString(R.string.status_thinking));
            }
        });
    }

    public void addFunctionCall(String functionName, String args) {
        activity.runOnUiThread(() -> {
            ChatMessage functionCall = ChatMessage.createFunctionCall(functionName, args, ChatMessage.Sender.ROBOT);
            viewModel.addMessage(functionCall);
            List<ChatMessage> list = viewModel.getMessageList().getValue();
            if (list != null) {
                chatAdapter.notifyItemInserted(list.size() - 1);
                chatRecyclerView.scrollToPosition(list.size() - 1);
            }
        });
    }

    public void updateFunctionCallResult(String result) {
        activity.runOnUiThread(() -> {
            List<ChatMessage> messageList = viewModel.getMessageList().getValue();
            if (messageList == null)
                return;
            for (int i = messageList.size() - 1; i >= 0; i--) {
                ChatMessage message = messageList.get(i);
                if (message.getType() == ChatMessage.Type.FUNCTION_CALL &&
                        message.getFunctionResult() == null) {
                    message.setFunctionResult(result);
                    chatAdapter.notifyItemChanged(i);
                    break;
                }
            }
        });
    }

    public void handleUserSpeechStopped(String itemId) {
        activity.runOnUiThread(() -> {
            ChatMessage placeholder = new ChatMessage("🎤 ...", ChatMessage.Sender.USER);
            placeholder.setItemId(itemId);
            viewModel.addMessage(placeholder);
            List<ChatMessage> list = viewModel.getMessageList().getValue();
            if (list != null) {
                chatAdapter.notifyItemInserted(list.size() - 1);
                chatRecyclerView.smoothScrollToPosition(list.size() - 1);
            }
            pendingUserTranscripts.put(itemId, placeholder);
        });
    }

    public void handleUserTranscriptCompleted(String itemId, String transcript) {
        activity.runOnUiThread(() -> {
            ChatMessage placeholder = pendingUserTranscripts.remove(itemId);
            if (placeholder != null) {
                List<ChatMessage> messageList = viewModel.getMessageList().getValue();
                if (messageList != null) {
                    int index = messageList.indexOf(placeholder);
                    if (index >= 0) {
                        placeholder.setMessage(transcript);
                        chatAdapter.notifyItemChanged(index);
                    }
                }
            } else {
                addMessage(transcript, ChatMessage.Sender.USER);
            }
        });
    }

    public void handleUserTranscriptFailed(String itemId, JSONObject error) {
        activity.runOnUiThread(() -> {
            ChatMessage placeholder = pendingUserTranscripts.remove(itemId);
            if (placeholder != null) {
                List<ChatMessage> messageList = viewModel.getMessageList().getValue();
                if (messageList != null) {
                    int index = messageList.indexOf(placeholder);
                    if (index >= 0) {
                        placeholder.setMessage("🎤 [Transcription failed]");
                        chatAdapter.notifyItemChanged(index);
                    }
                }
            }
        });
    }

    public void addImageMessage(String imagePath) {
        activity.runOnUiThread(() -> {
            ChatMessage msg = new ChatMessage("", imagePath, ChatMessage.Sender.ROBOT);
            viewModel.addMessage(msg);
            List<ChatMessage> list = viewModel.getMessageList().getValue();
            if (list != null) {
                chatAdapter.notifyItemInserted(list.size() - 1);
                chatRecyclerView.scrollToPosition(list.size() - 1);
            }
        });
    }

    public void setStatusText(String text) {
        activity.runOnUiThread(() -> viewModel.setStatusText(text));
    }

    public void clearMessages() {
        activity.runOnUiThread(() -> {
            viewModel.clearMessages();
            // notifyDataSetChanged will be called by Activity observer
        });
    }
}
