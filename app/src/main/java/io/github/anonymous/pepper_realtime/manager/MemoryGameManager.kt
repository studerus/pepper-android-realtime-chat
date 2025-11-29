package io.github.anonymous.pepper_realtime.manager

import android.util.Log
import io.github.anonymous.pepper_realtime.tools.games.MemoryCard
import io.github.anonymous.pepper_realtime.ui.MemoryGameInternalState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Memory game state and logic.
 * Extracted from ChatViewModel for better separation of concerns.
 */
@Singleton
class MemoryGameManager @Inject constructor() {

    companion object {
        private const val TAG = "MemoryGameManager"
        private const val AUTO_CLOSE_DELAY_MS = 5000L
        private const val MISMATCH_FLIP_DELAY_MS = 1500L
        private const val TIMER_INTERVAL_MS = 1000L
    }

    // Available symbols for cards
    private val allSymbols = arrayOf(
        "🌟", "🎈", "🍎", "🏆", "🎵", "🌺", "⚽", "🎯", "🚗", "🏠", "📚", "🎨",
        "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁", "🐮",
        "🍕", "🍔", "🍟", "🌭", "🍿", "🎂", "🍪", "🍩", "🍌", "🍇", "🍓",
        "🌲", "🌳", "🌴", "🌵", "🌸", "🌼", "🌻", "🌹", "🌿", "🍀", "🌾", "🌙"
    )

    // UI State
    private val _state = MutableStateFlow(MemoryGameInternalState())
    val state: StateFlow<MemoryGameInternalState> = _state.asStateFlow()

    // Callback for sending updates to the AI
    private var updateCallback: ((message: String, requestResponse: Boolean) -> Unit)? = null

    // Coroutine jobs
    private var timerJob: Job? = null
    private var autoCloseJob: Job? = null
    private var mismatchJob: Job? = null
    private var coroutineScope: CoroutineScope? = null

    /**
     * Set the coroutine scope for background operations.
     * Should be called with viewModelScope from the ViewModel.
     */
    fun setCoroutineScope(scope: CoroutineScope) {
        coroutineScope = scope
    }

    /**
     * Start a new Memory game.
     * @param difficulty "easy", "medium", or "hard"
     * @param onUpdate Callback for sending game updates to the AI
     * @return true if game started successfully
     */
    fun startGame(difficulty: String, onUpdate: (message: String, requestResponse: Boolean) -> Unit): Boolean {
        updateCallback = onUpdate

        val totalPairs = when (difficulty.lowercase()) {
            "easy" -> 4
            "hard" -> 12
            else -> 8
        }

        val cards = setupCards(totalPairs)

        _state.value = MemoryGameInternalState(
            isVisible = true,
            cards = cards,
            totalPairs = totalPairs,
            isGameActive = true,
            startTime = System.currentTimeMillis()
        )

        startTimer()

        val initialMessage = String.format(
            Locale.US,
            "User started a memory game (difficulty: %s, %d card pairs). The game is now running.",
            difficulty, totalPairs
        )
        onUpdate(initialMessage, false)

        Log.i(TAG, "Memory game started with difficulty: $difficulty")
        return true
    }

    /**
     * Check if a game is currently active.
     */
    fun isGameActive(): Boolean = _state.value.isVisible

    /**
     * Handle card click.
     * @param cardId The ID of the clicked card
     */
    fun onCardClick(cardId: Int) {
        val currentState = _state.value
        if (!currentState.isGameActive || currentState.processingMove) return

        val cardIndex = currentState.cards.indexOfFirst { it.id == cardId }
        if (cardIndex == -1) return

        val clickedCard = currentState.cards[cardIndex]
        if (!clickedCard.canFlip()) return

        val flippedCard = clickedCard.flip()
        updateCard(cardIndex, flippedCard)

        val cardInfo = "Position ${cardIndex + 1} (Symbol: ${flippedCard.symbol})"

        if (currentState.firstFlippedCardIndex == -1) {
            // First card
            _state.update { it.copy(firstFlippedCardIndex = cardIndex) }
            updateCallback?.invoke("User revealed the first card: $cardInfo", false)
        } else if (currentState.secondFlippedCardIndex == -1) {
            // Second card
            val newMoves = currentState.moves + 1
            _state.update {
                it.copy(secondFlippedCardIndex = cardIndex, moves = newMoves, processingMove = true)
            }

            val firstCard = currentState.cards[currentState.firstFlippedCardIndex]
            if (firstCard.symbol == flippedCard.symbol) {
                handleMatch(newMoves)
            } else {
                handleMismatch(newMoves)
            }
        }
    }

    /**
     * Dismiss the game dialog and reset state.
     */
    fun dismissGame() {
        stopTimer()
        autoCloseJob?.cancel()
        autoCloseJob = null
        mismatchJob?.cancel()
        mismatchJob = null
        _state.value = MemoryGameInternalState()
        updateCallback = null
        Log.i(TAG, "Memory game dismissed")
    }

