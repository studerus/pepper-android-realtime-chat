package io.github.anonymous.pepper_realtime;

import android.graphics.Bitmap;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

@SuppressWarnings({"SpellCheckingInspection", "ConstantConditions"})
public class FaceRecognitionService {

    private static final String TAG = "FaceRecognitionService";

    public interface FaceCallback {
        void onResult(List<FaceInfo> faces);
        void onError(Exception e);
    }

    public static class RateLimitException extends Exception {
        public final long retryAfterMs;
        public RateLimitException(long retryAfterMs) {
            super("Azure Face API rate limited");
            this.retryAfterMs = retryAfterMs;
        }
    }

    public static class FaceInfo {
        public int left;
        public int top;
        public int width;
        public int height;
        public Double yawDeg; // headPose.yaw in degrees
        public Double pitchDeg;
        public Double rollDeg;
        public String glassesType; // e.g., NoGlasses, ReadingGlasses, Sunglasses
        public Boolean isMasked;
        public String imageQuality; // low|medium|high
        public Double blurValue; // 0..1
        public String exposureLevel; // underExposure|goodExposure|overExposure
    }

    private final OkHttpClient http;

    public FaceRecognitionService() {
        this.http = OptimizedHttpClientManager.getInstance().getApiClient();
    }

    public boolean isConfigured() {
        final String endpoint = BuildConfig.AZURE_FACE_ENDPOINT;
        final String key = BuildConfig.AZURE_FACE_API_KEY;
        return endpoint != null && endpoint.length() > 0 && key != null && key.length() > 0;
    }

    public void detectFacesAsync(Bitmap image, FaceCallback callback) {
        if (!isConfigured() || image == null) {
            if (callback != null) callback.onResult(Collections.emptyList());
            return;
        }
        OptimizedThreadManager.getInstance().executeNetwork(() -> {
            try {
                // Downscale to reduce bandwidth/load
                Bitmap scaled = image;
                try {
                    final int maxW = 640;
                    if (image.getWidth() > maxW) {
                        int nh = (int) (image.getHeight() * (maxW / (double) image.getWidth()));
                        scaled = Bitmap.createScaledBitmap(image, maxW, nh, true);
                    }
                } catch (Exception ignore) { }

                // Prepare JPEG bytes
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, outputStream);
                byte[] imageBytes = outputStream.toByteArray();

                // Build request
                String base = BuildConfig.AZURE_FACE_ENDPOINT;
                if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                String url = base + "/face/v1.0/detect" +
                        "?returnFaceId=false" +
                        "&detectionModel=detection_03" +
                        "&recognitionModel=recognition_04" +
                        "&returnFaceAttributes=headPose,glasses,mask,blur,exposure,qualityForRecognition";

                Request req = new Request.Builder()
                        .url(url)
                        .addHeader("Ocp-Apim-Subscription-Key", BuildConfig.AZURE_FACE_API_KEY)
                        .addHeader("Content-Type", "application/octet-stream")
                        .post(RequestBody.create(imageBytes, MediaType.get("application/octet-stream")))
                        .build();

                try (Response resp = http.newCall(req).execute()) {
                    ResponseBody rb = resp.body();
                    if (!resp.isSuccessful() || rb == null) {
                        if (resp.code() == 429) {
                            String ra = resp.header("Retry-After");
                            long retryMs = 15000L;
                            try { if (ra != null) retryMs = Long.parseLong(ra.trim()) * 1000L; } catch (Exception ignore) {}
                            Log.w(TAG, "Azure detect failed: " + resp + ", retryAfterMs=" + retryMs);
                            if (callback != null) callback.onError(new RateLimitException(retryMs));
                        } else {
                            Log.w(TAG, "Azure detect failed: " + resp);
                            if (callback != null) callback.onResult(Collections.emptyList());
                        }
                        return;
                    }
                    String body = rb.string();
                    JSONArray arr = new JSONArray(body);
                    List<FaceInfo> results = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject obj = arr.getJSONObject(i);
                        FaceInfo info = new FaceInfo();
                        try {
                            JSONObject rect = obj.optJSONObject("faceRectangle");
                            if (rect != null) {
                                info.left = rect.optInt("left");
                                info.top = rect.optInt("top");
                                info.width = rect.optInt("width");
                                info.height = rect.optInt("height");
                            }
                            JSONObject attrs = obj.optJSONObject("faceAttributes");
                            if (attrs != null) {
                                JSONObject headPose = attrs.optJSONObject("headPose");
                                if (headPose != null) {
                                    info.yawDeg = headPose.optDouble("yaw");
                                    info.pitchDeg = headPose.optDouble("pitch");
                                    info.rollDeg = headPose.optDouble("roll");
                                }
                                String glasses = attrs.optString("glasses", "");
                                info.glassesType = glasses.isEmpty() ? null : glasses;
                                JSONObject mask = attrs.optJSONObject("mask");
                                if (mask != null) {
                                    if (mask.has("noseAndMouthCovered")) {
                                        info.isMasked = mask.optBoolean("noseAndMouthCovered");
                                    } else {
                                        String maskType = mask.optString("type", "");
                                        info.isMasked = !maskType.isEmpty() && !"noMask".equalsIgnoreCase(maskType);
                                    }
                                }
                                JSONObject blur = attrs.optJSONObject("blur");
                                if (blur != null) {
                                    info.blurValue = blur.optDouble("value");
                                }
                                JSONObject exposure = attrs.optJSONObject("exposure");
                                if (exposure != null) {
                                    String level = exposure.optString("exposureLevel", "");
                                    info.exposureLevel = level.isEmpty() ? null : level;
                                }
                                String quality = attrs.optString("qualityForRecognition", "");
                                if (!quality.isEmpty()) info.imageQuality = quality;
                            }
                        } catch (Exception ignore) { }
                        results.add(info);
                    }
                    Log.i(TAG, "Azure detect OK, faces=" + results.size());
                    if (callback != null) callback.onResult(results);
                }
            } catch (Exception e) {
                Log.e(TAG, "Face detect REST failed", e);
                if (callback != null) callback.onError(e);
            }
        });
    }
}
