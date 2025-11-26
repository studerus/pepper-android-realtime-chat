package io.github.anonymous.pepper_realtime.service

import android.graphics.Bitmap
import android.util.Log
import io.github.anonymous.pepper_realtime.BuildConfig
import io.github.anonymous.pepper_realtime.di.IoDispatcher
import io.github.anonymous.pepper_realtime.network.HttpClientManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for Azure Face API integration.
 * Provides face detection with attributes like head pose, glasses, mask, etc.
 */
@Suppress("SpellCheckingInspection")
@Singleton
class FaceRecognitionService @Inject constructor() {
    // For DI injection (optional - not currently used)
    constructor(
        @Suppress("UNUSED_PARAMETER") httpClientManager: HttpClientManager,
        @Suppress("UNUSED_PARAMETER") @IoDispatcher ioDispatcher: CoroutineDispatcher
    ) : this()
    companion object {
        private const val TAG = "FaceRecognitionService"
    }

    /**
     * Legacy callback interface for Java interop
     */
    interface FaceCallback {
        fun onResult(faces: List<FaceInfo>)
        fun onError(e: Exception)
    }

    /**
     * Exception thrown when Azure rate limits the request
     */
    class RateLimitException(val retryAfterMs: Long) : Exception("Azure Face API rate limited")

    /**
     * Face detection result with attributes
     */
    data class FaceInfo(
        var left: Int = 0,
        var top: Int = 0,
        var width: Int = 0,
        var height: Int = 0,
        var yawDeg: Double? = null,
        var pitchDeg: Double? = null,
        var rollDeg: Double? = null,
        var glassesType: String? = null,
        var isMasked: Boolean? = null,
        var imageQuality: String? = null,
        var blurValue: Double? = null,
        var exposureLevel: String? = null
    )

    private val http by lazy { HttpClientManager.getInstance().getApiClient() }

    /**
     * Check if Azure Face API is configured
     */
    fun isConfigured(): Boolean {
        val endpoint = BuildConfig.AZURE_FACE_ENDPOINT
        val key = BuildConfig.AZURE_FACE_API_KEY
        return !endpoint.isNullOrEmpty() && !key.isNullOrEmpty()
    }

