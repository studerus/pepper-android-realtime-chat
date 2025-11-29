package io.github.anonymous.pepper_realtime.tools.games

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.anonymous.pepper_realtime.tools.ToolContext
import java.util.Locale

/**
 * Manager for Memory Game logic and state.
 * Handles game rules, timer, and communication with AI.
 */
object MemoryGameManager {

    private const val TAG = "MemoryGameManager"

    // Game Constants
    private val ALL_SYMBOLS = arrayOf(
        // Objects & Items
        "🌟", "🎈", "🍎", "🏆", "🎵", "🌺", "⚽", "🎯", "🚗", "🏠", "📚", "🎨",
        // Animals
        "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁", "🐮",
        // Food & Drinks
        "🍕", "🍔", "🍟", "🌭", "🍿", "🎂", "🍪", "🍩", "🍎", "🍌", "🍇", "🍓",
        // Nature
        "🌲", "🌳", "🌴", "🌵", "🌸", "🌼", "🌻", "🌹", "🌿", "🍀", "🌾", "🌙",
        // Transportation
        "🚗", "🚕", "🚙", "🚌", "🚎", "🏎️", "🚓", "🚑", "🚒", "🚐", "🛻", "🚚",
        // Sports & Activities
        "⚽", "🏀", "🏈", "⚾", "🥎", "🎾", "🏐", "🏓", "🥇", "🏆", "🎯", "🎳",
        // Technology & Tools
        "💻", "📱", "⌨️", "🖥️", "🖱️", "🔧", "🔨", "⚙️", "🔩", "🛠️", "⚡", "🔋"
    )

    private const val EASY_PAIRS = 4
    private const val MEDIUM_PAIRS = 8
    private const val HARD_PAIRS = 12

    // Game State Class
    data class MemoryGameState(
        val isVisible: Boolean = false,
        val cards: List<MemoryCard> = emptyList(),
        val moves: Int = 0,
        val matchedPairs: Int = 0,
        val totalPairs: Int = 0,
        val timeString: String = "00:00",
        val isGameActive: Boolean = false
    )

    // Mutable State for Compose
    var gameState by mutableStateOf(MemoryGameState())
        private set

    // Internal State
    private var startTime = 0L
    private var firstFlippedCard: MemoryCard? = null
    private var firstFlippedCardIndex: Int = -1
    private var secondFlippedCard: MemoryCard? = null
    private var secondFlippedCardIndex: Int = -1
    private var processingMove = false
    private var toolContext: ToolContext? = null
    
    // Timer
    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null

    /**
     * Start a new Memory Game
     */
    fun startGame(difficulty: String, context: ToolContext): Boolean {
        if (!context.hasUi()) {
            Log.w(TAG, "Cannot start game - no UI context available")
            return false
        }

        toolContext = context
        
        // Determine number of pairs
        val totalPairs = when (difficulty.lowercase()) {
            "easy" -> EASY_PAIRS
            "hard" -> HARD_PAIRS
            else -> MEDIUM_PAIRS
        }

        // Setup cards
        val cards = setupCards(totalPairs)
        
        // Reset state
        startTime = System.currentTimeMillis()
        firstFlippedCard = null
        secondFlippedCard = null
        processingMove = false
        
        // Update Game State
        gameState = MemoryGameState(
            isVisible = true,
            cards = cards,
            totalPairs = totalPairs,
            isGameActive = true
        )

        // Start Timer
        startTimer()

        // Notify AI
        val initialMessage = String.format(
            Locale.US,
            "User started a memory game (difficulty: %s, %d card pairs). The game is now running.",
            difficulty, totalPairs
        )
        context.sendAsyncUpdate(initialMessage, false)

        Log.i(TAG, "Memory game started with difficulty: $difficulty")
        return true
    }

    private fun setupCards(pairCount: Int): List<MemoryCard> {
        val symbols = selectRandomSymbols(pairCount)
        val cardList = mutableListOf<MemoryCard>()
        var cardId = 0
        
        Log.i(TAG, "Setting up game with ${symbols.size} symbols: ${symbols.joinToString()}")
        
        for (symbol in symbols) {
            // Create a new instance for each card to ensure they are distinct objects
            cardList.add(MemoryCard(cardId++, symbol))
            cardList.add(MemoryCard(cardId++, symbol))
        }
        
        Log.i(TAG, "Created ${cardList.size} cards before shuffle")
        cardList.shuffle()
        
        // Log the final grid for debugging
        val gridString = cardList.map { "${it.id}:${it.symbol}" }.joinToString(", ")
        Log.i(TAG, "Final card layout: $gridString")
        
        return cardList
    }

    private fun selectRandomSymbols(count: Int): Array<String> {
        val actualCount = minOf(count, ALL_SYMBOLS.size)
        val availableSymbols = ALL_SYMBOLS.toMutableList()
        availableSymbols.shuffle()
        return Array(actualCount) { availableSymbols[it] }
    }

