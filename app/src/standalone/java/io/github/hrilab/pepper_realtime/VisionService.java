package io.github.hrilab.pepper_realtime;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Standalone implementation of VisionService using Android device camera.
 * Automatically captures photos with the front camera and sends them to vision API.
 */
public class VisionService {
    private static final String TAG = "VisionService[Standalone]";
    private static final String GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions";

    private final Context context;
    private final ChatActivity activityRef;
    private final OkHttpClient http;
    private final OptimizedThreadManager threadManager;

    private volatile boolean working = false;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread cameraThread;
    private Handler cameraHandler;

    public VisionService(Context context) {
        this.context = context.getApplicationContext();
        this.activityRef = (context instanceof ChatActivity) ? (ChatActivity) context : null;
        this.http = OptimizedHttpClientManager.getInstance().getApiClient();
        this.threadManager = OptimizedThreadManager.getInstance();
        Log.d(TAG, "VisionService created (using Android camera)");
    }

    /**
     * Initialize (no-op in standalone mode, Android camera is always available)
     */
    public void initialize(Object qiContext) {
        Log.i(TAG, "VisionService initialized (Android camera ready)");
        
        // Start camera background thread
        cameraThread = new HandlerThread("CameraBackground");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    /**
     * Check if service is initialized (always true for standalone)
     */
    public boolean isInitialized() {
        return true;
    }

    /**
     * Start vision analysis by automatically capturing with front camera
     */
    public void startAnalyze(String prompt, String apiKey, Callback callback) {
        if (working) {
            callback.onError("Vision analysis already in progress");
            return;
        }
        
        if (activityRef == null) {
            callback.onError("Activity reference not available");
            return;
        }

        // Check camera permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) 
                != PackageManager.PERMISSION_GRANTED) {
            callback.onError("Camera permission not granted");
            return;
        }

        working = true;
        Log.i(TAG, "Starting automatic photo capture with front camera...");
        
        // Capture photo automatically
        capturePhotoAutomatically(prompt, apiKey, callback);
    }

    /**
     * Automatically capture photo using Camera2 API
     */
    private void capturePhotoAutomatically(String prompt, String apiKey, Callback callback) {
        try {
            CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            
            // Find front camera
            String frontCameraId = null;
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    frontCameraId = cameraId;
                    break;
                }
            }
            
            if (frontCameraId == null) {
                working = false;
                callback.onError("Front camera not found");
                return;
            }

