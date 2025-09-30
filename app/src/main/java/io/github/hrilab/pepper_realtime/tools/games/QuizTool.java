package io.github.hrilab.pepper_realtime.tools.games;

import io.github.hrilab.pepper_realtime.R;

import io.github.hrilab.pepper_realtime.tools.Tool;
import io.github.hrilab.pepper_realtime.tools.ToolContext;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Tool for presenting quiz questions to users.
 * Shows a question with four multiple choice answers in a popup dialog.
 */
public class QuizTool implements Tool {

    @Override
    public String getName() {
        return "present_quiz_question";
    }

    @Override
    public JSONObject getDefinition() {
        try {
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", getName());
            tool.put("description", "Presents a quiz question with four possible answers to the user in a popup window.");
            
            JSONObject params = new JSONObject();
            params.put("type", "object");
            
            JSONObject properties = new JSONObject();
            properties.put("question", new JSONObject()
                .put("type", "string")
                .put("description", "The quiz question."));
            
            JSONObject optionsSchema = new JSONObject();
            optionsSchema.put("type", "array");
            optionsSchema.put("items", new JSONObject().put("type", "string"));
            optionsSchema.put("minItems", 4);
            optionsSchema.put("maxItems", 4);
            optionsSchema.put("description", "An array of exactly four string options for the answer.");
            properties.put("options", optionsSchema);
            
            properties.put("correct_answer", new JSONObject()
                .put("type", "string")
                .put("description", "The correct answer from the options array."));
            
            params.put("properties", properties);
            params.put("required", new JSONArray().put("question").put("options").put("correct_answer"));
            tool.put("parameters", params);
            
            return tool;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool definition", e);
        }
    }

    @Override
    public String execute(JSONObject args, ToolContext context) throws Exception {
        String question = args.optString("question", "");
        JSONArray optionsJson = args.optJSONArray("options");
        String correct = args.optString("correct_answer", "");
        
        if (question.isEmpty() || optionsJson == null || optionsJson.length() != 4) {
            return new JSONObject().put("error", "Missing required parameters: question or 4 options").toString();
        }
        
        String[] opts = new String[4];
        for (int i = 0; i < 4; i++) {
            opts[i] = optionsJson.getString(i);
        }
        
        if (context.hasUi()) {
            // Execute UI operation on main thread
            context.getActivity().runOnUiThread(() -> io.github.hrilab.pepper_realtime.managers.QuizDialogManager.showQuizDialog(
                    context.getActivity(), question, opts, correct,
                    new io.github.hrilab.pepper_realtime.managers.QuizDialogManager.QuizDialogCallback() {
                        @Override
                        public void onQuizAnswered(String question, String selectedOption) {
                            String feedbackMessage = context.getActivity().getString(R.string.quiz_feedback_format, question, selectedOption);
                            context.sendAsyncUpdate(feedbackMessage, true); // Send as user input to AI
                        }
                        
                        @Override
                        public void onInterruptRequested() {
                            // Handle interrupt through context
                            context.sendAsyncUpdate("Quiz interrupted", false);
                        }
                        
                        @Override
                        public boolean isActivityFinishing() {
                            return context.getActivity().isFinishing();
                        }
                        
                        @Override
                        public boolean shouldInterrupt() {
                            // Simple check - tools shouldn't need complex state management
                            return true;
                        }
                        

                    }
                ));
        }
        
        return new JSONObject()
                .put("status", "Quiz presented to user.")
                .toString();
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public String getApiKeyType() {
        return null;
    }
}
