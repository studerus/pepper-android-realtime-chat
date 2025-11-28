package io.github.anonymous.pepper_realtime.manager

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Manager for Quiz Dialog UI functionality.
 * Handles the creation and management of quiz popups using Jetpack Compose.
 */
object QuizDialogManager {

    private const val TAG = "QuizDialogManager"

    /**
     * Interface for quiz dialog callbacks
     */
    interface QuizDialogCallback {
        fun onQuizAnswered(question: String, selectedOption: String)
        fun onInterruptRequested()
        fun isActivityFinishing(): Boolean
        fun shouldInterrupt(): Boolean
    }

    /**
     * State holder for the quiz dialog
     */
    data class QuizState(
        val isVisible: Boolean = false,
        val question: String = "",
        val options: List<String> = emptyList(),
        val correctAnswer: String = "",
        val callback: QuizDialogCallback? = null
    )

    // Observable state for Compose
    var quizState by mutableStateOf(QuizState())
        private set

    /**
     * Show a quiz dialog with the given question and options
     *
     * @param question The quiz question
     * @param options Array of exactly 4 answer options
     * @param correctAnswer The correct answer from the options
     * @param callback Callback interface for handling quiz events
     */
    fun showQuizDialog(
        question: String,
        options: Array<String>,
        correctAnswer: String,
        callback: QuizDialogCallback
    ) {
        if (callback.isActivityFinishing()) {
            Log.w(TAG, "Not showing quiz dialog because activity is finishing.")
            return
        }

        quizState = QuizState(
            isVisible = true,
            question = question,
            options = options.toList(),
            correctAnswer = correctAnswer,
            callback = callback
        )

        Log.i(TAG, "Quiz dialog shown with question: $question")
    }

    /**
     * Called when user selects an answer
     */
    fun onAnswerSelected(selectedOption: String) {
        val state = quizState
        val callback = state.callback ?: return

        // Interrupt robot if it's currently speaking
        if (callback.shouldInterrupt()) {
            callback.onInterruptRequested()
        }

        // Notify callback about the selected answer
        callback.onQuizAnswered(state.question, selectedOption)
    }

    /**
     * Dismiss the quiz dialog
     */
    fun dismissQuiz() {
        quizState = QuizState()
        Log.i(TAG, "Quiz dialog dismissed")
    }
}
