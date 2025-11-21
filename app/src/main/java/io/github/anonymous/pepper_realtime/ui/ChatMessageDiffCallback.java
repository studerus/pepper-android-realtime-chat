package io.github.anonymous.pepper_realtime.ui;

import androidx.recyclerview.widget.DiffUtil;
import java.util.List;
import java.util.Objects;

public class ChatMessageDiffCallback extends DiffUtil.Callback {

    private final List<ChatMessage> oldList;
    private final List<ChatMessage> newList;

    public ChatMessageDiffCallback(List<ChatMessage> oldList, List<ChatMessage> newList) {
        this.oldList = oldList;
        this.newList = newList;
    }

    @Override
    public int getOldListSize() {
        return oldList.size();
    }

    @Override
    public int getNewListSize() {
        return newList.size();
    }

    @Override
    public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
        ChatMessage oldItem = oldList.get(oldItemPosition);
        ChatMessage newItem = newList.get(newItemPosition);

        // Primary: Use UUID for stable identity (all ChatMessages have UUIDs)
        // This ensures that streaming text updates are recognized as the SAME item
        return Objects.equals(oldItem.getUuid(), newItem.getUuid());
    }

    @Override
    public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
        ChatMessage oldItem = oldList.get(oldItemPosition);
        ChatMessage newItem = newList.get(newItemPosition);

        // Compare all visible fields
        return Objects.equals(oldItem.getMessage(), newItem.getMessage()) &&
                oldItem.getSender() == newItem.getSender() &&
                oldItem.getType() == newItem.getType() &&
                Objects.equals(oldItem.getImagePath(), newItem.getImagePath()) &&
                Objects.equals(oldItem.getFunctionResult(), newItem.getFunctionResult()) &&
                oldItem.isExpanded() == newItem.isExpanded();
    }

    @Override
    public Object getChangePayload(int oldItemPosition, int newItemPosition) {
        ChatMessage oldItem = oldList.get(oldItemPosition);
        ChatMessage newItem = newList.get(newItemPosition);

        // If only the message text changed (e.g. streaming), return a specific payload
        // This allows the adapter to update only the TextView without a full
        // rebind/animation
        if (!Objects.equals(oldItem.getMessage(), newItem.getMessage()) &&
                oldItem.getSender() == newItem.getSender() &&
                oldItem.getType() == newItem.getType() &&
                Objects.equals(oldItem.getImagePath(), newItem.getImagePath()) &&
                Objects.equals(oldItem.getFunctionResult(), newItem.getFunctionResult()) &&
                oldItem.isExpanded() == newItem.isExpanded()) {
            return "TEXT_UPDATE";
        }

        // If function result changed
        if (!Objects.equals(oldItem.getFunctionResult(), newItem.getFunctionResult()) &&
                Objects.equals(oldItem.getMessage(), newItem.getMessage()) &&
                oldItem.getSender() == newItem.getSender() &&
                oldItem.getType() == newItem.getType() &&
                Objects.equals(oldItem.getImagePath(), newItem.getImagePath()) &&
                oldItem.isExpanded() == newItem.isExpanded()) {
            return "FUNCTION_RESULT_UPDATE";
        }

        return null; // Full rebind needed
    }
}
