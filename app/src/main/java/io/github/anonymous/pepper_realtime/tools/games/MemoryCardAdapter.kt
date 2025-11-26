package io.github.anonymous.pepper_realtime.tools.games

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.github.anonymous.pepper_realtime.R

class MemoryCardAdapter(
    private val cards: List<MemoryCard>,
    private val listener: OnCardClickListener?,
    private val textSize: Float
) : RecyclerView.Adapter<MemoryCardAdapter.MemoryCardViewHolder>() {

    fun interface OnCardClickListener {
        fun onCardClicked(position: Int)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemoryCardViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_memory_card, parent, false)
        return MemoryCardViewHolder(view)
    }

    override fun onBindViewHolder(holder: MemoryCardViewHolder, position: Int) {
        val card = cards[position]
        holder.bind(card, position)
    }

    override fun getItemCount(): Int = cards.size

    inner class MemoryCardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardView: CardView = itemView as CardView
        private val cardText: TextView = itemView.findViewById(R.id.memory_card_text)

        fun bind(card: MemoryCard, position: Int) {
            // Set dynamic text size
            cardText.textSize = textSize

            // Update card appearance based on state
            if (card.isFlipped || card.isMatched) {
                cardText.text = card.symbol
                if (card.isMatched) {
                    // Matched cards have green background
                    cardView.setCardBackgroundColor(ContextCompat.getColor(itemView.context, R.color.correct_green))
                    cardText.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.white))
                } else {
                    // Flipped but not matched - normal color
                    cardView.setCardBackgroundColor(ContextCompat.getColor(itemView.context, android.R.color.white))
                    cardText.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.black))
                }
            } else {
                // Face down card
                cardText.text = itemView.context.getString(R.string.memory_card_face_down)
                cardView.setCardBackgroundColor(ContextCompat.getColor(itemView.context, R.color.purple_500))
                cardText.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.white))
            }

            // Set click listener
            cardView.setOnClickListener {
                if (listener != null && card.canFlip()) {
                    listener.onCardClicked(position)
                }
            }

            // Disable clicking for matched cards
            cardView.isClickable = !card.isMatched
        }
    }
}