    /**
     * Detect faces in an image using Azure Face API.
     * This is a suspend function - preferred for new Kotlin code.
     *
     * @param image Bitmap to analyze
     * @return List of detected faces with attributes
     * @throws RateLimitException if Azure rate limits the request
     */
    suspend fun detectFaces(image: Bitmap?): List<FaceInfo> = withContext(Dispatchers.IO) {
        if (!isConfigured() || image == null) {
            return@withContext emptyList()
        }

        try {
            // Downscale to reduce bandwidth/load
            val scaled = try {
                val maxW = 640
                if (image.width > maxW) {
                    val nh = (image.height * (maxW / image.width.toDouble())).toInt()
                    Bitmap.createScaledBitmap(image, maxW, nh, true)
                } else {
                    image
                }
            } catch (e: Exception) {
                image
            }

            // Prepare JPEG bytes
            val outputStream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val imageBytes = outputStream.toByteArray()

            // Build request URL
            var base = BuildConfig.AZURE_FACE_ENDPOINT
            if (base.endsWith("/")) base = base.dropLast(1)
            val url = "$base/face/v1.0/detect" +
                    "?returnFaceId=false" +
                    "&detectionModel=detection_03" +
                    "&recognitionModel=recognition_04" +
                    "&returnFaceAttributes=headPose,glasses,mask,blur,exposure,qualityForRecognition"

            val request = Request.Builder()
                .url(url)
                .addHeader("Ocp-Apim-Subscription-Key", BuildConfig.AZURE_FACE_API_KEY)
                .addHeader("Content-Type", "application/octet-stream")
                .post(imageBytes.toRequestBody("application/octet-stream".toMediaType()))
                .build()

            http.newCall(request).execute().use { response ->
                val body = response.body

                if (!response.isSuccessful || body == null) {
                    if (response.code == 429) {
                        val retryAfter = response.header("Retry-After")
                        val retryMs = try {
                            retryAfter?.trim()?.toLong()?.times(1000L) ?: 15000L
                        } catch (e: Exception) {
                            15000L
                        }
                        Log.w(TAG, "Azure detect failed: $response, retryAfterMs=$retryMs")
                        throw RateLimitException(retryMs)
                    } else {
                        Log.w(TAG, "Azure detect failed: $response")
                        return@withContext emptyList()
                    }
                }

                val responseBody = body.string()
                val arr = JSONArray(responseBody)
                val results = mutableListOf<FaceInfo>()

                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val info = FaceInfo()

                    try {
                        obj.optJSONObject("faceRectangle")?.let { rect ->
                            info.left = rect.optInt("left")
                            info.top = rect.optInt("top")
                            info.width = rect.optInt("width")
                            info.height = rect.optInt("height")
                        }

                        obj.optJSONObject("faceAttributes")?.let { attrs ->
                            attrs.optJSONObject("headPose")?.let { headPose ->
                                info.yawDeg = headPose.optDouble("yaw")
                                info.pitchDeg = headPose.optDouble("pitch")
                                info.rollDeg = headPose.optDouble("roll")
                            }

                            val glasses = attrs.optString("glasses", "")
                            info.glassesType = glasses.ifEmpty { null }

                            attrs.optJSONObject("mask")?.let { mask ->
                                info.isMasked = if (mask.has("noseAndMouthCovered")) {
                                    mask.optBoolean("noseAndMouthCovered")
                                } else {
                                    val maskType = mask.optString("type", "")
                                    maskType.isNotEmpty() && !maskType.equals("noMask", ignoreCase = true)
                                }
                            }

                            attrs.optJSONObject("blur")?.let { blur ->
                                info.blurValue = blur.optDouble("value")
                            }

                            attrs.optJSONObject("exposure")?.let { exposure ->
                                val level = exposure.optString("exposureLevel", "")
                                info.exposureLevel = level.ifEmpty { null }
                            }

                            val quality = attrs.optString("qualityForRecognition", "")
                            if (quality.isNotEmpty()) info.imageQuality = quality
                        }
                    } catch (e: Exception) {
                        // Ignore parsing errors for individual faces
                    }

                    results.add(info)
                }

                Log.i(TAG, "Azure detect OK, faces=${results.size}")
                results
            }
        } catch (e: RateLimitException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Face detect REST failed", e)
            throw e
        }
    }

    /**
     * Legacy async method for Java interop.
     * New Kotlin code should use the suspend function detectFaces() instead.
     */
    @Deprecated("Use suspend fun detectFaces() for new Kotlin code")
    fun detectFacesAsync(image: Bitmap?, callback: FaceCallback?) {
        if (!isConfigured() || image == null) {
            callback?.onResult(emptyList())
            return
        }

        // Use a simple thread for legacy compatibility
        Thread({
            try {
                // This is a blocking call - not ideal but maintains compatibility
                val httpClient = HttpClientManager.getInstance().getApiClient()
                
                // Downscale
                val scaled = try {
                    val maxW = 640
                    if (image.width > maxW) {
                        val nh = (image.height * (maxW / image.width.toDouble())).toInt()
                        Bitmap.createScaledBitmap(image, maxW, nh, true)
                    } else {
                        image
                    }
                } catch (e: Exception) {
                    image
                }

                val outputStream = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                val imageBytes = outputStream.toByteArray()

                var base = BuildConfig.AZURE_FACE_ENDPOINT
                if (base.endsWith("/")) base = base.dropLast(1)
                val url = "$base/face/v1.0/detect" +
                        "?returnFaceId=false" +
                        "&detectionModel=detection_03" +
                        "&recognitionModel=recognition_04" +
                        "&returnFaceAttributes=headPose,glasses,mask,blur,exposure,qualityForRecognition"

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Ocp-Apim-Subscription-Key", BuildConfig.AZURE_FACE_API_KEY)
                    .addHeader("Content-Type", "application/octet-stream")
                    .post(imageBytes.toRequestBody("application/octet-stream".toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val body = response.body

                    if (!response.isSuccessful || body == null) {
                        if (response.code == 429) {
                            val retryAfter = response.header("Retry-After")
                            val retryMs = try {
                                retryAfter?.trim()?.toLong()?.times(1000L) ?: 15000L
                            } catch (parseEx: Exception) {
                                15000L
                            }
                            callback?.onError(RateLimitException(retryMs))
                        } else {
                            callback?.onResult(emptyList())
                        }
                        return@use
                    }

                    val responseBody = body.string()
                    val arr = JSONArray(responseBody as String)
                    val results = mutableListOf<FaceInfo>()

                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val info = FaceInfo()

                        try {
                            obj.optJSONObject("faceRectangle")?.let { rect: org.json.JSONObject ->
                                info.left = rect.optInt("left")
                                info.top = rect.optInt("top")
                                info.width = rect.optInt("width")
                                info.height = rect.optInt("height")
                            }

                            obj.optJSONObject("faceAttributes")?.let { attrs: org.json.JSONObject ->
                                attrs.optJSONObject("headPose")?.let { headPose: org.json.JSONObject ->
                                    info.yawDeg = headPose.optDouble("yaw")
                                    info.pitchDeg = headPose.optDouble("pitch")
                                    info.rollDeg = headPose.optDouble("roll")
                                }

                                val glasses = attrs.optString("glasses", "")
                                info.glassesType = glasses.ifEmpty { null }

                                attrs.optJSONObject("mask")?.let { mask: org.json.JSONObject ->
                                    info.isMasked = if (mask.has("noseAndMouthCovered")) {
                                        mask.optBoolean("noseAndMouthCovered")
                                    } else {
                                        val maskType = mask.optString("type", "")
                                        maskType.isNotEmpty() && !maskType.equals("noMask", ignoreCase = true)
                                    }
                                }

                                attrs.optJSONObject("blur")?.let { blur: org.json.JSONObject ->
                                    info.blurValue = blur.optDouble("value")
                                }

                                attrs.optJSONObject("exposure")?.let { exposure: org.json.JSONObject ->
                                    val level = exposure.optString("exposureLevel", "")
                                    info.exposureLevel = level.ifEmpty { null }
                                }

                                val quality = attrs.optString("qualityForRecognition", "")
                                if (quality.isNotEmpty()) info.imageQuality = quality
                            }
                        } catch (parseEx: Exception) {
                            // Ignore parsing errors for individual faces
                        }

                        results.add(info)
                    }

                    Log.i(TAG, "Azure detect OK, faces=${results.size}")
                    callback?.onResult(results)
                }
            } catch (e: RateLimitException) {
                callback?.onError(e)
            } catch (e: Exception) {
                Log.e(TAG, "Face detect REST failed", e)
                callback?.onError(e)
            }
        }, "face-recognition-thread").start()
    }
}


