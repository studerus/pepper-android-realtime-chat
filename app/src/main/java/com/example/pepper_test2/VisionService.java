package com.example.pepper_test2;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    private final OkHttpClient http;
    private final ExecutorService visionExecutor;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private ImageReader imageReader;
    private volatile boolean working = false;

    public VisionService(Context context) {
        this.context = context.getApplicationContext();
        this.http = new OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
        this.visionExecutor = Executors.newSingleThreadExecutor();
    }

    public void startAnalyze(String prompt, String groqApiKey, Callback callback) {
        if (working) return;
        working = true;
        if (groqApiKey == null || groqApiKey.isEmpty() || groqApiKey.startsWith("your_")) {
            working = false;
            callback.onError("🔑 Vision analysis requires GROQ_API_KEY.\n" +
                           "Get free key at: https://console.groq.com/\n" +
                           "Add to local.properties: GROQ_API_KEY=your_key");
            return;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            working = false;
            callback.onError("Camera permission not granted.");
            return;
        }
        openCameraAndCapture(prompt, groqApiKey, callback);
    }

    private void openCameraAndCapture(String prompt, String apiKey, Callback callback) {
        try {
            if (cameraThread == null) {
                cameraThread = new HandlerThread("vision-camera-thread");
                cameraThread.start();
                cameraHandler = new Handler(cameraThread.getLooper());
            }
            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String frontId = null;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) { frontId = id; break; }
            }
            if (frontId == null && cm.getCameraIdList().length > 0) frontId = cm.getCameraIdList()[0];
            if (frontId == null) { fail(callback, "No camera available"); return; }

            if (imageReader != null) { try { imageReader.close(); } catch (Exception ignored) {} }
            imageReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 1);
            imageReader.setOnImageAvailableListener(reader -> {
                try (Image image = reader.acquireLatestImage()) {
                    if (image == null) return;
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    // Save to cache file for preview in chat
                    try {
                        File out = new File(context.getCacheDir(), "vision_" + System.currentTimeMillis() + ".jpg");
                        try (FileOutputStream fos = new FileOutputStream(out)) {
                            fos.write(bytes);
                            fos.flush();
                        }
                        if (callback != null) callback.onPhotoCaptured(out.getAbsolutePath());
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to save preview file", e);
                    }
                    String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                    // Offload network analysis to dedicated executor (do NOT block camera handler)
                    visionExecutor.submit(() -> analyzeWithGroq(base64, prompt, apiKey, callback));
                } catch (Exception e) {
                    Log.e(TAG, "Image read failed", e);
                    if (callback != null) callback.onError("Failed reading image: " + e.getMessage());
                    working = false;
                } finally {
                    closeCamera();
                }
            }, cameraHandler);

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                fail(callback, "Camera permission missing");
                return;
            }
            cm.openCamera(frontId, new CameraDevice.StateCallback() {
                @Override public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    try {
                        Surface surface = imageReader.getSurface();
                        camera.createCaptureSession(java.util.Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                            @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                                try {
                                    CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                    b.addTarget(surface);
                                    b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                    session.capture(b.build(), new CameraCaptureSession.CaptureCallback() {}, cameraHandler);
                                } catch (CameraAccessException e) { fail(callback, "Capture failed: " + e.getMessage()); closeCamera(); }
                            }
                            @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) { fail(callback, "Camera configure failed"); closeCamera(); }
                        }, cameraHandler);
                    } catch (CameraAccessException e) { fail(callback, "Session failed: " + e.getMessage()); closeCamera(); }
                }
                @Override public void onDisconnected(@NonNull CameraDevice camera) { closeCamera(); }
                @Override public void onError(@NonNull CameraDevice camera, int error) { fail(callback, "Camera error: " + error); closeCamera(); }
            }, cameraHandler);
        } catch (Exception e) {
            fail(callback, "Open camera failed: " + e.getMessage());
            closeCamera();
        }
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

    private void fail(Callback cb, String msg) {
        working = false;
        cb.onError(msg);
    }

    private void closeCamera() {
        try { if (cameraDevice != null) cameraDevice.close(); } catch (Exception ignored) {}
        cameraDevice = null;
        try { if (imageReader != null) imageReader.close(); } catch (Exception ignored) {}
        imageReader = null;
        // leave thread for reuse
    }
}
