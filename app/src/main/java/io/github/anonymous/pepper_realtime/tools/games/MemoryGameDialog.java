package io.github.anonymous.pepper_realtime.tools.games;

import io.github.anonymous.pepper_realtime.R;
import io.github.anonymous.pepper_realtime.DynamicGridSpacingItemDecoration;

import android.app.AlertDialog;
import android.content.Context;
import io.github.anonymous.pepper_realtime.tools.ToolContext;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MemoryGameDialog {
    
    public interface GameClosedListener {
        void onGameClosed();
    }

    private static final String TAG = "MemoryGameDialog";
    
    private final Context context;
    private final ToolContext toolContext;
    private final GameClosedListener closeListener;
    private AlertDialog dialog;
    
    // Game state
    private List<MemoryCard> cards;
    private MemoryCardAdapter adapter;
    private int moves;
    private int matchedPairs;
    private int totalPairs;
    private long startTime;
    private boolean gameActive;
    
    // Currently flipped cards
    private MemoryCard firstFlippedCard;
    private MemoryCard secondFlippedCard;
    private boolean processingMove;
    
    // UI elements
    private TextView movesCounter;
    private TextView pairsCounter;
    private TextView timer;
    private final Handler timerHandler;
    private Runnable timerRunnable;

    // Large symbol pools for random selection
    private static final String[] ALL_SYMBOLS = {
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
    };
    
    // Numbers of pairs needed for each difficulty
    private static final int EASY_PAIRS = 4;
    private static final int MEDIUM_PAIRS = 8; 
    private static final int HARD_PAIRS = 12;

    public MemoryGameDialog(Context context, ToolContext toolContext, GameClosedListener closeListener) {
        this.context = context;
        this.toolContext = toolContext;
        this.closeListener = closeListener;
        this.timerHandler = new Handler(Looper.getMainLooper());
    }

    public void show(String difficulty) {
        Log.i(TAG, "Starting memory game with difficulty: " + difficulty);
        
        // Setup game based on difficulty
        setupGame(difficulty);
        
        // Create and show dialog
        createDialog();
        
        // Send initial context update
        if (toolContext != null) {
            String initialMessage = String.format(Locale.US,
                "User started a memory game (difficulty: %s, %d card pairs). The game is now running.",
                difficulty, totalPairs);
            toolContext.sendAsyncUpdate(initialMessage, false);
        }
    }

    private void setupGame(String difficulty) {
        // Reset game state
        moves = 0;
        matchedPairs = 0;
        gameActive = true;
        firstFlippedCard = null;
        secondFlippedCard = null;
        processingMove = false;
        startTime = System.currentTimeMillis();
        
        // Determine number of pairs based on difficulty
        switch (difficulty.toLowerCase()) {
            case "easy":
                totalPairs = EASY_PAIRS;
                break;
            case "hard":
                totalPairs = HARD_PAIRS;
                break;
            default: // medium
                totalPairs = MEDIUM_PAIRS;
                break;
        }
        
        // Randomly select symbols from the large pool
        String[] symbols = selectRandomSymbols(totalPairs);
        
        // Create cards (2 of each symbol)
        cards = new ArrayList<>();
        int cardId = 0;
        for (String symbol : symbols) {
            cards.add(new MemoryCard(cardId++, symbol));
            cards.add(new MemoryCard(cardId++, symbol));
        }
        
        // Shuffle cards
        Collections.shuffle(cards);
        
        Log.i(TAG, "Game setup complete: " + cards.size() + " cards, " + totalPairs + " pairs");
    }
    
    /**
     * Randomly selects the specified number of unique symbols from the symbol pool
     */
    private String[] selectRandomSymbols(int count) {
        if (count > ALL_SYMBOLS.length) {
            Log.w(TAG, "Requested more symbols (" + count + ") than available (" + ALL_SYMBOLS.length + ")");
            count = ALL_SYMBOLS.length;
        }
        
        // Create a list of all symbols and shuffle it
        List<String> availableSymbols = new ArrayList<>(Arrays.asList(ALL_SYMBOLS));
        Collections.shuffle(availableSymbols);
        
        // Take the first 'count' symbols from the shuffled list
        String[] selectedSymbols = new String[count];
        for (int i = 0; i < count; i++) {
            selectedSymbols[i] = availableSymbols.get(i);
        }
        
        Log.i(TAG, "Selected " + count + " random symbols for game");
        return selectedSymbols;
    }

    private void createDialog() {
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_memory_game, null);
        
        // Initialize UI elements
        movesCounter = dialogView.findViewById(R.id.memory_moves_counter);
        pairsCounter = dialogView.findViewById(R.id.memory_pairs_counter);
        timer = dialogView.findViewById(R.id.memory_timer);
        Button closeButton = dialogView.findViewById(R.id.memory_close_button);
        
        // Setup RecyclerView with dynamic card sizing
        RecyclerView recyclerView = dialogView.findViewById(R.id.memory_game_grid);
        int columns, rows;
        if (totalPairs <= 4) {
            columns = 4; // 4x2 grid
            rows = 2;
        } else if (totalPairs <= 8) {
            columns = 4; // 4x4 grid
            rows = 4;
        } else {
            columns = 6; // 6x4 grid
            rows = 4;
        }
        
        // Calculate optimal card height based on available screen space
        int cardHeight = calculateOptimalCardHeight(rows);
        
        GridLayoutManager gridLayoutManager = new GridLayoutManager(context, columns);
        recyclerView.setLayoutManager(gridLayoutManager);
        
        // Add item decoration with calculated card height
        recyclerView.addItemDecoration(new DynamicGridSpacingItemDecoration(columns, 4, true, cardHeight));
        
        // Calculate optimal text size based on card height
        float textSize = calculateOptimalTextSize(cardHeight);
        
        adapter = new MemoryCardAdapter(cards, this::onCardClicked, textSize);
        recyclerView.setAdapter(adapter);
        
        // Update UI
        updateUI();
        
        // Setup close button
        closeButton.setOnClickListener(v -> closeGame());
        
        // Create dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setView(dialogView);
        builder.setCancelable(false);
        
        dialog = builder.create();
        dialog.show();
        
        // Make dialog full-screen
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        
        // Start timer
        startTimer();
    }

    private void onCardClicked(int position) {
        if (!gameActive || processingMove) return;
        
        MemoryCard clickedCard = cards.get(position);
        if (!clickedCard.canFlip()) return;
        
        // Flip the card
        clickedCard.flip();
        adapter.notifyItemChanged(position);
        
        String cardInfo = String.format(Locale.US, "Position %d (Symbol: %s)", position + 1, clickedCard.getSymbol());
        
        if (firstFlippedCard == null) {
            // First card of the pair
            firstFlippedCard = clickedCard;
            
            // Send context update
            if (toolContext != null) {
                String message = String.format(Locale.US,
                    "User revealed the first card: %s", cardInfo);
                toolContext.sendAsyncUpdate(message, false);
            }
            
        } else if (secondFlippedCard == null) {
            // Second card of the pair
            secondFlippedCard = clickedCard;
            moves++;
            processingMove = true;
            
            // Check for match
            boolean isMatch = firstFlippedCard.getSymbol().equals(secondFlippedCard.getSymbol());
            
            if (isMatch) {
                // Match found!
                firstFlippedCard.setMatched(true);
                secondFlippedCard.setMatched(true);
                matchedPairs++;
                
                // Update UI for matched cards
                int firstCardIndex = cards.indexOf(firstFlippedCard);
                int secondCardIndex = cards.indexOf(secondFlippedCard);
                adapter.notifyItemChanged(firstCardIndex);
                adapter.notifyItemChanged(secondCardIndex);
                
                // Check if game is complete and send appropriate message
                if (matchedPairs == totalPairs) {
                    // Game complete - send final message with completion and statistics
                    gameCompleteWithFinalPair(firstFlippedCard, firstCardIndex, cardInfo);
                } else {
                    // Game continues - send pair found message with feedback request
                    if (toolContext != null) {
                        String message = String.format(Locale.US,
                            "User found a matching pair! First card: %s, Second card: %s. " +
                            "Current score: %d of %d pairs found, %d moves. Give a very short feedback to the user.",
                            getCardInfo(firstFlippedCard, firstCardIndex),
                            cardInfo, matchedPairs, totalPairs, moves);
                        toolContext.sendAsyncUpdate(message, true);
                    }
                }
                
                // Reset for next turn
                firstFlippedCard = null;
                secondFlippedCard = null;
                processingMove = false;
                
            } else {
                // No match - flip cards back after delay
                if (toolContext != null) {
                    String message = String.format(Locale.US,
                        "User revealed two different cards: %s and %s. Cards will be flipped back. Current score: %d moves.",
                        getCardInfo(firstFlippedCard, cards.indexOf(firstFlippedCard)),
                        cardInfo, moves);
                    toolContext.sendAsyncUpdate(message, false);
                }
                
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    firstFlippedCard.flipBack();
                    secondFlippedCard.flipBack();
                    int firstCardIndex = cards.indexOf(firstFlippedCard);
                    int secondCardIndex = cards.indexOf(secondFlippedCard);
                    adapter.notifyItemChanged(firstCardIndex);
                    adapter.notifyItemChanged(secondCardIndex);
                    
                    firstFlippedCard = null;
                    secondFlippedCard = null;
                    processingMove = false;
                }, 1500);
            }
            
            updateUI();
        }
    }

    private String getCardInfo(MemoryCard card, int position) {
        return String.format(Locale.US, "Position %d (Symbol: %s)", position + 1, card.getSymbol());
    }

    private void gameCompleteWithFinalPair(MemoryCard firstCard, int firstCardIndex, String secondCardInfo) {
        gameActive = false;
        stopTimer();
        
        long endTime = System.currentTimeMillis();
        long totalTimeMs = endTime - startTime;
        int totalSeconds = (int) (totalTimeMs / 1000);
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        
        String timeString = String.format(Locale.US, "%02d:%02d", minutes, seconds);
        
        // Send final message combining last pair found and game completion
                            if (toolContext != null) {
            String message = String.format(Locale.US,
                "User found the final matching pair! First card: %s, Second card: %s. " +
                "GAME COMPLETED! Final statistics: All %d pairs found in %d moves and %s time. " +
                "Congratulate the user on completing the memory game!",
                getCardInfo(firstCard, firstCardIndex), secondCardInfo,
                totalPairs, moves, timeString);
            toolContext.sendAsyncUpdate(message, true);
        }
        
        // Auto-close dialog after 5 seconds
        new Handler(Looper.getMainLooper()).postDelayed(this::closeGame, 5000);
    }

    private void updateUI() {
        movesCounter.setText(context.getString(R.string.memory_moves_format, moves));
        pairsCounter.setText(context.getString(R.string.memory_pairs_format, matchedPairs, totalPairs));
    }

    private void startTimer() {
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                if (gameActive) {
                    long currentTime = System.currentTimeMillis();
                    long elapsedMs = currentTime - startTime;
                    int totalSeconds = (int) (elapsedMs / 1000);
                    int minutes = totalSeconds / 60;
                    int seconds = totalSeconds % 60;
                    
                    timer.setText(context.getString(R.string.memory_time_format, minutes, seconds));
                    timerHandler.postDelayed(this, 1000);
                }
            }
        };
        timerHandler.post(timerRunnable);
    }

    private void stopTimer() {
        if (timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }
    }

    private void closeGame() {
        gameActive = false;
        stopTimer();
        
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
        
        // Send context update about early closure if game wasn't completed
        if (matchedPairs < totalPairs && toolContext != null) {
            String message = String.format(Locale.US,
                "User closed the memory game early. Score when closing: %d of %d pairs found, %d moves.",
                matchedPairs, totalPairs, moves);
            toolContext.sendAsyncUpdate(message, false);
        }
        
        if (closeListener != null) {
            closeListener.onGameClosed();
        }
        
        Log.i(TAG, "Memory game closed");
    }

    /**
     * Calculate optimal card height based on available screen space
     */
    private int calculateOptimalCardHeight(int rows) {
        try {
            // Get screen dimensions
            WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics displayMetrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getMetrics(displayMetrics);
            
            int screenHeight = displayMetrics.heightPixels;
            
            // Convert dp to pixels for layout calculations
            float density = context.getResources().getDisplayMetrics().density;
            
            // Estimated space used by other UI elements (in dp, converted to pixels)
            int titleBarHeight = (int) (60 * density); // Title and close button
            int infoBarHeight = (int) (50 * density);  // Moves, pairs, time counters
            int paddingAndMargins = (int) (50 * density); // Dialog padding and margins
            int navigationBarHeight = (int) (48 * density); // System navigation bar
            
            // Calculate available height for the grid
            int availableHeight = screenHeight - titleBarHeight - infoBarHeight - paddingAndMargins - navigationBarHeight;
            
            // Calculate optimal card height
            int spacing = (int) (4 * density); // Spacing between cards
            int totalSpacing = spacing * (rows + 1); // Top, bottom, and between rows
            int cardHeight = (availableHeight - totalSpacing) / rows;
            
            // Ensure minimum and maximum card heights
            int minCardHeight = (int) (60 * density);  // Minimum 60dp
            int maxCardHeight = (int) (150 * density); // Maximum 150dp
            
            cardHeight = Math.max(minCardHeight, Math.min(maxCardHeight, cardHeight));
            
            Log.i(TAG, "Calculated card height: " + cardHeight + "px for " + rows + " rows");
            return cardHeight;
            
        } catch (Exception e) {
            Log.e(TAG, "Error calculating card height", e);
            // Fallback to default height
            return (int) (80 * context.getResources().getDisplayMetrics().density);
        }
    }

    /**
     * Calculate optimal text size based on card height and difficulty
     */
    private float calculateOptimalTextSize(int cardHeightPx) {
        float density = context.getResources().getDisplayMetrics().density;
        
        // Convert card height back to dp for calculation
        float cardHeightDp = cardHeightPx / density;
        
        // Scale text size proportionally to card height, with bonus for easy difficulty
        float textSizeSp;
        if (totalPairs <= 4) {
            // Easy difficulty - extra large symbols since there's much more space
            if (cardHeightDp <= 80) {
                textSizeSp = 44; // Much larger for small cards
            } else if (cardHeightDp <= 120) {
                textSizeSp = 56; // Much larger for medium cards
            } else {
                textSizeSp = 72; // Extra extra large for easy mode
            }
        } else {
            // Medium/Hard difficulty - moderately larger than before
            if (cardHeightDp <= 80) {
                textSizeSp = 38; // Larger small cards (increased from 32)
            } else if (cardHeightDp <= 120) {
                textSizeSp = 46; // Larger medium cards (increased from 40)  
            } else {
                textSizeSp = 58; // Larger large cards (increased from 52)
            }
        }
        
        Log.i(TAG, "Calculated text size: " + textSizeSp + "sp for card height " + cardHeightDp + "dp (pairs: " + totalPairs + ")");
        return textSizeSp;
    }
}
