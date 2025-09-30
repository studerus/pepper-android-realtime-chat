package io.github.hrilab.pepper_realtime.managers;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import io.github.hrilab.pepper_realtime.ChatActivity;
import io.github.hrilab.pepper_realtime.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Manager for Quiz Dialog UI functionality.
 * Handles the creation and management of quiz popups.
 */
public class QuizDialogManager {
    
    private static final String TAG = "QuizDialogManager";
    
    /**
     * Interface for quiz dialog callbacks
     */
    public interface QuizDialogCallback {
        void onQuizAnswered(String question, String selectedOption);
        void onInterruptRequested();
        boolean isActivityFinishing();
        boolean shouldInterrupt();

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
    public static void showQuizDialog(Context context, String question, String[] options, 
                                    String correctAnswer, QuizDialogCallback callback) {
        
        if (callback.isActivityFinishing()) { 
            Log.w(TAG, "Not showing quiz dialog because activity is finishing.");
            return; 
        }
        
        ChatActivity activity = (ChatActivity) context;
        View dialogView = activity.getLayoutInflater().inflate(R.layout.dialog_quiz, null);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, R.style.QuizDialog);
        builder.setView(dialogView);
        builder.setCancelable(false);
        
        // Get references to the UI elements
        TextView questionTextView = dialogView.findViewById(R.id.quiz_question_textview);
        Button option1Button = dialogView.findViewById(R.id.quiz_option_1);
        Button option2Button = dialogView.findViewById(R.id.quiz_option_2);
        Button option3Button = dialogView.findViewById(R.id.quiz_option_3);
        Button option4Button = dialogView.findViewById(R.id.quiz_option_4);
        List<Button> buttons = new ArrayList<>();
        buttons.add(option1Button);
        buttons.add(option2Button);
        buttons.add(option3Button);
        buttons.add(option4Button);

        // Set question and button texts
        questionTextView.setText(question);
        for (int i = 0; i < buttons.size() && i < options.length; i++) {
            buttons.get(i).setText(options[i]);
        }

        final AlertDialog dialog = builder.create();

        // Set onClick listeners for each button
        for (final Button button : buttons) {
            button.setOnClickListener(v -> {
                // Interrupt robot if it's currently speaking
                if (callback.shouldInterrupt()) {
                    callback.onInterruptRequested();
                }

                String selectedOption = button.getText().toString();
                boolean isCorrect = selectedOption.equals(correctAnswer);

                // Disable all buttons to prevent multiple answers
                for (Button b : buttons) {
                    b.setEnabled(false);
                }

                // Color the buttons based on the answer
                if (isCorrect) {
                    int green = ContextCompat.getColor(context, R.color.correct_green);
                    button.setBackgroundTintList(ColorStateList.valueOf(green));
                    button.setTextColor(Color.WHITE);
                } else {
                    int red = ContextCompat.getColor(context, R.color.incorrect_red);
                    button.setBackgroundTintList(ColorStateList.valueOf(red));
                    button.setTextColor(Color.WHITE);
                    for (Button b : buttons) {
                        if (b.getText().toString().equals(correctAnswer)) {
                            int green = ContextCompat.getColor(context, R.color.correct_green);
                            b.setBackgroundTintList(ColorStateList.valueOf(green));
                            b.setTextColor(Color.WHITE);
                        }
                    }
                }

                // Notify callback about the selected answer
                callback.onQuizAnswered(question, selectedOption);

                // Close the dialog after a delay
                new Handler(Looper.getMainLooper()).postDelayed(dialog::dismiss, 4000); // 4 seconds delay
            });
        }
        
        dialog.show();
        // Make dialog full-screen
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        
        Log.i(TAG, "Quiz dialog shown with question: " + question);
    }
}
