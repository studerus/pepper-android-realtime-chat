package io.github.anonymous.pepper_realtime.tools.games

/**
 * Represents a card in the memory game
 */
class MemoryCard(
    val id: Int,
    val symbol: String
) {
    var isFlipped: Boolean = false
        private set

    var isMatched: Boolean = false
        private set

    fun setMatched(matched: Boolean) {
        isMatched = matched
        if (matched) {
            isFlipped = true // Matched cards stay flipped
        }
    }

    fun canFlip(): Boolean = !isFlipped && !isMatched

    fun flip() {
        if (canFlip()) {
            isFlipped = true
        }
    }

    fun flipBack() {
        if (!isMatched) {
            isFlipped = false
        }
    }
}