    /**
     * Handle card click
     */
    fun onCardClick(cardId: Int) {
        if (!gameState.isGameActive || processingMove) return

        // Find card by ID
        val cards = gameState.cards
        val cardIndex = cards.indexOfFirst { it.id == cardId }
        if (cardIndex == -1) return
        
        val clickedCard = cards[cardIndex]
        if (!clickedCard.canFlip()) return

        // Flip card - create new card instance (immutable)
        val flippedCard = clickedCard.flip()
        updateCard(cardIndex, flippedCard)

        val cardInfo = "Position ${cardIndex + 1} (Symbol: ${flippedCard.symbol})"

        val first = firstFlippedCard
        if (first == null) {
            // First card
            firstFlippedCard = flippedCard
            firstFlippedCardIndex = cardIndex
            toolContext?.sendAsyncUpdate("User revealed the first card: $cardInfo", false)
        } else if (secondFlippedCard == null) {
            // Second card
            secondFlippedCard = flippedCard
            secondFlippedCardIndex = cardIndex
            val newMoves = gameState.moves + 1
            
            // Update moves immediately
            gameState = gameState.copy(moves = newMoves)
            
            processingMove = true

            if (first.symbol == flippedCard.symbol) {
                // Match!
                handleMatch(newMoves)
            } else {
                // No Match
                handleMismatch(newMoves)
            }
        }
    }

    private fun handleMatch(currentMoves: Int) {
        val first = firstFlippedCard ?: return
        val second = secondFlippedCard ?: return
        
        // Mark both cards as matched (create new instances)
        updateCard(firstFlippedCardIndex, first.setMatched())
        updateCard(secondFlippedCardIndex, second.setMatched())
        
        val newMatchedPairs = gameState.matchedPairs + 1
        gameState = gameState.copy(matchedPairs = newMatchedPairs)

        val firstInfo = "Symbol: ${first.symbol}"
        val secondInfo = "Symbol: ${second.symbol}"

        if (newMatchedPairs == gameState.totalPairs) {
            // Game Won
            gameComplete(currentMoves)
        } else {
            // Continue
            val message = String.format(
                Locale.US,
                "User found a matching pair! First card: %s, Second card: %s. Current score: %d of %d pairs found, %d moves. Give a very short feedback to the user.",
                firstInfo, secondInfo, newMatchedPairs, gameState.totalPairs, currentMoves
            )
            toolContext?.sendAsyncUpdate(message, true)
            
            resetTurn()
        }
    }

    private fun handleMismatch(currentMoves: Int) {
        val first = firstFlippedCard ?: return
        val second = secondFlippedCard ?: return
        val firstIdx = firstFlippedCardIndex
        val secondIdx = secondFlippedCardIndex
        
        val firstInfo = "Symbol: ${first.symbol}"
        val secondInfo = "Symbol: ${second.symbol}"
        
        val message = String.format(
            Locale.US,
            "User revealed two different cards: %s and %s. Cards will be flipped back. Current score: %d moves.",
            firstInfo, secondInfo, currentMoves
        )
        toolContext?.sendAsyncUpdate(message, false)

        // Delay flip back
        Handler(Looper.getMainLooper()).postDelayed({
            // Flip cards back (create new instances)
            val currentCards = gameState.cards.toMutableList()
            if (firstIdx in currentCards.indices) {
                currentCards[firstIdx] = currentCards[firstIdx].flipBack()
            }
            if (secondIdx in currentCards.indices) {
                currentCards[secondIdx] = currentCards[secondIdx].flipBack()
            }
            gameState = gameState.copy(cards = currentCards)
            resetTurn()
        }, 1500)
    }

    private fun resetTurn() {
        firstFlippedCard = null
        firstFlippedCardIndex = -1
        secondFlippedCard = null
        secondFlippedCardIndex = -1
        processingMove = false
    }

    private fun gameComplete(finalMoves: Int) {
        stopTimer()
        gameState = gameState.copy(isGameActive = false)
        
        val message = String.format(
            Locale.US,
            "GAME COMPLETED! Final statistics: All %d pairs found in %d moves and %s time. Congratulate the user on completing the memory game!",
            gameState.totalPairs, finalMoves, gameState.timeString
        )
        toolContext?.sendAsyncUpdate(message, true)

        // Auto close after delay
        Handler(Looper.getMainLooper()).postDelayed({
            dismissGame()
        }, 5000)
    }

    fun dismissGame() {
        stopTimer()
        gameState = MemoryGameState() // Reset to invisible
        Log.i(TAG, "Memory game dismissed")
    }

    private fun startTimer() {
        stopTimer() // Ensure no duplicates
        timerRunnable = object : Runnable {
            override fun run() {
                if (gameState.isGameActive) {
                    val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000
                    val minutes = elapsedSeconds / 60
                    val seconds = elapsedSeconds % 60
                    val timeString = String.format(Locale.US, "%02d:%02d", minutes, seconds)
                    
                    gameState = gameState.copy(timeString = timeString)
                    timerHandler.postDelayed(this, 1000)
                }
            }
        }
        timerHandler.post(timerRunnable!!)
    }

    private fun stopTimer() {
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        timerRunnable = null
    }
    
    /**
     * Update a card at a specific index with a new instance.
     * Creates a new list to trigger Compose recomposition.
     */
    private fun updateCard(index: Int, newCard: MemoryCard) {
        val updatedCards = gameState.cards.toMutableList()
        if (index in updatedCards.indices) {
            updatedCards[index] = newCard
            gameState = gameState.copy(cards = updatedCards)
        }
    }
}

