package io.github.studerus.pepper_android_realtime.tools.vision;

import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import io.github.studerus.pepper_android_realtime.R;
import io.github.studerus.pepper_android_realtime.VisionService;
import io.github.studerus.pepper_android_realtime.tools.Tool;
import io.github.studerus.pepper_android_realtime.tools.ToolContext;

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
            tool.put("description", "Analyzes the current camera image of the robot and describes what the robot is seeing. Use this function if the user asks what you are seeing or how the user looks. Tell the user to wait a moment before you perform the function.");
            
            JSONObject params = new JSONObject();
            params.put("type", "object");
            
            JSONObject properties = new JSONObject();
            properties.put("prompt", new JSONObject()
                .put("type", "string")
                .put("description", "Optional additional instruction for the vision analysis (e.g. 'how old is the person?' if the user asks you to estimate his age)"));
            
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
        
        if (!context.getApiKeyManager().isVisionAnalysisAvailable()) {
            String setupMessage = "🔑 Vision analysis requires GROQ_API_KEY.\n" +
                                "Get free key at: https://console.groq.com/\n" +
                                "Add to local.properties: GROQ_API_KEY=your_key";
            try {
                return new JSONObject().put("error", setupMessage).toString();
            } catch (Exception e) {
                return "{\"error\":\"Setup failed\"}";
            }
        }
        
        Log.i(TAG, "Analyzing vision with prompt: " + (prompt.isEmpty() ? "(default analysis)" : prompt));
        
        try {
            // Use existing VisionService for actual implementation
            VisionService visionService = new VisionService(context.getAppContext());
            String apiKey = context.getApiKeyManager().getGroqApiKey();
            
            // Update UI status by sending async update
            context.sendAsyncUpdate("📸 Taking photo and analyzing with AI...", false);
            
            // Use CountDownLatch to handle async vision analysis
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicReference<String> result = new AtomicReference<>();
            
            visionService.startAnalyze(prompt, apiKey, new VisionService.Callback() {
                @Override 
                public void onResult(String resultJson) {
                    try {
                        // Append context for the realtime response so it speaks in "Du" form
                        org.json.JSONObject obj = new org.json.JSONObject(resultJson);
                        String desc = obj.optString("description", "");
                        String contextSuffix = context.getAppContext().getString(R.string.vision_context_suffix);
                        if (!desc.isEmpty()) {
                            obj.put("description", desc + contextSuffix);
                        }
                        result.set(obj.toString());
                        latch.countDown();
                    } catch (Exception e) {
                        // Fallback: if parsing fails, use original string
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
                            result.set(new JSONObject().put("error", "Error processing vision result: " + e.getMessage()).toString());
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
                    // Add image to chat UI and session cleanup
                    if (context.hasUi()) {
                        context.getActivity().addImageMessage(path);
                        context.getActivity().addImageToSessionCleanup(path);
                    }
                    // Inform about photo capture via async update
                    context.sendAsyncUpdate("📷 Photo captured - analyzing with AI...", false);
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
                return result.get();
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
        return true;
    }

    @Override
    public String getApiKeyType() {
        return "Groq";
    }
}
