package io.github.anonymous.pepper_realtime.ui;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import dagger.hilt.android.lifecycle.HiltViewModel;

@HiltViewModel
public class ChatViewModel extends AndroidViewModel {

    // State LiveData
    private final MutableLiveData<Boolean> isWarmingUp = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> isResponseGenerating = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> isAudioPlaying = new MutableLiveData<>(false);
    private final MutableLiveData<String> statusText = new MutableLiveData<>("");
    private final MutableLiveData<List<ChatMessage>> messageList = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<Boolean> isMuted = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> isInterruptFabVisible = new MutableLiveData<>(false);

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

    @Inject
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

    public LiveData<Boolean> getIsMuted() {
        return isMuted;
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

    public void setMuted(boolean muted) {
        isMuted.postValue(muted);
    }

    public LiveData<Boolean> getIsInterruptFabVisible() {
        return isInterruptFabVisible;
    }

    public void setInterruptFabVisible(boolean visible) {
        isInterruptFabVisible.postValue(visible);
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
        List<ChatMessage> newList;
        if (currentList == null)
            newList = new ArrayList<>();
        else
            newList = new ArrayList<>(currentList);
        newList.add(message);
        messageList.postValue(newList);
    }

    public void addImageMessage(String imagePath) {
        addMessage(new ChatMessage("", imagePath, ChatMessage.Sender.USER));
    }

    public void appendToLastMessage(String text) {
        List<ChatMessage> currentList = messageList.getValue();
        if (currentList != null && !currentList.isEmpty()) {
            int lastIndex = currentList.size() - 1;
            ChatMessage lastMsg = currentList.get(lastIndex);

            if (lastMsg.getSender() == ChatMessage.Sender.ROBOT
                    && lastMsg.getType() == ChatMessage.Type.REGULAR_MESSAGE) {

                // Create a new list
                List<ChatMessage> newList = new ArrayList<>(currentList);
                // Create a copy of the message with new text
                ChatMessage updatedMsg = lastMsg.copyWithNewText(lastMsg.getMessage() + text);
                // Replace in list
                newList.set(lastIndex, updatedMsg);

                // Post new list
                messageList.postValue(newList);
            }
        }
    }

    public void updateLastRobotMessage(String newText) {
        List<ChatMessage> currentList = messageList.getValue();
        if (currentList != null && !currentList.isEmpty()) {
            int lastIndex = currentList.size() - 1;
            ChatMessage lastMsg = currentList.get(lastIndex);

            if (lastMsg.getSender() == ChatMessage.Sender.ROBOT
                    && lastMsg.getType() == ChatMessage.Type.REGULAR_MESSAGE) {

                // Create a new list
                List<ChatMessage> newList = new ArrayList<>(currentList);
                // Create a copy of the message with REPLACED text
                ChatMessage updatedMsg = lastMsg.copyWithNewText(newText);
                // Replace in list
                newList.set(lastIndex, updatedMsg);

                // Post new list
                messageList.postValue(newList);
            }
        }
    }

    public boolean updateMessageByItemId(String itemId, String newText) {
        List<ChatMessage> currentList = messageList.getValue();
        if (currentList != null) {
            int indexToUpdate = -1;
            ChatMessage msgToUpdate = null;

            for (int i = 0; i < currentList.size(); i++) {
                ChatMessage msg = currentList.get(i);
                if (itemId != null && itemId.equals(msg.getItemId())) {
                    indexToUpdate = i;
                    msgToUpdate = msg;
                    break;
                }
            }

            if (indexToUpdate != -1 && msgToUpdate != null) {
                List<ChatMessage> newList = new ArrayList<>(currentList);
                ChatMessage updatedMsg = msgToUpdate.copyWithNewText(newText);
                newList.set(indexToUpdate, updatedMsg);
                messageList.postValue(newList);
                android.util.Log.d("ChatViewModel", "Updated message at index " + indexToUpdate +
                        " with itemId " + itemId + " to text: " + newText);
                return true;
            } else {
                android.util.Log.w("ChatViewModel", "Could not find message with itemId: " + itemId +
                        " in list of " + currentList.size() + " messages");
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
                // Create a new list
                List<ChatMessage> newList = new ArrayList<>(currentList);
                // Find the index of the placeholder
                int index = newList.indexOf(placeholder);
                if (index != -1) {
                    // Create a copy of the message with new text
                    ChatMessage updatedMsg = placeholder.copyWithNewText(transcript);
                    newList.set(index, updatedMsg);
                    messageList.postValue(newList);
                } else {
                    // Fallback: just add if not found (shouldn't happen if logic is correct)
                    addMessage(new ChatMessage(transcript, ChatMessage.Sender.USER));
                }
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
                List<ChatMessage> newList = new ArrayList<>(currentList);
                int index = newList.indexOf(placeholder);
                if (index != -1) {
                    ChatMessage updatedMsg = placeholder.copyWithNewText("🎤 [Transcription failed]");
                    newList.set(index, updatedMsg);
                    messageList.postValue(newList);
                }
            }
        }
    }

    public void updateLatestFunctionCallResult(String result) {
        List<ChatMessage> currentList = messageList.getValue();
        if (currentList != null) {
            int indexToUpdate = -1;
            ChatMessage msgToUpdate = null;

            for (int i = currentList.size() - 1; i >= 0; i--) {
                ChatMessage message = currentList.get(i);
                if (message.getType() == ChatMessage.Type.FUNCTION_CALL &&
                        message.getFunctionResult() == null) {
                    indexToUpdate = i;
                    msgToUpdate = message;
                    break;
                }
            }

            if (indexToUpdate != -1 && msgToUpdate != null) {
                List<ChatMessage> newList = new ArrayList<>(currentList);
                ChatMessage updatedMsg = msgToUpdate.copyWithFunctionResult(result);
                newList.set(indexToUpdate, updatedMsg);
                messageList.postValue(newList);
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

    // Controller Delegation
    private io.github.anonymous.pepper_realtime.controller.ChatSessionController sessionController;

    public void setSessionController(
            io.github.anonymous.pepper_realtime.controller.ChatSessionController sessionController) {
        this.sessionController = sessionController;
    }

    public void sendMessageToRealtimeAPI(String text, boolean requestResponse, boolean allowInterrupt) {
        if (sessionController != null) {
            sessionController.sendMessageToRealtimeAPI(text, requestResponse, allowInterrupt);
        }
    }

    public void sendToolResult(String callId, String result) {
        if (sessionController != null) {
            sessionController.sendToolResult(callId, result);
        }
    }

    public void startNewSession() {
        if (sessionController != null) {
            sessionController.startNewSession();
        }
    }

    public void connectWebSocket(io.github.anonymous.pepper_realtime.network.WebSocketConnectionCallback callback) {
        if (sessionController != null) {
            sessionController.connectWebSocket(callback);
        }
    }

    public void disconnectWebSocket() {
        if (sessionController != null) {
            sessionController.disconnectWebSocket();
        }
    }

    public void disconnectWebSocketGracefully() {
        if (sessionController != null) {
            sessionController.disconnectWebSocketGracefully();
        }
    }
}
