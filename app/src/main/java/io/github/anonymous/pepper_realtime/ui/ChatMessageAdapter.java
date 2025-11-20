package io.github.anonymous.pepper_realtime.ui;

import io.github.anonymous.pepper_realtime.R;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.RotateAnimation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.List;

public class ChatMessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_USER = 1;
    private static final int VIEW_TYPE_ROBOT = 2;
    private static final int VIEW_TYPE_FUNCTION_CALL = 3;

    private final List<ChatMessage> messages;

    public ChatMessageAdapter(List<ChatMessage> messages) {
        this.messages = messages;
    }

    @Override
    public int getItemViewType(int position) {
        ChatMessage message = messages.get(position);
        if (message.getType() == ChatMessage.Type.FUNCTION_CALL) {
            return VIEW_TYPE_FUNCTION_CALL;
        }
        if (message.getSender() == ChatMessage.Sender.USER) {
            return VIEW_TYPE_USER;
        }
        return VIEW_TYPE_ROBOT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        
        return switch (viewType) {
            case VIEW_TYPE_FUNCTION_CALL -> {
                View functionView = inflater.inflate(R.layout.item_function_call, parent, false);
                yield new FunctionCallViewHolder(functionView, this);
            }
            case VIEW_TYPE_USER -> {
                View userView = inflater.inflate(R.layout.item_chat_user, parent, false);
                yield new MessageViewHolder(userView);
            }
            default -> {
                View robotView = inflater.inflate(R.layout.item_chat_robot, parent, false);
                yield new MessageViewHolder(robotView);
            }
        };
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ChatMessage message = messages.get(position);
        
        if (holder instanceof FunctionCallViewHolder functionHolder) {
            functionHolder.bind(message);
        } else if (holder instanceof MessageViewHolder messageHolder) {
            String text = message.getMessage();
            messageHolder.messageTextView.setText(text);

            FrameLayout bubbleContainer = messageHolder.itemView.findViewById(R.id.bubble_container);

            if (TextUtils.isEmpty(text)) {
                if (bubbleContainer != null) {
                    bubbleContainer.setVisibility(View.GONE);
                }
            } else {
                if (bubbleContainer != null) {
                    bubbleContainer.setVisibility(View.VISIBLE);
                    // Forcefully apply a programmatically created drawable to bypass themes
                    if (message.getSender() == ChatMessage.Sender.ROBOT) {
                        GradientDrawable robotBubble = new GradientDrawable();
                        robotBubble.setShape(GradientDrawable.RECTANGLE);
                        robotBubble.setColor(Color.parseColor("#E5E5EA")); // Light gray
                        robotBubble.setCornerRadius(dpToPx(messageHolder.itemView.getContext(), 20));
                        bubbleContainer.setBackground(robotBubble);
                    }
                }
                int screenMax = (int) (messageHolder.itemView.getResources().getDisplayMetrics().widthPixels * 0.8f);
                messageHolder.messageTextView.setMaxWidth(screenMax);
            }

            ImageView imageView = messageHolder.messageImageView;
            if (imageView != null) {
                String path = message.getImagePath();
                if (path != null && new File(path).exists()) {
                    imageView.setVisibility(View.VISIBLE);
                    int thumbPx = dpToPx(messageHolder.itemView.getContext(), 220);
                    imageView.setImageBitmap(decodeSampledBitmapFromPath(path, thumbPx, thumbPx));
                    imageView.setOnClickListener(v -> showImageOverlay(v, path));
                } else {
                    imageView.setVisibility(View.GONE);
                    imageView.setOnClickListener(null);
                }
            }
        }
    }

    private int dpToPx(Context context, int dp) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return Math.round(dp * (metrics.densityDpi / 160f));
    }

    private void showImageOverlay(View clickedView, String imagePath) {
        // Find the root activity view to add overlay
        Activity activity = (Activity) clickedView.getContext();
        View rootView = activity.findViewById(android.R.id.content);
        View imageOverlay = activity.findViewById(R.id.image_overlay);
        
        if (imageOverlay != null && rootView != null) {
            ImageView overlayImage = imageOverlay.findViewById(R.id.overlay_image);
            
            if (overlayImage != null) {
                // Load image
                Bitmap bitmap = decodeSampledBitmapFromPath(imagePath, 1024, 1024);
                overlayImage.setImageBitmap(bitmap);
                
                // Show overlay
                imageOverlay.setVisibility(View.VISIBLE);
                
                // Set click listener to close
                imageOverlay.setOnClickListener(v -> hideImageOverlay(activity));
            }
        }
    }
    
    private void hideImageOverlay(Activity activity) {
        View imageOverlay = activity.findViewById(R.id.image_overlay);
        if (imageOverlay != null) {
            imageOverlay.setVisibility(View.GONE);
        }
    }

    public static Bitmap decodeSampledBitmapFromPath(String path, int reqWidth, int reqHeight) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(path, options);
    }

    public static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    @Override
    public int getItemCount() { return messages.size(); }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        final TextView messageTextView;
        final ImageView messageImageView;

        public MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            messageTextView = itemView.findViewById(R.id.messageTextView);
            messageImageView = itemView.findViewById(R.id.messageImageView);
        }
    }
    
    static class FunctionCallViewHolder extends RecyclerView.ViewHolder {
        private final TextView functionIcon;
        private final TextView functionName;
        private final TextView functionStatus;
        private final TextView functionSummary;
        private final TextView functionArgs;
        private final TextView functionResult;
        private final TextView functionResultLabel;
        private final ImageView expandIcon;
        private final LinearLayout detailsContainer;
        private final ChatMessageAdapter adapter;
        
        public FunctionCallViewHolder(@NonNull View itemView, ChatMessageAdapter adapter) {
            super(itemView);
            this.adapter = adapter;
            functionIcon = itemView.findViewById(R.id.function_icon);
            functionName = itemView.findViewById(R.id.function_name);
            functionStatus = itemView.findViewById(R.id.function_status);
            functionSummary = itemView.findViewById(R.id.function_summary);
            functionArgs = itemView.findViewById(R.id.function_args);
            functionResult = itemView.findViewById(R.id.function_result);
            functionResultLabel = itemView.findViewById(R.id.function_result_label);
            expandIcon = itemView.findViewById(R.id.expand_icon);
            detailsContainer = itemView.findViewById(R.id.function_details_container);
            
            // Set up click listener for expand/collapse (no need to keep header as a field)
            LinearLayout header = itemView.findViewById(R.id.function_header);
            header.setOnClickListener(v -> toggleExpanded());
        }
        
        public void bind(ChatMessage message) {
            // Set function icon based on function name
            functionIcon.setText(getFunctionIcon(message.getFunctionName()));
            
            // Set function display name
            functionName.setText(getFunctionDisplayName(message.getFunctionName()));
            
            // Set status with appropriate colors
            boolean hasResult = message.getFunctionResult() != null;
            if (hasResult) {
                functionStatus.setText("✅");
                functionStatus.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.function_call_status_success));
            } else {
                functionStatus.setText("⏳");
                functionStatus.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.function_call_status_pending));
            }
            
            // Set summary
            functionSummary.setText(generateSummary(message));
            
            // Set arguments
            functionArgs.setText(formatJson(message.getFunctionArgs()));
            
            // Show/hide result
            if (hasResult) {
                functionResult.setText(formatJson(message.getFunctionResult()));
                functionResult.setVisibility(View.VISIBLE);
                functionResultLabel.setVisibility(View.VISIBLE);
            } else {
                functionResult.setVisibility(View.GONE);
                functionResultLabel.setVisibility(View.GONE);
            }
            
            // Set expand/collapse state
            updateExpandState(message.isExpanded());
        }
        
        private void toggleExpanded() {
            int position = getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION) return;
            if (position >= adapter.messages.size()) return;
            ChatMessage message = adapter.messages.get(position);
            boolean newState = !message.isExpanded();
            message.setExpanded(newState);
            updateExpandState(newState);
        }
        
        private void updateExpandState(boolean expanded) {
            detailsContainer.setVisibility(expanded ? View.VISIBLE : View.GONE);
            
            // Rotate expand icon
            RotateAnimation rotate = new RotateAnimation(
                expanded ? 0 : 180, expanded ? 180 : 0,
                RotateAnimation.RELATIVE_TO_SELF, 0.5f,
                RotateAnimation.RELATIVE_TO_SELF, 0.5f
            );
            rotate.setDuration(200);
            rotate.setFillAfter(true);
            expandIcon.startAnimation(rotate);
        }
        
        private String getFunctionIcon(String functionName) {
            return switch (functionName) {
                case "search_internet" -> "🌐";
                case "get_weather" -> "🌤️";
                case "analyze_vision" -> "👁️";
                case "play_animation" -> "🤖";
                case "get_current_datetime" -> "🕐";
                case "get_random_joke" -> "😄";
                case "present_quiz_question" -> "❓";
                case "start_memory_game" -> "🧠";
                default -> "🔧";
            };
        }
        
        private String getFunctionDisplayName(String functionName) {
            return switch (functionName) {
                case "search_internet" -> "Internet Search";
                case "get_weather" -> "Weather";
                case "analyze_vision" -> "Vision Analysis";
                case "play_animation" -> "Animation";
                case "get_current_datetime" -> "Date/Time";
                case "get_random_joke" -> "Random Joke";
                case "present_quiz_question" -> "Quiz Question";
                case "start_memory_game" -> "Memory Game";
                default -> functionName.replace("_", " ");
            };
        }
        
        private String generateSummary(ChatMessage message) {
            String functionName = message.getFunctionName();
            boolean hasResult = message.getFunctionResult() != null;
            
            return switch (functionName) {
                case "search_internet" -> hasResult ? "Internet search completed" : "Searching internet...";
                case "get_weather" -> hasResult ? "Weather information retrieved" : "Getting weather...";
                case "analyze_vision" -> hasResult ? "Image analysis completed" : "Analyzing image...";
                case "play_animation" -> hasResult ? "Animation played" : "Playing animation...";
                default -> hasResult ? "Function completed" : "Function executing...";
            };
        }
        
        private String formatJson(String json) {
            if (json == null || json.isEmpty()) return "";
            
            // Simple formatting - add line breaks after commas and format braces
            return json.replace(",", ",\n")
                      .replace("{", "{\n  ")
                      .replace("}", "\n}")
                      .replace("\":", "\": ");
        }
    }
}

