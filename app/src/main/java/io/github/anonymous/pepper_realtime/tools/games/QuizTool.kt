package io.github.anonymous.pepper_realtime.tools.games

import io.github.anonymous.pepper_realtime.R
import io.github.anonymous.pepper_realtime.manager.QuizDialogManager
import io.github.anonymous.pepper_realtime.tools.Tool
import io.github.anonymous.pepper_realtime.tools.ToolContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool for presenting quiz questions to users.
 * Shows a question with four multiple choice answers in a popup dialog.
 */
class QuizTool : Tool {

    override fun getName(): String = "present_quiz_question"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Presents a quiz question with four possible answers to the user in a popup window.")

            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("question", JSONObject()
                        .put("type", "string")
                        .put("description", "The quiz question."))

                    put("options", JSONObject().apply {
                        put("type", "array")
                        put("items", JSONObject().put("type", "string"))
                        put("minItems", 4)
                        put("maxItems", 4)
                        put("description", "An array of exactly four string options for the answer.")
                    })

                    put("correct_answer", JSONObject()
                        .put("type", "string")
                        .put("description", "The correct answer from the options array."))
                })
                put("required", JSONArray().put("question").put("options").put("correct_answer"))
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        val question = args.optString("question", "")
        val optionsJson = args.optJSONArray("options")
        val correct = args.optString("correct_answer", "")

        if (question.isEmpty() || optionsJson == null || optionsJson.length() != 4) {
            return JSONObject().put("error", "Missing required parameters: question or 4 options").toString()
        }

        val opts = Array(4) { i -> optionsJson.getString(i) }

        if (context.hasUi()) {
            val activity = context.activity
            activity?.runOnUiThread {
                QuizDialogManager.showQuizDialog(
                    question,
                    opts,
                    correct,
                    object : QuizDialogManager.QuizDialogCallback {
                        override fun onQuizAnswered(question: String, selectedOption: String) {
                            val feedbackMessage = activity.getString(R.string.quiz_feedback_format, question, selectedOption)
                            context.sendAsyncUpdate(feedbackMessage, true)
                        }

                        override fun onInterruptRequested() {
                            context.sendAsyncUpdate("Quiz interrupted", false)
                        }

                        override fun isActivityFinishing(): Boolean {
                            return activity.isFinishing
                        }

                        override fun shouldInterrupt(): Boolean {
                            return true
                        }
                    }
                )
            }
        }

        return JSONObject()
            .put("status", "Quiz presented to user.")
            .toString()
    }

}