            // Setup ImageReader for capture
            imageReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 1);
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = reader.acquireLatestImage();
                if (image != null) {
                    processCapturedImage(image, prompt, apiKey, callback);
                    image.close();
                }
            }, cameraHandler);

            // Open camera
            cameraManager.openCamera(frontCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    Log.i(TAG, "Front camera opened, capturing photo...");
                    takePictureNow(callback);
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                    if (working) {
                        working = false;
                        callback.onError("Camera disconnected");
                    }
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    working = false;
                    callback.onError("Camera error: " + error);
                }
            }, cameraHandler);

        } catch (CameraAccessException e) {
            working = false;
            Log.e(TAG, "Camera access exception", e);
            callback.onError("Failed to access camera: " + e.getMessage());
        } catch (SecurityException e) {
            working = false;
            Log.e(TAG, "Camera permission denied", e);
            callback.onError("Camera permission denied");
        }
    }

    /**
     * Capture photo from opened camera
     */
    private void takePictureNow(Callback callback) {
        if (cameraDevice == null || imageReader == null) {
            working = false;
            callback.onError("Camera not ready");
            return;
        }

        try {
            CaptureRequest.Builder captureBuilder = 
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

            cameraDevice.createCaptureSession(Arrays.asList(imageReader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        captureSession = session;
                        try {
                            // Small delay for auto-focus
                            cameraHandler.postDelayed(() -> {
                                try {
                                    captureSession.capture(captureBuilder.build(), null, cameraHandler);
                                    Log.i(TAG, "Photo capture triggered");
                                } catch (CameraAccessException e) {
                                    Log.e(TAG, "Capture failed", e);
                                    cleanupCamera();
                                    working = false;
                                    callback.onError("Failed to capture photo");
                                }
                            }, 500); // 500ms delay for focus
                        } catch (Exception e) {
                            Log.e(TAG, "Capture delay failed", e);
                            cleanupCamera();
                            working = false;
                            callback.onError("Failed to capture photo");
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        cleanupCamera();
                        working = false;
                        callback.onError("Camera configuration failed");
                    }
                }, cameraHandler);

        } catch (CameraAccessException e) {
            cleanupCamera();
            working = false;
            Log.e(TAG, "Failed to create capture session", e);
            callback.onError("Failed to create capture session");
        }
    }

    /**
     * Process captured image from Camera2
     */
    private void processCapturedImage(Image image, String prompt, String apiKey, Callback callback) {
        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            
            if (bitmap == null) {
                cleanupCamera();
                working = false;
                callback.onError("Failed to decode captured image");
                return;
            }
            
            Log.i(TAG, "Photo captured successfully: " + bitmap.getWidth() + "x" + bitmap.getHeight());
            
            // Fix rotation for front camera (front camera is mirrored and rotated)
            // Front camera sensor is typically 270° on most devices
            bitmap = rotateBitmapForFrontCamera(bitmap);
            
            // Cleanup camera immediately after capture
            cleanupCamera();
            
            // Process the bitmap
            processBitmapAndAnalyze(bitmap, prompt, apiKey, callback);
            
        } catch (Exception e) {
            cleanupCamera();
            working = false;
            Log.e(TAG, "Error processing captured image", e);
            callback.onError("Error processing captured image: " + e.getMessage());
        }
    }
    
    /**
     * Rotate bitmap to correct orientation for front camera
     */
    private Bitmap rotateBitmapForFrontCamera(Bitmap bitmap) {
        try {
            // Get device rotation
            int rotation = activityRef.getWindowManager().getDefaultDisplay().getRotation();
            
            // Front camera requires rotation correction
            // Most Android devices have front camera at 270° sensor orientation
            int rotationDegrees = 0;
            switch (rotation) {
                case android.view.Surface.ROTATION_0:   // Portrait
                    rotationDegrees = 270; // Rotate 270° to correct
                    break;
                case android.view.Surface.ROTATION_90:  // Landscape (right)
                    rotationDegrees = 0;   // No rotation needed
                    break;
                case android.view.Surface.ROTATION_180: // Portrait (upside down)
                    rotationDegrees = 90;  // Rotate 90°
                    break;
                case android.view.Surface.ROTATION_270: // Landscape (left)
                    rotationDegrees = 180; // Rotate 180°
                    break;
            }
            
            if (rotationDegrees == 0) {
                return bitmap; // No rotation needed
            }
            
            Log.d(TAG, "Rotating bitmap by " + rotationDegrees + "° (device rotation: " + rotation + ")");
            
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.postRotate(rotationDegrees);
            
            Bitmap rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, 
                bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            
            // Recycle original bitmap if different from rotated
            if (rotatedBitmap != bitmap) {
                bitmap.recycle();
            }
            
            return rotatedBitmap;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to rotate bitmap, using original", e);
            return bitmap;
        }
    }

    /**
     * Cleanup camera resources
     */
    private void cleanupCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    /**
     * Process captured bitmap and send to vision API
     * (Same logic as Pepper version)
     */
    private void processBitmapAndAnalyze(Bitmap bitmap, String prompt, String apiKey, Callback callback) {
        threadManager.executeComputation(() -> {
            try {
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
                } else {
                    // Offload network analysis to computation thread (Groq path)
                    analyzeWithGroq(base64, prompt, apiKey, callback);
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error processing camera image", e);
                working = false;
                callback.onError("Failed processing camera image: " + e.getMessage());
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
     * Optimize bitmap for API upload by reducing size while maintaining aspect ratio
     * (Copied from Pepper version - same logic)
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

    /**
     * Send image to Groq Vision API for analysis
     * (Copied from Pepper version - same logic)
     */
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
     * Callback interface for vision analysis (compatible with Pepper version)
     */
    public interface Callback {
        void onResult(String resultJson);
        void onError(String errorMessage);
        void onInfo(String message);
        void onPhotoCaptured(String path);
    }

    /**
     * Callback interface for async picture taking
     */
    public interface PictureCallback {
        void onPictureTaken(Bitmap bitmap);
    }

    /**
     * Shuts down the service
     */
    public void shutdown() {
        working = false;
        cleanupCamera();
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try {
                cameraThread.join();
                cameraThread = null;
                cameraHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Camera thread interrupted during shutdown", e);
            }
        }
        Log.i(TAG, "VisionService shutdown");
    }
}
