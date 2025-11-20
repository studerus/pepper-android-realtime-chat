package io.github.anonymous.pepper_realtime.tools.vision;

import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import io.github.anonymous.pepper_realtime.R;
import io.github.anonymous.pepper_realtime.service.VisionService;
import io.github.anonymous.pepper_realtime.tools.Tool;
import io.github.anonymous.pepper_realtime.tools.ToolContext;

import org.json.JSONObject;

/**
 * Tool for analyzing robot's camera image using AI vision.
 * Uses Groq API to analyze what the robot is currently seeing.
 */
@SuppressWarnings("SpellCheckingInspection") // "Groq" is the correct API provider name
public class AnalyzeVisionTool implements Tool {

    private static final String TAG = "AnalyzeVisionTool";

    @Override
    public String getName() {
        return "analyze_vision";
    }

    @Override
    public JSONObject getDefinition() {
        try {
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", getName());
            tool.put("description",
                    "Analyzes the current camera image of the robot and describes what the robot is seeing. Use this function if the user asks what you are seeing or how the user looks. Tell the user to wait a moment before you perform the function.");

            JSONObject params = new JSONObject();
            params.put("type", "object");

            JSONObject properties = new JSONObject();
            properties.put("prompt", new JSONObject()
                    .put("type", "string")
                    .put("description",
                            "Optional additional instruction for the vision analysis (e.g. 'how old is the person?' if the user asks you to estimate his age)"));

            params.put("properties", properties);
            tool.put("parameters", params);

            return tool;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool definition", e);
        }
    }

    @Override
    public String execute(JSONObject args, ToolContext context) throws Exception {
        String prompt = args.optString("prompt", "");

        // Vision analysis now works with gpt-realtime (built-in) or Groq (optional)
        // No longer require API key check as it's handled by VisionService internally

        Log.i(TAG, "Analyzing vision with prompt: " + (prompt.isEmpty() ? "(default analysis)" : prompt));

        try {
            // Use existing VisionService for actual implementation
            VisionService visionService = new VisionService(context.getAppContext());

            // Initialize with QiContext for robot camera access
            visionService.initialize((com.aldebaran.qi.sdk.QiContext) context.getQiContext());

            if (!visionService.isInitialized()) {
                return new JSONObject().put("error", "Robot camera not available. Ensure robot has focus.").toString();
            }

            String apiKey = context.getApiKeyManager().getGroqApiKey();

            // Gestures now only move arms, not head - no need to stop them for vision

            // Update UI status by sending async update
            context.sendAsyncUpdate("📸 Taking photo with robot camera...", false);

            // Use CountDownLatch to handle async vision analysis
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> result = new AtomicReference<>();

            visionService.startAnalyze(prompt, apiKey, new VisionService.Callback() {
                @Override
                public void onResult(String resultJson) {
                    try {
                        // For Groq path, append context to the description.
                        // The gpt-realtime path is handled after latch.await().
                        JSONObject obj = new JSONObject(resultJson);
                        if (obj.has("description")) {
                            String desc = obj.optString("description", "");
                            String contextSuffix = context.getAppContext().getString(R.string.vision_context_suffix);
                            if (!desc.isEmpty()) {
                                obj.put("description", desc + contextSuffix);
                            }
                            result.set(obj.toString());
                        } else {
                            result.set(resultJson);
                        }
                        latch.countDown();
                    } catch (Exception e) {
                        // Not a JSON or doesn't have "description", likely the gpt-realtime status.
                        result.set(resultJson);
                        latch.countDown();
                    }
                }

                @Override
                public void onError(String errorMessage) {
                    try {
                        JSONObject err = new JSONObject();
                        err.put("error", errorMessage);
                        result.set(err.toString());
                        latch.countDown();
                    } catch (Exception e) {
                        try {
                            result.set(new JSONObject()
                                    .put("error", "Error processing vision result: " + e.getMessage()).toString());
                        } catch (Exception jsonE) {
                            result.set("{\"error\":\"Error processing vision result\"}");
                        }
                        latch.countDown();
                    }
                }

                @Override
                public void onInfo(String message) {
                    // Send info messages as async updates
                    context.sendAsyncUpdate(message, false);
                }

                @Override
                public void onPhotoCaptured(String path) {
                    // Add image to chat UI and session cleanup (unchanged)
                    if (context.hasUi()) {
                        context.getActivity().addImageMessage(path);
                        context.getAppContainer().sessionImageManager.addImage(path);
                    }
                    // Inform about photo capture via async update
                    context.sendAsyncUpdate("📷 Photo captured.", false);
                }
            });

            // Wait for vision analysis to complete (blocking call)
            try {
                boolean finished = latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
                if (!finished) {
                    try {
                        return new JSONObject().put("error", "Vision analysis timed out").toString();
                    } catch (Exception e) {
                        return "{\"error\":\"Vision analysis timed out\"}";
                    }
                }

                // Check if this was the GA path (direct image send). If so, formulate a
                // descriptive result.
                String finalResult = result.get();
                try {
                    JSONObject resultJson = new JSONObject(finalResult);
                    if ("sent_to_realtime".equals(resultJson.optString("status"))) {
                        JSONObject newResult = new JSONObject();
                        String contextMessage = context.getAppContext().getString(R.string.vision_context_suffix);
                        newResult.put("description", "A photo has been sent for you to analyze." + contextMessage);
                        finalResult = newResult.toString();
                    }
                } catch (Exception e) {
                    // Not a JSON or doesn't have the status field, proceed with original result
                }

                return finalResult;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                try {
                    return new JSONObject().put("error", "Vision analysis interrupted").toString();
                } catch (Exception jsonE) {
                    return "{\"error\":\"Vision analysis interrupted\"}";
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error during vision analysis", e);
            try {
                return new JSONObject().put("error", "Vision analysis failed: " + e.getMessage()).toString();
            } catch (Exception jsonE) {
                return "{\"error\":\"Vision analysis failed\"}";
            }
        }
    }

    @Override
    public boolean requiresApiKey() {
        return false; // Vision now works with gpt-realtime built-in, Groq is optional
    }

    @Override
    public String getApiKeyType() {
        return null; // No specific API key required
    }
}
