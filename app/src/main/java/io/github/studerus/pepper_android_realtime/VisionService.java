package io.github.studerus.pepper_android_realtime;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import com.aldebaran.qi.Future;
import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.builder.TakePictureBuilder;
import com.aldebaran.qi.sdk.object.camera.TakePicture;
import com.aldebaran.qi.sdk.object.image.EncodedImage;
import com.aldebaran.qi.sdk.object.image.EncodedImageHandle;
import com.aldebaran.qi.sdk.object.image.TimestampedImageHandle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@SuppressWarnings("SpellCheckingInspection") // API provider names (Groq)
public class VisionService {
    public interface Callback {
        void onResult(String resultJson);
        void onError(String errorMessage);
        @SuppressWarnings("unused") // May be used in future versions
        void onInfo(String message);
        void onPhotoCaptured(String path);
    }

    private static final String TAG = "VisionService";
    private static final String GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions";

    private final Context context;
    private final ChatActivity activityRef;
    private final OkHttpClient http;
    private final OptimizedThreadManager threadManager;

    private QiContext qiContext;
    private Future<TakePicture> takePictureAction;
    private volatile boolean working = false;

    public VisionService(Context context) {
        this.context = context.getApplicationContext();
        this.activityRef = (context instanceof ChatActivity) ? (ChatActivity) context : null;
        // Use optimized shared API client for better performance and connection reuse
        this.http = OptimizedHttpClientManager.getInstance().getApiClient();
        // Use optimized thread manager for computation tasks
        this.threadManager = OptimizedThreadManager.getInstance();
    }
    
    /**
     * Initialize with QiContext for robot camera access
     */
    public void initialize(QiContext qiContext) {
        this.qiContext = qiContext;
        if (qiContext != null) {
            // Build the TakePicture action once for reuse
            this.takePictureAction = TakePictureBuilder.with(qiContext).buildAsync();
        }
    }

    public void startAnalyze(String prompt, String groqApiKey, Callback callback) {
        if (working) {
            callback.onError("Vision analysis already in progress");
            return;
        }
        working = true;
        
        // Groq key is only required when we fall back to Groq analysis (older models)
        
        if (qiContext == null || takePictureAction == null) {
            working = false;
            callback.onError("Robot camera not initialized. Ensure robot has focus.");
            return;
        }
        
        takePictureAndAnalyze(prompt, groqApiKey, callback);
    }

