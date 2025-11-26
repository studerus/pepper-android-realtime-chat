package io.github.anonymous.pepper_realtime.manager

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.github.anonymous.pepper_realtime.R
import io.github.anonymous.pepper_realtime.ui.ChatActivity

/**
 * Manager for Quiz Dialog UI functionality.
 * Handles the creation and management of quiz popups.
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
     * Show a quiz dialog with the given question and options
     *
     * @param context The activity context
     * @param question The quiz question
     * @param options Array of exactly 4 answer options
     * @param correctAnswer The correct answer from the options
     * @param callback Callback interface for handling quiz events
     */
    @JvmStatic
    fun showQuizDialog(
        context: Context,
        question: String,
        options: Array<String>,
        correctAnswer: String,
        callback: QuizDialogCallback
    ) {
        if (callback.isActivityFinishing()) {
            Log.w(TAG, "Not showing quiz dialog because activity is finishing.")
            return
        }

        val activity = context as ChatActivity
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_quiz, null)
        val builder = AlertDialog.Builder(activity, R.style.QuizDialog)
        builder.setView(dialogView)
        builder.setCancelable(false)

        // Get references to the UI elements
        val questionTextView = dialogView.findViewById<TextView>(R.id.quiz_question_textview)
        val buttons = listOf(
            dialogView.findViewById<Button>(R.id.quiz_option_1),
            dialogView.findViewById<Button>(R.id.quiz_option_2),
            dialogView.findViewById<Button>(R.id.quiz_option_3),
            dialogView.findViewById<Button>(R.id.quiz_option_4)
        )

        // Set question and button texts
        questionTextView.text = question
        buttons.forEachIndexed { index, button ->
            if (index < options.size) {
                button.text = options[index]
            }
        }

        val dialog = builder.create()

        // Set onClick listeners for each button
        for (button in buttons) {
            button.setOnClickListener {
                // Interrupt robot if it's currently speaking
                if (callback.shouldInterrupt()) {
                    callback.onInterruptRequested()
                }

                val selectedOption = button.text.toString()
                val isCorrect = selectedOption == correctAnswer

                // Disable all buttons to prevent multiple answers
                buttons.forEach { it.isEnabled = false }

                // Color the buttons based on the answer
                if (isCorrect) {
                    val green = ContextCompat.getColor(context, R.color.correct_green)
                    button.backgroundTintList = ColorStateList.valueOf(green)
                    button.setTextColor(Color.WHITE)
                } else {
                    val red = ContextCompat.getColor(context, R.color.incorrect_red)
                    button.backgroundTintList = ColorStateList.valueOf(red)
                    button.setTextColor(Color.WHITE)
                    buttons.find { it.text.toString() == correctAnswer }?.let { correctButton ->
                        val green = ContextCompat.getColor(context, R.color.correct_green)
                        correctButton.backgroundTintList = ColorStateList.valueOf(green)
                        correctButton.setTextColor(Color.WHITE)
                    }
                }

                // Notify callback about the selected answer
                callback.onQuizAnswered(question, selectedOption)

                // Close the dialog after a delay
                Handler(Looper.getMainLooper()).postDelayed({ dialog.dismiss() }, 4000)
            }
        }

        dialog.show()
        // Make dialog full-screen
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        Log.i(TAG, "Quiz dialog shown with question: $question")
    }
}

