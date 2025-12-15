package ch.fhnw.pepper_realtime.ui

import android.graphics.Bitmap
import ch.fhnw.pepper_realtime.data.EventRule
import ch.fhnw.pepper_realtime.data.MapGraphInfo
import ch.fhnw.pepper_realtime.data.MatchedRule
import ch.fhnw.pepper_realtime.data.PerceptionData
import ch.fhnw.pepper_realtime.data.SavedLocation
import ch.fhnw.pepper_realtime.tools.games.MemoryCard

/**
 * UI state for navigation and mapping overlay.
 */
data class NavigationUiState(
    val isVisible: Boolean = false,
    val localizationStatus: String = "Not running",
    val hasMapOnDisk: Boolean = false,
    val mapBitmap: Bitmap? = null,
    val mapGfx: MapGraphInfo? = null,
    val savedLocations: List<SavedLocation> = emptyList()
)

/**
 * UI state for perception dashboard overlay.
 */
data class DashboardState(
    val isVisible: Boolean = false,
    val humans: List<PerceptionData.HumanInfo> = emptyList(),
    val lastUpdate: String = "",
    val isMonitoring: Boolean = false
)

/**
 * UI state for quiz dialog.
 */
data class QuizState(
    val isVisible: Boolean = false,
    val question: String = "",
    val options: List<String> = emptyList(),
    val correctAnswer: String = ""
)

/**
 * UI state for TicTacToe game dialog.
 * Note: TicTacToeGameState is defined in TicTacToeDialog.kt
 */
data class TicTacToeUiState(
    val isVisible: Boolean = false,
    val board: List<Int> = List(9) { 0 }, // 0=empty, 1=X(user), 2=O(AI)
    val isGameOver: Boolean = false,
    val gameResult: Int = 0 // From TicTacToeGame constants
)

/**
 * Internal state for Memory game including cards and game logic state.
 */
data class MemoryGameInternalState(
    val isVisible: Boolean = false,
    val cards: List<MemoryCard> = emptyList(),
    val moves: Int = 0,
    val matchedPairs: Int = 0,
    val totalPairs: Int = 0,
    val timeString: String = "00:00",
    val isGameActive: Boolean = false,
    // Internal game logic state
    val firstFlippedCardIndex: Int = -1,
    val secondFlippedCardIndex: Int = -1,
    val processingMove: Boolean = false,
    val startTime: Long = 0L
)

/**
 * UI state for drawing canvas game.
 */
data class DrawingGameState(
    val isVisible: Boolean = false,
    val topic: String? = null,
    val hasUnsavedChanges: Boolean = false,
    val lastSentTimestamp: Long = 0L
)

/**
 * UI state for melody player overlay.
 */
data class MelodyPlayerState(
    val isVisible: Boolean = false,
    val melody: String = "",
    val isPlaying: Boolean = false,
    val progress: Float = 0f,
    val currentNote: String = ""
)

/**
 * UI state for event rules overlay.
 */
data class EventRulesState(
    val isVisible: Boolean = false,
    val rules: List<EventRule> = emptyList(),
    val editingRule: EventRule? = null,
    val recentTriggeredRules: List<MatchedRule> = emptyList()
)

