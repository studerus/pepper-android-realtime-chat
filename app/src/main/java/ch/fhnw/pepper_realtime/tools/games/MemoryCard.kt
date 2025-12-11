package ch.fhnw.pepper_realtime.tools.games

/**
 * Represents a card in the memory game.
 * Immutable data class - create new instances for state changes.
 * This enables Compose to detect changes properly.
 */
data class MemoryCard(
    val id: Int,
    val symbol: String,
    val isFlipped: Boolean = false,
    val isMatched: Boolean = false
) {
    fun canFlip(): Boolean = !isFlipped && !isMatched

    fun flip(): MemoryCard = if (canFlip()) copy(isFlipped = true) else this

    fun flipBack(): MemoryCard = if (!isMatched) copy(isFlipped = false) else this

    fun setMatched(): MemoryCard = copy(isMatched = true, isFlipped = true)
}

