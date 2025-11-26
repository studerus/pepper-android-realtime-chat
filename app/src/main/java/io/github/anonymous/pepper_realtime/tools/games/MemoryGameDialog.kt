package io.github.anonymous.pepper_realtime.tools.games

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.anonymous.pepper_realtime.R
import io.github.anonymous.pepper_realtime.tools.ToolContext
import io.github.anonymous.pepper_realtime.ui.DynamicGridSpacingItemDecoration
import java.util.Locale

class MemoryGameDialog(
    private val context: Context,
    private val toolContext: ToolContext?,
    private val closeListener: GameClosedListener?
) {

    fun interface GameClosedListener {
        fun onGameClosed()
    }

    companion object {
        private const val TAG = "MemoryGameDialog"

        // Large symbol pools for random selection
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

        // Numbers of pairs needed for each difficulty
        private const val EASY_PAIRS = 4
        private const val MEDIUM_PAIRS = 8
        private const val HARD_PAIRS = 12
    }

    private var dialog: AlertDialog? = null

    // Game state
    private lateinit var cards: MutableList<MemoryCard>
    private var adapter: MemoryCardAdapter? = null
    private var moves = 0
    private var matchedPairs = 0
    private var totalPairs = 0
    private var startTime = 0L
    private var gameActive = false

    // Currently flipped cards
    private var firstFlippedCard: MemoryCard? = null
    private var secondFlippedCard: MemoryCard? = null
    private var processingMove = false

    // UI elements
    private lateinit var movesCounter: TextView
    private lateinit var pairsCounter: TextView
    private lateinit var timer: TextView
    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null

    fun show(difficulty: String) {
        Log.i(TAG, "Starting memory game with difficulty: $difficulty")

        // Setup game based on difficulty
        setupGame(difficulty)

        // Create and show dialog
        createDialog()

        // Send initial context update
        toolContext?.let {
            val initialMessage = String.format(
                Locale.US,
                "User started a memory game (difficulty: %s, %d card pairs). The game is now running.",
                difficulty, totalPairs
            )
            it.sendAsyncUpdate(initialMessage, false)
        }
    }

    private fun setupGame(difficulty: String) {
        // Reset game state
        moves = 0
        matchedPairs = 0
        gameActive = true
        firstFlippedCard = null
        secondFlippedCard = null
        processingMove = false
        startTime = System.currentTimeMillis()

        // Determine number of pairs based on difficulty
        totalPairs = when (difficulty.lowercase()) {
            "easy" -> EASY_PAIRS
            "hard" -> HARD_PAIRS
            else -> MEDIUM_PAIRS // medium
        }

        // Randomly select symbols from the large pool
        val symbols = selectRandomSymbols(totalPairs)

        // Create cards (2 of each symbol)
        cards = mutableListOf()
        var cardId = 0
        for (symbol in symbols) {
            cards.add(MemoryCard(cardId++, symbol))
            cards.add(MemoryCard(cardId++, symbol))
        }

        // Shuffle cards
        cards.shuffle()

        Log.i(TAG, "Game setup complete: ${cards.size} cards, $totalPairs pairs")
    }

    /**
     * Randomly selects the specified number of unique symbols from the symbol pool
     */
    private fun selectRandomSymbols(count: Int): Array<String> {
        val actualCount = if (count > ALL_SYMBOLS.size) {
            Log.w(TAG, "Requested more symbols ($count) than available (${ALL_SYMBOLS.size})")
            ALL_SYMBOLS.size
        } else {
            count
        }

        // Create a list of all symbols and shuffle it
        val availableSymbols = ALL_SYMBOLS.toMutableList()
        availableSymbols.shuffle()

        // Take the first 'count' symbols from the shuffled list
        val selectedSymbols = Array(actualCount) { availableSymbols[it] }

        Log.i(TAG, "Selected $actualCount random symbols for game")
        return selectedSymbols
    }

    private fun createDialog() {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_memory_game, null)

        // Initialize UI elements
        movesCounter = dialogView.findViewById(R.id.memory_moves_counter)
        pairsCounter = dialogView.findViewById(R.id.memory_pairs_counter)
        timer = dialogView.findViewById(R.id.memory_timer)
        val closeButton: Button = dialogView.findViewById(R.id.memory_close_button)

        // Setup RecyclerView with dynamic card sizing
        val recyclerView: RecyclerView = dialogView.findViewById(R.id.memory_game_grid)
        val (columns, rows) = when {
            totalPairs <= 4 -> 4 to 2 // 4x2 grid
            totalPairs <= 8 -> 4 to 4 // 4x4 grid
            else -> 6 to 4 // 6x4 grid
        }

        // Calculate optimal card height based on available screen space
        val cardHeight = calculateOptimalCardHeight(rows)

        val gridLayoutManager = GridLayoutManager(context, columns)
        recyclerView.layoutManager = gridLayoutManager

        // Add item decoration with calculated card height
        recyclerView.addItemDecoration(DynamicGridSpacingItemDecoration(columns, 4, true, cardHeight))

        // Calculate optimal text size based on card height
        val textSize = calculateOptimalTextSize(cardHeight)

        adapter = MemoryCardAdapter(cards, { position -> onCardClicked(position) }, textSize)
        recyclerView.adapter = adapter

        // Update UI
        updateUI()

        // Setup close button
        closeButton.setOnClickListener { closeGame() }

        // Create dialog
        val builder = AlertDialog.Builder(context)
        builder.setView(dialogView)
        builder.setCancelable(false)

        dialog = builder.create()
        dialog?.show()

        // Make dialog full-screen
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        // Start timer
        startTimer()
    }

    private fun onCardClicked(position: Int) {
        if (!gameActive || processingMove) return

        val clickedCard = cards[position]
        if (!clickedCard.canFlip()) return

        // Flip the card
        clickedCard.flip()
        adapter?.notifyItemChanged(position)

        val cardInfo = String.format(Locale.US, "Position %d (Symbol: %s)", position + 1, clickedCard.symbol)

        val first = firstFlippedCard
        if (first == null) {
            // First card of the pair
            firstFlippedCard = clickedCard

            // Send context update
            toolContext?.let {
                val message = String.format(Locale.US, "User revealed the first card: %s", cardInfo)
                it.sendAsyncUpdate(message, false)
            }
        } else if (secondFlippedCard == null) {
            // Second card of the pair
            secondFlippedCard = clickedCard
            moves++
            processingMove = true

            // Check for match
            val isMatch = first.symbol == clickedCard.symbol

            if (isMatch) {
                // Match found!
                first.setMatched(true)
                clickedCard.setMatched(true)
                matchedPairs++

                // Update UI for matched cards
                val firstCardIndex = cards.indexOf(first)
                val secondCardIndex = cards.indexOf(clickedCard)
                adapter?.notifyItemChanged(firstCardIndex)
                adapter?.notifyItemChanged(secondCardIndex)

                // Check if game is complete and send appropriate message
                if (matchedPairs == totalPairs) {
                    // Game complete - send final message with completion and statistics
                    gameCompleteWithFinalPair(first, firstCardIndex, cardInfo)
                } else {
                    // Game continues - send pair found message with feedback request
                    toolContext?.let {
                        val message = String.format(
                            Locale.US,
                            "User found a matching pair! First card: %s, Second card: %s. " +
                                    "Current score: %d of %d pairs found, %d moves. Give a very short feedback to the user.",
                            getCardInfo(first, firstCardIndex),
                            cardInfo, matchedPairs, totalPairs, moves
                        )
                        it.sendAsyncUpdate(message, true)
                    }
                }

                // Reset for next turn
                firstFlippedCard = null
                secondFlippedCard = null
                processingMove = false

            } else {
                // No match - flip cards back after delay
                toolContext?.let {
                    val message = String.format(
                        Locale.US,
                        "User revealed two different cards: %s and %s. Cards will be flipped back. Current score: %d moves.",
                        getCardInfo(first, cards.indexOf(first)),
                        cardInfo, moves
                    )
                    it.sendAsyncUpdate(message, false)
                }

                Handler(Looper.getMainLooper()).postDelayed({
                    first.flipBack()
                    clickedCard.flipBack()
                    val firstCardIndex = cards.indexOf(first)
                    val secondCardIndex = cards.indexOf(clickedCard)
                    adapter?.notifyItemChanged(firstCardIndex)
                    adapter?.notifyItemChanged(secondCardIndex)

                    firstFlippedCard = null
                    secondFlippedCard = null
                    processingMove = false
                }, 1500)
            }

            updateUI()
        }
    }

    private fun getCardInfo(card: MemoryCard, position: Int): String {
        return String.format(Locale.US, "Position %d (Symbol: %s)", position + 1, card.symbol)
    }

    private fun gameCompleteWithFinalPair(firstCard: MemoryCard, firstCardIndex: Int, secondCardInfo: String) {
        gameActive = false
        stopTimer()

        val endTime = System.currentTimeMillis()
        val totalTimeMs = endTime - startTime
        val totalSeconds = (totalTimeMs / 1000).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60

        val timeString = String.format(Locale.US, "%02d:%02d", minutes, seconds)

        // Send final message combining last pair found and game completion
        toolContext?.let {
            val message = String.format(
                Locale.US,
                "User found the final matching pair! First card: %s, Second card: %s. " +
                        "GAME COMPLETED! Final statistics: All %d pairs found in %d moves and %s time. " +
                        "Congratulate the user on completing the memory game!",
                getCardInfo(firstCard, firstCardIndex), secondCardInfo,
                totalPairs, moves, timeString
            )
            it.sendAsyncUpdate(message, true)
        }

        // Auto-close dialog after 5 seconds
        Handler(Looper.getMainLooper()).postDelayed({ closeGame() }, 5000)
    }

    private fun updateUI() {
        movesCounter.text = context.getString(R.string.memory_moves_format, moves)
        pairsCounter.text = context.getString(R.string.memory_pairs_format, matchedPairs, totalPairs)
    }

    private fun startTimer() {
        timerRunnable = object : Runnable {
            override fun run() {
                if (gameActive) {
                    val currentTime = System.currentTimeMillis()
                    val elapsedMs = currentTime - startTime
                    val totalSeconds = (elapsedMs / 1000).toInt()
                    val minutes = totalSeconds / 60
                    val seconds = totalSeconds % 60

                    timer.text = context.getString(R.string.memory_time_format, minutes, seconds)
                    timerHandler.postDelayed(this, 1000)
                }
            }
        }
        timerHandler.post(timerRunnable!!)
    }

    private fun stopTimer() {
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
    }

    private fun closeGame() {
        gameActive = false
        stopTimer()

        dialog?.let {
            if (it.isShowing) {
                it.dismiss()
            }
        }

        // Send context update about early closure if game wasn't completed
        if (matchedPairs < totalPairs) {
            toolContext?.let {
                val message = String.format(
                    Locale.US,
                    "User closed the memory game early. Score when closing: %d of %d pairs found, %d moves.",
                    matchedPairs, totalPairs, moves
                )
                it.sendAsyncUpdate(message, false)
            }
        }

        closeListener?.onGameClosed()

        Log.i(TAG, "Memory game closed")
    }

    /**
     * Calculate optimal card height based on available screen space
     */
    private fun calculateOptimalCardHeight(rows: Int): Int {
        return try {
            // Get screen dimensions
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)

            val screenHeight = displayMetrics.heightPixels

            // Convert dp to pixels for layout calculations
            val density = context.resources.displayMetrics.density

            // Estimated space used by other UI elements (in dp, converted to pixels)
            val titleBarHeight = (60 * density).toInt() // Title and close button
            val infoBarHeight = (50 * density).toInt()  // Moves, pairs, time counters
            val paddingAndMargins = (50 * density).toInt() // Dialog padding and margins
            val navigationBarHeight = (48 * density).toInt() // System navigation bar

            // Calculate available height for the grid
            val availableHeight = screenHeight - titleBarHeight - infoBarHeight - paddingAndMargins - navigationBarHeight

            // Calculate optimal card height
            val spacing = (4 * density).toInt() // Spacing between cards
            val totalSpacing = spacing * (rows + 1) // Top, bottom, and between rows
            var cardHeight = (availableHeight - totalSpacing) / rows

            // Ensure minimum and maximum card heights
            val minCardHeight = (60 * density).toInt()  // Minimum 60dp
            val maxCardHeight = (150 * density).toInt() // Maximum 150dp

            cardHeight = maxOf(minCardHeight, minOf(maxCardHeight, cardHeight))

            Log.i(TAG, "Calculated card height: ${cardHeight}px for $rows rows")
            cardHeight

        } catch (e: Exception) {
            Log.e(TAG, "Error calculating card height", e)
            // Fallback to default height
            (80 * context.resources.displayMetrics.density).toInt()
        }
    }

    /**
     * Calculate optimal text size based on card height and difficulty
     */
    private fun calculateOptimalTextSize(cardHeightPx: Int): Float {
        val density = context.resources.displayMetrics.density

        // Convert card height back to dp for calculation
        val cardHeightDp = cardHeightPx / density

        // Scale text size proportionally to card height, with bonus for easy difficulty
        val textSizeSp = if (totalPairs <= 4) {
            // Easy difficulty - extra large symbols since there's much more space
            when {
                cardHeightDp <= 80 -> 44f // Much larger for small cards
                cardHeightDp <= 120 -> 56f // Much larger for medium cards
                else -> 72f // Extra extra large for easy mode
            }
        } else {
            // Medium/Hard difficulty - moderately larger than before
            when {
                cardHeightDp <= 80 -> 38f // Larger small cards (increased from 32)
                cardHeightDp <= 120 -> 46f // Larger medium cards (increased from 40)
                else -> 58f // Larger large cards (increased from 52)
            }
        }

        Log.i(TAG, "Calculated text size: ${textSizeSp}sp for card height ${cardHeightDp}dp (pairs: $totalPairs)")
        return textSizeSp
    }
}