    private void takePictureAndAnalyze(String prompt, String apiKey, Callback callback) {
        if (takePictureAction == null) {
            working = false;
            callback.onError("Robot camera not available");
            return;
        }

        Log.i(TAG, "Taking picture with robot head camera...");
        
        // Execute on realtime thread to avoid blocking QiSDK MainEventLoop
        threadManager.executeRealtime(() -> {
            try {
                takePictureAction.andThenCompose(takePicture -> takePicture.async().run())
                    .andThenConsume(timestampedImageHandle -> {
                        try {
                            Bitmap bitmap = convertToBitmap(timestampedImageHandle);
                            if (bitmap == null) {
                                working = false;
                                callback.onError("Failed to convert robot camera image");
                                return;
                            }
                            
                            // Optimize bitmap for API upload (smaller size, lower quality)
                            Bitmap optimizedBitmap = optimizeBitmapForApi(bitmap);
                            
                            // Single compression for both cache file and Base64
                            byte[] jpegBytes;
                            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                                optimizedBitmap.compress(Bitmap.CompressFormat.JPEG, 75, baos);
                                jpegBytes = baos.toByteArray();
                            }
                            
                            // Save to cache file (async, non-blocking)
                            threadManager.executeComputation(() -> {
                    try {
                        File out = new File(context.getCacheDir(), "vision_" + System.currentTimeMillis() + ".jpg");
                        try (FileOutputStream fos = new FileOutputStream(out)) {
                                        fos.write(jpegBytes);
                        }
                                    callback.onPhotoCaptured(out.getAbsolutePath());
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to save preview file", e);
                    }
                            });
                            
                            // Convert to Base64 once (reuse for GA direct image or Groq text analysis)
                            String base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP);

                            // Decide path: if GA gpt-realtime is active, send image directly via WS
                            boolean useGaImagePath = shouldSendImageDirectToRealtime();
                            if (useGaImagePath) {
                                threadManager.executeComputation(() -> {
                                    try {
                                        if (activityRef == null) throw new IllegalStateException("Activity reference not available");
                                        RealtimeSessionManager sm = activityRef.getSessionManager();
                                        if (sm == null || !sm.isConnected()) throw new IllegalStateException("Realtime session not connected");
                                        boolean sentItem = sm.sendUserImageMessage(base64, "image/jpeg");
                                        if (!sentItem) throw new IllegalStateException("Failed to send image message");
                                        working = false;
                                        callback.onResult(new JSONObject().put("status", "sent_to_realtime").toString());
                                    } catch (Exception e) {
                                        Log.e(TAG, "Failed to send image to realtime API, falling back to Groq", e);
                                        analyzeWithGroq(base64, prompt, apiKey, callback);
                                    }
                                });
                            } else {
                                // Offload network analysis to computation thread (Groq path)
                    threadManager.executeComputation(() -> analyzeWithGroq(base64, prompt, apiKey, callback));
                            }
                            
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing robot camera image", e);
                            working = false;
                            callback.onError("Failed processing robot camera image: " + e.getMessage());
                        }
                    });
                } catch (Exception e) {
                Log.e(TAG, "Robot camera capture failed", e);
                    working = false;
                callback.onError("Robot camera capture failed: " + e.getMessage());
            }
        });
    }

    /**
     * Decide if we should send the image directly to the Realtime API (GA gpt-realtime)
     */
    private boolean shouldSendImageDirectToRealtime() {
        try {
            if (activityRef == null) return false;
            String model = activityRef.getSettingsManager().getModel();
            RealtimeApiProvider provider = activityRef.getSettingsManager().getApiProvider();
            return "gpt-realtime".equals(model) && provider == RealtimeApiProvider.OPENAI_DIRECT;
        } catch (Exception ignored) {
            return false;
        }
    }
    
    /**
     * Convert TimestampedImageHandle to Bitmap (same logic as PerceptionService)
     */
    private Bitmap convertToBitmap(TimestampedImageHandle timestampedImageHandle) {
        try {
            EncodedImageHandle encodedImageHandle = timestampedImageHandle.getImage();
            EncodedImage encodedImage = encodedImageHandle.getValue();
            ByteBuffer buffer = encodedImage.getData();
            buffer.rewind();
            byte[] pictureBufferArray = new byte[buffer.remaining()];
            buffer.get(pictureBufferArray);
            return BitmapFactory.decodeByteArray(pictureBufferArray, 0, pictureBufferArray.length);
        } catch (Exception e) {
            Log.e(TAG, "Error converting TimestampedImageHandle to Bitmap", e);
            return null;
        }
    }
    
    /**
     * Optimize bitmap for API upload by reducing size while maintaining aspect ratio
     */
    private Bitmap optimizeBitmapForApi(Bitmap original) {
        if (original == null) return null;
        
        // Target max dimension for API upload (smaller = faster processing)
        int maxDimension = 800;
        int width = original.getWidth();
        int height = original.getHeight();
        
        // Skip resizing if already small enough
        if (width <= maxDimension && height <= maxDimension) {
            return original;
        }
        
        // Calculate scale factor to maintain aspect ratio
        float scale = Math.min((float) maxDimension / width, (float) maxDimension / height);
        int newWidth = Math.round(width * scale);
        int newHeight = Math.round(height * scale);
        
        Log.d(TAG, String.format("Optimizing bitmap: %dx%d → %dx%d (scale: %.2f)", 
               width, height, newWidth, newHeight, scale));
        
        return Bitmap.createScaledBitmap(original, newWidth, newHeight, true);
    }
    

    private void analyzeWithGroq(String base64Jpeg, String prompt, String apiKey, Callback callback) {
        try {
            JSONObject body = new JSONObject();
            body.put("model", "meta-llama/llama-4-scout-17b-16e-instruct");
            JSONArray messages = new JSONArray();
            JSONObject user = new JSONObject();
            user.put("role", "user");
            JSONArray content = new JSONArray();
            if (prompt != null && !prompt.isEmpty()) content.put(new JSONObject().put("type", "text").put("text", prompt));
            else content.put(new JSONObject().put("type", "text").put("text", "What's in this image?"));
            JSONObject img = new JSONObject();
            img.put("type", "image_url");
            img.put("image_url", new JSONObject().put("url", "data:image/jpeg;base64," + base64Jpeg));
            content.put(img);
            user.put("content", content);
            messages.put(user);
            body.put("messages", messages);

            Request req = new Request.Builder()
                    .url(GROQ_API_URL)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(body.toString(), MediaType.get("application/json; charset=utf-8")))
                    .build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful()) { 
                    callback.onError("Groq request failed: " + resp); 
                    working = false; 
                    return; 
                }
                okhttp3.ResponseBody responseBody = resp.body();
                if (responseBody == null) {
                    callback.onError("Groq request failed: Empty response body");
                    working = false;
                    return;
                }
                String s = responseBody.string();
            JSONObject json = new JSONObject(s);
            String description = "";
            try {
                JSONObject msg = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
                description = msg.optString("content", "");
                if (description.isEmpty() && msg.has("content") && msg.get("content") instanceof JSONArray) {
                    JSONArray parts = msg.getJSONArray("content");
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject p = parts.getJSONObject(i);
                        if ("text".equals(p.optString("type"))) sb.append(p.optString("text")).append(" ");
                    }
                    description = sb.toString().trim();
                }
            } catch (Exception ignore) {}
            if (description.isEmpty()) description = "No description returned.";
            JSONObject result = new JSONObject();
            result.put("description", description);
                callback.onResult(result.toString());
            }
        } catch (Exception e) {
            callback.onError("Groq analysis failed: " + e.getMessage());
        } finally {
            working = false;
        }
    }

    /**
     * Check if the service is initialized and ready to use
     */
    public boolean isInitialized() {
        return qiContext != null && takePictureAction != null;
    }
}
