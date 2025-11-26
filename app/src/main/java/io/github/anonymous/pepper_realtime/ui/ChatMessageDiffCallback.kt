package io.github.anonymous.pepper_realtime.ui

import androidx.recyclerview.widget.DiffUtil

/**
 * DiffUtil callback for efficient RecyclerView updates of chat messages.
 * Supports partial updates for text streaming and function result updates.
 */
class ChatMessageDiffCallback(
    private val oldList: List<ChatMessage>,
    private val newList: List<ChatMessage>
) : DiffUtil.Callback() {

    override fun getOldListSize(): Int = oldList.size

    override fun getNewListSize(): Int = newList.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        val oldItem = oldList[oldItemPosition]
        val newItem = newList[newItemPosition]

        // Primary: Use UUID for stable identity (all ChatMessages have UUIDs)
        // This ensures that streaming text updates are recognized as the SAME item
        return oldItem.uuid == newItem.uuid
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        val oldItem = oldList[oldItemPosition]
        val newItem = newList[newItemPosition]

        // Compare all visible fields
        return oldItem.message == newItem.message &&
                oldItem.sender == newItem.sender &&
                oldItem.type == newItem.type &&
                oldItem.imagePath == newItem.imagePath &&
                oldItem.functionResult == newItem.functionResult &&
                oldItem.isExpanded == newItem.isExpanded
    }

    override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any? {
        val oldItem = oldList[oldItemPosition]
        val newItem = newList[newItemPosition]

        // If only the message text changed (e.g. streaming), return a specific payload
        // This allows the adapter to update only the TextView without a full rebind/animation
        if (oldItem.message != newItem.message &&
            oldItem.sender == newItem.sender &&
            oldItem.type == newItem.type &&
            oldItem.imagePath == newItem.imagePath &&
            oldItem.functionResult == newItem.functionResult &&
            oldItem.isExpanded == newItem.isExpanded
        ) {
            return TEXT_UPDATE
        }

        // If function result changed
        if (oldItem.functionResult != newItem.functionResult &&
            oldItem.message == newItem.message &&
            oldItem.sender == newItem.sender &&
            oldItem.type == newItem.type &&
            oldItem.imagePath == newItem.imagePath &&
            oldItem.isExpanded == newItem.isExpanded
        ) {
            return FUNCTION_RESULT_UPDATE
        }

        return null // Full rebind needed
    }

    companion object {
        const val TEXT_UPDATE = "TEXT_UPDATE"
        const val FUNCTION_RESULT_UPDATE = "FUNCTION_RESULT_UPDATE"
    }
}

