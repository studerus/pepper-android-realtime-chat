package com.example.pepper_test2;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.List;

public class ChatMessageAdapter extends RecyclerView.Adapter<ChatMessageAdapter.MessageViewHolder> {

    private static final int VIEW_TYPE_USER = 1;
    private static final int VIEW_TYPE_ROBOT = 2;

    private final List<ChatMessage> messages;

    public ChatMessageAdapter(List<ChatMessage> messages) {
        this.messages = messages;
    }

    @Override
    public int getItemViewType(int position) {
        ChatMessage message = messages.get(position);
        if (message.getSender() == ChatMessage.Sender.USER) {
            return VIEW_TYPE_USER;
        } else {
            return VIEW_TYPE_ROBOT;
        }
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view;
        if (viewType == VIEW_TYPE_USER) {
            view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat_user, parent, false);
        } else {
            view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat_robot, parent, false);
        }
        return new MessageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        ChatMessage message = messages.get(position);
        String text = message.getMessage();
        holder.messageTextView.setText(text);

        FrameLayout bubbleContainer = holder.itemView.findViewById(R.id.bubble_container);

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
                    robotBubble.setCornerRadius(dpToPx(holder.itemView.getContext(), 20));
                    bubbleContainer.setBackground(robotBubble);
                }
            }
            int screenMax = (int) (holder.itemView.getResources().getDisplayMetrics().widthPixels * 0.8f);
            holder.messageTextView.setMaxWidth(screenMax);
        }

        ImageView imageView = holder.messageImageView;
        if (imageView != null) {
            String path = message.getImagePath();
            if (path != null && new File(path).exists()) {
                imageView.setVisibility(View.VISIBLE);
                int thumbPx = dpToPx(holder.itemView.getContext(), 220);
                imageView.setImageBitmap(decodeSampledBitmapFromPath(path, thumbPx, thumbPx));
                imageView.setOnClickListener(v -> openFullscreen(v.getContext(), path));
            } else {
                imageView.setVisibility(View.GONE);
                imageView.setOnClickListener(null);
            }
        }
    }

    private int dpToPx(Context context, int dp) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return Math.round(dp * (metrics.densityDpi / 160f));
    }

    private void openFullscreen(Context context, String path) {
        Intent intent = new Intent(context, FullscreenImageActivity.class);
        intent.putExtra("image_path", path);
        context.startActivity(intent);
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
        TextView messageTextView;
        ImageView messageImageView;

        public MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            messageTextView = itemView.findViewById(R.id.messageTextView);
            messageImageView = itemView.findViewById(R.id.messageImageView);
        }
    }
}

