package ch.fhnw.pepper_realtime.manager

import android.util.Log
import ch.fhnw.pepper_realtime.ui.QuizState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Quiz game state and logic.
 * Extracted from ChatViewModel for better separation of concerns.
 */
@Singleton
class QuizGameManager @Inject constructor() {

    companion object {
        private const val TAG = "QuizGameManager"
    }

    // UI State
    private val _state = MutableStateFlow(QuizState())
    val state: StateFlow<QuizState> = _state.asStateFlow()

    // Callback for when user answers
    private var answerCallback: ((question: String, selectedOption: String) -> Unit)? = null

    /**
     * Show a quiz question.
     * @param question The question text
     * @param options List of answer options
     * @param correctAnswer The correct answer
     * @param onAnswered Callback when user selects an answer
     */
    fun showQuiz(
        question: String,
        options: List<String>,
        correctAnswer: String,
        onAnswered: (question: String, selectedOption: String) -> Unit
    ) {
        answerCallback = onAnswered
        _state.value = QuizState(
            isVisible = true,
            question = question,
            options = options,
            correctAnswer = correctAnswer
        )
        Log.i(TAG, "Quiz shown: $question")
    }

    /**
     * Handle user answer selection.
     * Note: Does NOT dismiss the quiz immediately - the UI handles the delay
     * to show correct/incorrect feedback before auto-dismissing.
     * @param selectedOption The option selected by the user
     */
    fun onAnswerSelected(selectedOption: String) {
        val currentState = _state.value
        answerCallback?.invoke(currentState.question, selectedOption)
        // Don't dismiss here - the QuizDialog has a LaunchedEffect that waits 4 seconds
        // to show the correct answer feedback before calling onDismiss
    }

    /**
     * Dismiss the quiz dialog.
     */
    fun dismissQuiz() {
        _state.value = QuizState()
        answerCallback = null
        Log.i(TAG, "Quiz dismissed")
    }
}

