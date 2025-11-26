package io.github.anonymous.pepper_realtime.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.RotateAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.github.anonymous.pepper_realtime.R
import java.io.File

class ChatMessageAdapter(
    private var messages: List<ChatMessage>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_ROBOT = 2
        private const val VIEW_TYPE_FUNCTION_CALL = 3

        @JvmStatic
        fun decodeSampledBitmapFromPath(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path, options)
            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false
            return BitmapFactory.decodeFile(path, options)
        }

        @JvmStatic
        fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
            val height = options.outHeight
            val width = options.outWidth
            var inSampleSize = 1
            if (height > reqHeight || width > reqWidth) {
                val halfHeight = height / 2
                val halfWidth = width / 2
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize
        }
    }

    fun setMessages(newMessages: List<ChatMessage>) {
        val diffResult = DiffUtil.calculateDiff(ChatMessageDiffCallback(messages, newMessages))
        messages = newMessages
        diffResult.dispatchUpdatesTo(this)
    }

    override fun getItemViewType(position: Int): Int {
        val message = messages[position]
        return when {
            message.type == ChatMessage.Type.FUNCTION_CALL -> VIEW_TYPE_FUNCTION_CALL
            message.sender == ChatMessage.Sender.USER -> VIEW_TYPE_USER
            else -> VIEW_TYPE_ROBOT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_FUNCTION_CALL -> {
                val view = inflater.inflate(R.layout.item_function_call, parent, false)
                FunctionCallViewHolder(view, this)
            }
            VIEW_TYPE_USER -> {
                val view = inflater.inflate(R.layout.item_chat_user, parent, false)
                MessageViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_chat_robot, parent, false)
                MessageViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
        } else {
            val message = messages[position]
            for (payload in payloads) {
                when (payload) {
                    ChatMessageDiffCallback.TEXT_UPDATE -> {
                        if (holder is MessageViewHolder) {
                            holder.messageTextView.text = message.message
                            // Ensure bubble visibility is correct if it was empty before
                            if (!TextUtils.isEmpty(message.message)) {
                                val bubbleContainer = holder.itemView.findViewById<FrameLayout>(R.id.bubble_container)
                                if (bubbleContainer != null && bubbleContainer.visibility != View.VISIBLE) {
                                    bubbleContainer.visibility = View.VISIBLE
                                    // Re-apply background if needed
                                    if (message.sender == ChatMessage.Sender.ROBOT) {
                                        val robotBubble = GradientDrawable().apply {
                                            shape = GradientDrawable.RECTANGLE
                                            setColor(Color.parseColor("#E5E5EA"))
                                            cornerRadius = dpToPx(holder.itemView.context, 20).toFloat()
                                        }
                                        bubbleContainer.background = robotBubble
                                    }
                                }
                            }
                        }
                    }
                    ChatMessageDiffCallback.FUNCTION_RESULT_UPDATE -> {
                        if (holder is FunctionCallViewHolder) {
                            holder.bind(message) // Re-bind function view to show result
                        }
                    }
                }
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]

        when (holder) {
            is FunctionCallViewHolder -> holder.bind(message)
            is MessageViewHolder -> {
                val text = message.message
                holder.messageTextView.text = text

                val bubbleContainer = holder.itemView.findViewById<FrameLayout>(R.id.bubble_container)

                if (TextUtils.isEmpty(text)) {
                    bubbleContainer?.visibility = View.GONE
                } else {
                    bubbleContainer?.apply {
                        visibility = View.VISIBLE
                        // Forcefully apply a programmatically created drawable to bypass themes
                        if (message.sender == ChatMessage.Sender.ROBOT) {
                            val robotBubble = GradientDrawable().apply {
                                shape = GradientDrawable.RECTANGLE
                                setColor(Color.parseColor("#E5E5EA")) // Light gray
                                cornerRadius = dpToPx(holder.itemView.context, 20).toFloat()
                            }
                            background = robotBubble
                        }
                    }
                    val screenMax = (holder.itemView.resources.displayMetrics.widthPixels * 0.8f).toInt()
                    holder.messageTextView.maxWidth = screenMax
                }

                val imageView = holder.messageImageView
                if (imageView != null) {
                    val path = message.imagePath
                    if (path != null && File(path).exists()) {
                        imageView.visibility = View.VISIBLE
                        val thumbPx = dpToPx(holder.itemView.context, 220)
                        imageView.setImageBitmap(decodeSampledBitmapFromPath(path, thumbPx, thumbPx))
                        imageView.setOnClickListener { v -> showImageOverlay(v, path) }
                    } else {
                        imageView.visibility = View.GONE
                        imageView.setOnClickListener(null)
                    }
                }
            }
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        val metrics = context.resources.displayMetrics
        return Math.round(dp * (metrics.densityDpi / 160f))
    }

    private fun showImageOverlay(clickedView: View, imagePath: String) {
        // Find the root activity view to add overlay
        val activity = clickedView.context as Activity
        val rootView = activity.findViewById<View>(android.R.id.content)
        val imageOverlay = activity.findViewById<View>(R.id.image_overlay)

        if (imageOverlay != null && rootView != null) {
            val overlayImage = imageOverlay.findViewById<ImageView>(R.id.overlay_image)

            if (overlayImage != null) {
                // Load image
                val bitmap = decodeSampledBitmapFromPath(imagePath, 2048, 2048)
                overlayImage.setImageBitmap(bitmap)

                // Show overlay
                imageOverlay.visibility = View.VISIBLE

                // Set click listener to close
                imageOverlay.setOnClickListener { hideImageOverlay(activity) }
            }
        }
    }

    private fun hideImageOverlay(activity: Activity) {
        val imageOverlay = activity.findViewById<View>(R.id.image_overlay)
        imageOverlay?.visibility = View.GONE
    }

    override fun getItemCount(): Int = messages.size

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val messageTextView: TextView = itemView.findViewById(R.id.messageTextView)
        val messageImageView: ImageView? = itemView.findViewById(R.id.messageImageView)
    }

    class FunctionCallViewHolder(
        itemView: View,
        private val adapter: ChatMessageAdapter
    ) : RecyclerView.ViewHolder(itemView) {

        private val functionIcon: TextView = itemView.findViewById(R.id.function_icon)
        private val functionName: TextView = itemView.findViewById(R.id.function_name)
        private val functionStatus: TextView = itemView.findViewById(R.id.function_status)
        private val functionSummary: TextView = itemView.findViewById(R.id.function_summary)
        private val functionArgs: TextView = itemView.findViewById(R.id.function_args)
        private val functionResult: TextView = itemView.findViewById(R.id.function_result)
        private val functionResultLabel: TextView = itemView.findViewById(R.id.function_result_label)
        private val expandIcon: ImageView = itemView.findViewById(R.id.expand_icon)
        private val detailsContainer: LinearLayout = itemView.findViewById(R.id.function_details_container)

        init {
            // Set up click listener for expand/collapse
            val header = itemView.findViewById<LinearLayout>(R.id.function_header)
            header.setOnClickListener { toggleExpanded() }
        }

        fun bind(message: ChatMessage) {
            // Set function icon based on function name
            functionIcon.text = getFunctionIcon(message.functionName)

            // Set function display name
            functionName.text = getFunctionDisplayName(message.functionName)

            // Set status with appropriate colors
            val hasResult = message.functionResult != null
            if (hasResult) {
                functionStatus.text = "✅"
                functionStatus.setTextColor(
                    ContextCompat.getColor(itemView.context, R.color.function_call_status_success)
                )
            } else {
                functionStatus.text = "⏳"
                functionStatus.setTextColor(
                    ContextCompat.getColor(itemView.context, R.color.function_call_status_pending)
                )
            }

            // Set summary
            functionSummary.text = generateSummary(message)

            // Set arguments
            functionArgs.text = formatJson(message.functionArgs)

            // Show/hide result
            if (hasResult) {
                functionResult.text = formatJson(message.functionResult)
                functionResult.visibility = View.VISIBLE
                functionResultLabel.visibility = View.VISIBLE
            } else {
                functionResult.visibility = View.GONE
                functionResultLabel.visibility = View.GONE
            }

            // Set expand/collapse state
            updateExpandState(message.isExpanded)
        }

        private fun toggleExpanded() {
            val position = bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return
            if (position >= adapter.messages.size) return

            val message = adapter.messages[position]
            val newState = !message.isExpanded
            message.isExpanded = newState
            updateExpandState(newState)
        }

        private fun updateExpandState(expanded: Boolean) {
            detailsContainer.visibility = if (expanded) View.VISIBLE else View.GONE

            // Rotate expand icon
            val rotate = RotateAnimation(
                if (expanded) 0f else 180f,
                if (expanded) 180f else 0f,
                RotateAnimation.RELATIVE_TO_SELF, 0.5f,
                RotateAnimation.RELATIVE_TO_SELF, 0.5f
            ).apply {
                duration = 200
                fillAfter = true
            }
            expandIcon.startAnimation(rotate)
        }

        private fun getFunctionIcon(functionName: String?): String {
            return when (functionName) {
                "search_internet" -> "🌐"
                "get_weather" -> "🌤️"
                "analyze_vision" -> "👁️"
                "play_animation" -> "🤖"
                "get_current_datetime" -> "🕐"
                "get_random_joke" -> "😄"
                "present_quiz_question" -> "❓"
                "start_memory_game" -> "🧠"
                else -> "🔧"
            }
        }

        private fun getFunctionDisplayName(functionName: String?): String {
            return when (functionName) {
                "search_internet" -> "Internet Search"
                "get_weather" -> "Weather"
                "analyze_vision" -> "Vision Analysis"
                "play_animation" -> "Animation"
                "get_current_datetime" -> "Date/Time"
                "get_random_joke" -> "Random Joke"
                "present_quiz_question" -> "Quiz Question"
                "start_memory_game" -> "Memory Game"
                else -> functionName?.replace("_", " ") ?: "Unknown"
            }
        }

        private fun generateSummary(message: ChatMessage): String {
            val functionName = message.functionName
            val hasResult = message.functionResult != null

            return when (functionName) {
                "search_internet" -> if (hasResult) "Internet search completed" else "Searching internet..."
                "get_weather" -> if (hasResult) "Weather information retrieved" else "Getting weather..."
                "analyze_vision" -> if (hasResult) "Image analysis completed" else "Analyzing image..."
                "play_animation" -> if (hasResult) "Animation played" else "Playing animation..."
                else -> if (hasResult) "Function completed" else "Function executing..."
            }
        }

        private fun formatJson(json: String?): String {
            if (json.isNullOrEmpty()) return ""

            // Simple formatting - add line breaks after commas and format braces
            return json.replace(",", ",\n")
                .replace("{", "{\n  ")
                .replace("}", "\n}")
                .replace("\":", "\": ")
        }
    }
}

