package com.example.pepper_test2;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class MemoryCardAdapter extends RecyclerView.Adapter<MemoryCardAdapter.MemoryCardViewHolder> {

    public interface OnCardClickListener {
        void onCardClicked(int position);
    }

    private final List<MemoryCard> cards;
    private final OnCardClickListener listener;
    private final float textSize;

    public MemoryCardAdapter(List<MemoryCard> cards, OnCardClickListener listener, float textSize) {
        this.cards = cards;
        this.listener = listener;
        this.textSize = textSize;
    }

    @NonNull
    @Override
    public MemoryCardViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_memory_card, parent, false);
        return new MemoryCardViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MemoryCardViewHolder holder, int position) {
        MemoryCard card = cards.get(position);
        holder.bind(card, position);
    }

    @Override
    public int getItemCount() {
        return cards.size();
    }

    class MemoryCardViewHolder extends RecyclerView.ViewHolder {
        private final CardView cardView;
        private final TextView cardText;

        public MemoryCardViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = (CardView) itemView;
            cardText = itemView.findViewById(R.id.memory_card_text);
        }

        public void bind(MemoryCard card, int position) {
            // Set dynamic text size
            cardText.setTextSize(textSize);
            
            // Update card appearance based on state
            if (card.isFlipped() || card.isMatched()) {
                cardText.setText(card.getSymbol());
                if (card.isMatched()) {
                    // Matched cards have green background
                    cardView.setCardBackgroundColor(ContextCompat.getColor(itemView.getContext(), R.color.correct_green));
                    cardText.setTextColor(ContextCompat.getColor(itemView.getContext(), android.R.color.white));
                } else {
                    // Flipped but not matched - normal color
                    cardView.setCardBackgroundColor(ContextCompat.getColor(itemView.getContext(), android.R.color.white));
                    cardText.setTextColor(ContextCompat.getColor(itemView.getContext(), android.R.color.black));
                }
            } else {
                // Face down card
                cardText.setText(itemView.getContext().getString(R.string.memory_card_face_down));
                cardView.setCardBackgroundColor(ContextCompat.getColor(itemView.getContext(), R.color.purple_500));
                cardText.setTextColor(ContextCompat.getColor(itemView.getContext(), android.R.color.white));
            }

            // Set click listener
            cardView.setOnClickListener(v -> {
                if (listener != null && card.canFlip()) {
                    listener.onCardClicked(position);
                }
            });

            // Disable clicking for matched cards
            cardView.setClickable(!card.isMatched());
        }
    }
}
