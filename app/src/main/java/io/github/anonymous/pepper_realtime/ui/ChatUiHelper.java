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
    private final List<ChatMessage> messageList;
    private final ChatMessageAdapter chatAdapter;
    private final RecyclerView chatRecyclerView;
    private final TextView statusTextView;
    private final Map<String, ChatMessage> pendingUserTranscripts;
    private final AudioInputController audioInputController;
    private final ChatSessionController sessionController;
    private final TurnManager turnManager;

    public ChatUiHelper(Activity activity,
                      List<ChatMessage> messageList,
                      ChatMessageAdapter chatAdapter,
                      RecyclerView chatRecyclerView,
                      TextView statusTextView,
                      Map<String, ChatMessage> pendingUserTranscripts,
                      AudioInputController audioInputController,
                      ChatSessionController sessionController,
                      TurnManager turnManager) {
        this.activity = activity;
        this.messageList = messageList;
        this.chatAdapter = chatAdapter;
        this.chatRecyclerView = chatRecyclerView;
        this.statusTextView = statusTextView;
        this.pendingUserTranscripts = pendingUserTranscripts;
        this.audioInputController = audioInputController;
        this.sessionController = sessionController;
        this.turnManager = turnManager;
    }

    public void addMessage(String text, ChatMessage.Sender sender) {
        activity.runOnUiThread(() -> {
            messageList.add(new ChatMessage(text, sender));
            chatAdapter.notifyItemInserted(messageList.size() - 1);
            chatRecyclerView.scrollToPosition(messageList.size() - 1);
             if (sender == ChatMessage.Sender.USER) {
                 statusTextView.setText(activity.getString(R.string.status_thinking));
             }
        });
    }

    public void addFunctionCall(String functionName, String args) {
        activity.runOnUiThread(() -> {
            ChatMessage functionCall = ChatMessage.createFunctionCall(functionName, args, ChatMessage.Sender.ROBOT);
            messageList.add(functionCall);
            chatAdapter.notifyItemInserted(messageList.size() - 1);
            chatRecyclerView.scrollToPosition(messageList.size() - 1);
        });
    }

    public void updateFunctionCallResult(String result) {
        activity.runOnUiThread(() -> {
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
            messageList.add(placeholder);
            chatAdapter.notifyItemInserted(messageList.size() - 1);
            chatRecyclerView.smoothScrollToPosition(messageList.size() - 1);
            pendingUserTranscripts.put(itemId, placeholder);
        });
    }
    
    public void handleUserTranscriptCompleted(String itemId, String transcript) {
        activity.runOnUiThread(() -> {
            ChatMessage placeholder = pendingUserTranscripts.remove(itemId);
            if (placeholder != null) {
                int index = messageList.indexOf(placeholder);
                if (index >= 0) {
                    placeholder.setMessage(transcript);
                    chatAdapter.notifyItemChanged(index);
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
                int index = messageList.indexOf(placeholder);
                if (index >= 0) {
                    placeholder.setMessage("🎤 [Transcription failed]");
                    chatAdapter.notifyItemChanged(index);
                }
            }
        });
    }
    
    public void addImageMessage(String imagePath) {
        activity.runOnUiThread(() -> {
            ChatMessage msg = new ChatMessage("", imagePath, ChatMessage.Sender.ROBOT);
            messageList.add(msg);
            chatAdapter.notifyItemInserted(messageList.size() - 1);
            chatRecyclerView.scrollToPosition(messageList.size() - 1);
        });
    }

    public void setStatusText(String text) {
        activity.runOnUiThread(() -> statusTextView.setText(text));
    }
    
    public void clearMessages() {
        activity.runOnUiThread(() -> {
            messageList.clear();
            chatAdapter.notifyDataSetChanged();
        });
    }
}

