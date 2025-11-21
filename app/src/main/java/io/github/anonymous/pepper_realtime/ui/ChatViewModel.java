package io.github.anonymous.pepper_realtime.ui;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.List;

public class ChatViewModel extends AndroidViewModel {

    // State LiveData
    private final MutableLiveData<Boolean> isWarmingUp = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> isResponseGenerating = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> isAudioPlaying = new MutableLiveData<>(false);
    private final MutableLiveData<String> statusText = new MutableLiveData<>("");
    private final MutableLiveData<List<ChatMessage>> messageList = new MutableLiveData<>(new ArrayList<>());

    // Connection State
    private final MutableLiveData<Boolean> isConnected = new MutableLiveData<>(false);

    // Navigation State
    private final MutableLiveData<String> mapStatus = new MutableLiveData<>("");
    private final MutableLiveData<String> localizationStatus = new MutableLiveData<>("");

    // Internal State
    private volatile String currentResponseId = null;
    private volatile String cancelledResponseId = null;
    private volatile String lastChatBubbleResponseId = null;
    private volatile boolean expectingFinalAnswerAfterToolCall = false;
    private volatile String lastAssistantItemId = null;

    public ChatViewModel(@NonNull Application application) {
        super(application);
    }

    // Getters for LiveData
    public LiveData<Boolean> getIsWarmingUp() {
        return isWarmingUp;
    }

    public LiveData<Boolean> getIsResponseGenerating() {
        return isResponseGenerating;
    }

    public LiveData<Boolean> getIsAudioPlaying() {
        return isAudioPlaying;
    }

    public LiveData<String> getStatusText() {
        return statusText;
    }

    public LiveData<List<ChatMessage>> getMessageList() {
        return messageList;
    }

    public LiveData<Boolean> getIsConnected() {
        return isConnected;
    }

    // Setters (to be replaced by logic later)
    public void setWarmingUp(boolean warmingUp) {
        isWarmingUp.postValue(warmingUp);
    }

    public void setResponseGenerating(boolean generating) {
        isResponseGenerating.postValue(generating);
    }

    public void setAudioPlaying(boolean playing) {
        isAudioPlaying.postValue(playing);
    }

    public void setStatusText(String text) {
        statusText.postValue(text);
    }

    public void setConnected(boolean connected) {
        isConnected.postValue(connected);
    }

    // Navigation State Accessors
    public LiveData<String> getMapStatus() {
        return mapStatus;
    }

    public LiveData<String> getLocalizationStatus() {
        return localizationStatus;
    }

    public void setMapStatus(String status) {
        mapStatus.postValue(status);
    }

    public void setLocalizationStatus(String status) {
        localizationStatus.postValue(status);
    }

    public void addMessage(ChatMessage message) {
        List<ChatMessage> currentList = messageList.getValue();
        if (currentList == null)
            currentList = new ArrayList<>();
        currentList.add(message);
        messageList.postValue(currentList);
    }

    public void appendToLastMessage(String text) {
        List<ChatMessage> currentList = messageList.getValue();
        if (currentList != null && !currentList.isEmpty()) {
            ChatMessage lastMsg = currentList.get(currentList.size() - 1);
            if (lastMsg.getSender() == ChatMessage.Sender.ROBOT
                    && lastMsg.getType() == ChatMessage.Type.REGULAR_MESSAGE) {
                lastMsg.setMessage(lastMsg.getMessage() + text);
                // Force update
                messageList.postValue(currentList);
            }
        }
    }

    public boolean updateMessageByItemId(String itemId, String newText) {
        List<ChatMessage> currentList = messageList.getValue();
        if (currentList != null) {
            boolean found = false;
            for (ChatMessage msg : currentList) {
                if (itemId != null && itemId.equals(msg.getItemId())) {
                    msg.setMessage(newText);
                    found = true;
                    break;
                }
            }
            if (found) {
                messageList.postValue(currentList);
                return true;
            }
        }
        return false;
    }

    public void clearMessages() {
        messageList.postValue(new ArrayList<>());
        // Clear cached item ID to prevent truncate errors on non-existent items
        lastAssistantItemId = null;
    }

    // Transcript Management
    private final java.util.Map<String, ChatMessage> pendingUserTranscripts = new java.util.HashMap<>();

    public void handleUserSpeechStopped(String itemId) {
        ChatMessage placeholder = new ChatMessage("🎤 ...", ChatMessage.Sender.USER);
        placeholder.setItemId(itemId);
        addMessage(placeholder);
        pendingUserTranscripts.put(itemId, placeholder);
    }

    public void handleUserTranscriptCompleted(String itemId, String transcript) {
        ChatMessage placeholder = pendingUserTranscripts.remove(itemId);
        if (placeholder != null) {
            List<ChatMessage> currentList = messageList.getValue();
            if (currentList != null) {
                // Find the message in the list (it might be the same object, but let's be safe)
                // Since we're modifying the object directly, we need to trigger an update
                placeholder.setMessage(transcript);
                messageList.postValue(currentList);
            }
        } else {
            // Fallback if placeholder not found
            addMessage(new ChatMessage(transcript, ChatMessage.Sender.USER));
        }
    }

    public void handleUserTranscriptFailed(String itemId, org.json.JSONObject error) {
        ChatMessage placeholder = pendingUserTranscripts.remove(itemId);
        if (placeholder != null) {
            List<ChatMessage> currentList = messageList.getValue();
            if (currentList != null) {
                placeholder.setMessage("🎤 [Transcription failed]");
                messageList.postValue(currentList);
            }
        }
    }

    public void updateLatestFunctionCallResult(String result) {
        List<ChatMessage> currentList = messageList.getValue();
        if (currentList != null) {
            for (int i = currentList.size() - 1; i >= 0; i--) {
                ChatMessage message = currentList.get(i);
                if (message.getType() == ChatMessage.Type.FUNCTION_CALL &&
                        message.getFunctionResult() == null) {
                    message.setFunctionResult(result);
                    messageList.postValue(currentList);
                    break;
                }
            }
        }
    }

    // State Accessors
    public String getCurrentResponseId() {
        return currentResponseId;
    }

    public void setCurrentResponseId(String currentResponseId) {
        this.currentResponseId = currentResponseId;
    }

    public String getCancelledResponseId() {
        return cancelledResponseId;
    }

    public void setCancelledResponseId(String cancelledResponseId) {
        this.cancelledResponseId = cancelledResponseId;
    }

    public String getLastChatBubbleResponseId() {
        return lastChatBubbleResponseId;
    }

    public void setLastChatBubbleResponseId(String lastChatBubbleResponseId) {
        this.lastChatBubbleResponseId = lastChatBubbleResponseId;
    }

    public boolean isExpectingFinalAnswerAfterToolCall() {
        return expectingFinalAnswerAfterToolCall;
    }

    public void setExpectingFinalAnswerAfterToolCall(boolean expectingFinalAnswerAfterToolCall) {
        this.expectingFinalAnswerAfterToolCall = expectingFinalAnswerAfterToolCall;
    }

    public String getLastAssistantItemId() {
        return lastAssistantItemId;
    }

    public void setLastAssistantItemId(String lastAssistantItemId) {
        this.lastAssistantItemId = lastAssistantItemId;
    }
}