    private fun setupCards(pairCount: Int): List<MemoryCard> {
        val actualCount = minOf(pairCount, allSymbols.size)
        val availableSymbols = allSymbols.toMutableList().shuffled()
        val cardList = mutableListOf<MemoryCard>()
        var cardId = 0

        for (i in 0 until actualCount) {
            val symbol = availableSymbols[i]
            cardList.add(MemoryCard(cardId++, symbol))
            cardList.add(MemoryCard(cardId++, symbol))
        }

        return cardList.shuffled()
    }

    private fun handleMatch(currentMoves: Int) {
        val currentState = _state.value
        val firstIdx = currentState.firstFlippedCardIndex
        val secondIdx = currentState.secondFlippedCardIndex

        val updatedCards = currentState.cards.toMutableList()
        updatedCards[firstIdx] = updatedCards[firstIdx].setMatched()
        updatedCards[secondIdx] = updatedCards[secondIdx].setMatched()

        val newMatchedPairs = currentState.matchedPairs + 1

        _state.update {
            it.copy(
                cards = updatedCards,
                matchedPairs = newMatchedPairs,
                firstFlippedCardIndex = -1,
                secondFlippedCardIndex = -1,
                processingMove = false
            )
        }

        val firstInfo = "Symbol: ${currentState.cards[firstIdx].symbol}"
        val secondInfo = "Symbol: ${currentState.cards[secondIdx].symbol}"

        if (newMatchedPairs == currentState.totalPairs) {
            gameComplete(currentMoves)
        } else {
            val message = String.format(
                Locale.US,
                "User found a matching pair! First card: %s, Second card: %s. Current score: %d of %d pairs found, %d moves. Give a very short feedback to the user.",
                firstInfo, secondInfo, newMatchedPairs, currentState.totalPairs, currentMoves
            )
            updateCallback?.invoke(message, true)
        }
    }

    private fun handleMismatch(currentMoves: Int) {
        val currentState = _state.value
        val firstIdx = currentState.firstFlippedCardIndex
        val secondIdx = currentState.secondFlippedCardIndex

        val firstInfo = "Symbol: ${currentState.cards[firstIdx].symbol}"
        val secondInfo = "Symbol: ${currentState.cards[secondIdx].symbol}"

        val message = String.format(
            Locale.US,
            "User revealed two different cards: %s and %s. Cards will be flipped back. Current score: %d moves.",
            firstInfo, secondInfo, currentMoves
        )
        updateCallback?.invoke(message, false)

        mismatchJob?.cancel()
        mismatchJob = coroutineScope?.launch {
            delay(MISMATCH_FLIP_DELAY_MS)
            _state.update { state ->
                val updatedCards = state.cards.toMutableList()
                if (firstIdx in updatedCards.indices) {
                    updatedCards[firstIdx] = updatedCards[firstIdx].flipBack()
                }
                if (secondIdx in updatedCards.indices) {
                    updatedCards[secondIdx] = updatedCards[secondIdx].flipBack()
                }
                state.copy(
                    cards = updatedCards,
                    firstFlippedCardIndex = -1,
                    secondFlippedCardIndex = -1,
                    processingMove = false
                )
            }
        }
    }

    private fun gameComplete(finalMoves: Int) {
        stopTimer()
        val currentState = _state.value
        _state.update { it.copy(isGameActive = false) }

        val message = String.format(
            Locale.US,
            "GAME COMPLETED! Final statistics: All %d pairs found in %d moves and %s time. Congratulate the user on completing the memory game!",
            currentState.totalPairs, finalMoves, currentState.timeString
        )
        updateCallback?.invoke(message, true)

        autoCloseJob?.cancel()
        autoCloseJob = coroutineScope?.launch {
            delay(AUTO_CLOSE_DELAY_MS)
            dismissGame()
        }
    }

    private fun startTimer() {
        stopTimer()
        timerJob = coroutineScope?.launch {
            while (true) {
                val currentState = _state.value
                if (!currentState.isGameActive) break

                val elapsedSeconds = (System.currentTimeMillis() - currentState.startTime) / 1000
                val minutes = elapsedSeconds / 60
                val seconds = elapsedSeconds % 60
                val timeString = String.format(Locale.US, "%02d:%02d", minutes, seconds)

                _state.update { it.copy(timeString = timeString) }
                delay(TIMER_INTERVAL_MS)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun updateCard(index: Int, newCard: MemoryCard) {
        _state.update { state ->
            val updatedCards = state.cards.toMutableList()
            if (index in updatedCards.indices) {
                updatedCards[index] = newCard
            }
            state.copy(cards = updatedCards)
        }
    }
}

