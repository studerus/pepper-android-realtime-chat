package io.github.anonymous.pepper_realtime.tools.navigation

import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.LookAtBuilder
import com.aldebaran.qi.sdk.builder.TransformBuilder
import com.aldebaran.qi.sdk.`object`.actuation.LookAtMovementPolicy
import com.aldebaran.qi.sdk.`object`.geometry.Vector3
import io.github.anonymous.pepper_realtime.tools.Tool
import io.github.anonymous.pepper_realtime.tools.ToolContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Tool for making Pepper look at a specific 3D position relative to the robot's base frame.
 * Waits for head movement to complete before returning success, then automatically cancels after specified duration.
 */
class LookAtPositionTool : Tool {

    companion object {
        private const val TAG = "LookAtPositionTool"
        
        // Wait time constants for head alignment
        private const val MIN_ALIGNMENT_WAIT_MS = 300L   // Minimum wait for small movements
        private const val MAX_ALIGNMENT_WAIT_MS = 1000L  // Maximum wait for large movements
        
        // Default gaze position (straight ahead at eye level)
        private const val DEFAULT_GAZE_Y = 0.0   // Center (no left/right)
        private const val DEFAULT_GAZE_Z = 1.2   // Eye level height
    }

    override fun getName(): String = "look_at_position"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Make Pepper look at a specific 3D position relative to the robot's base frame. " +
                    "X: forward(+)/backward(-), Y: left(+)/right(-), Z: up(+)/down(-). " +
                    "Robot base is at ground level (Z=0). Typical values: ground Z=0, eye-level Z=1.2, ceiling Z=2.5+. " +
                    "Returns success immediately when gaze is aligned, then automatically returns to normal after the specified duration. " +
                    "Perfect for combining with vision analysis - look first, then analyze what you see.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("x", JSONObject()
                        .put("type", "number")
                        .put("description", "Distance forward (positive) or backward (negative) from robot base in meters")
                        .put("minimum", -5.0)
                        .put("maximum", 5.0))
                    put("y", JSONObject()
                        .put("type", "number")
                        .put("description", "Distance left (positive) or right (negative) from robot base in meters")
                        .put("minimum", -5.0)
                        .put("maximum", 5.0))
                    put("z", JSONObject()
                        .put("type", "number")
                        .put("description", "Distance up (positive) or down (negative) from robot base in meters. Ground=0, eye-level=1.2, ceiling=2.5+")
                        .put("minimum", -2.0)
                        .put("maximum", 5.0))
                    put("movement_policy", JSONObject()
                        .put("type", "string")
                        .put("description", "Movement policy for looking")
                        .put("enum", JSONArray().put("head_only").put("whole_body"))
                        .put("default", "head_only"))
                    put("duration", JSONObject()
                        .put("type", "number")
                        .put("description", "Duration to look at the position in seconds before automatically returning to normal gaze")
                        .put("minimum", 1)
                        .put("maximum", 15)
                        .put("default", 3))
                })
                put("required", JSONArray().put("x").put("y").put("z"))
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        val x = args.optDouble("x", 0.0)
        val y = args.optDouble("y", 0.0)
        val z = args.optDouble("z", 0.0)
        val movementPolicy = args.optString("movement_policy", "head_only")
        val duration = args.optDouble("duration", 3.0) // Default 3 seconds

        // Validate that at least one coordinate is non-zero
        if (x == 0.0 && y == 0.0 && z == 0.0) {
            return JSONObject().put("error", "Please provide non-zero coordinates for x, y, or z.").toString()
        }

        // Validate duration
        if (duration < 1 || duration > 15) {
            return JSONObject().put("error", "Duration must be between 1 and 15 seconds").toString()
        }

        // Check if robot is ready
        if (context.isQiContextNotReady()) {
            return JSONObject().put("error", "Robot not ready").toString()
        }

        // Check if charging flap is open - only required for whole_body movement (HEAD_AND_BASE)
        if (movementPolicy == "whole_body" && isChargingFlapOpen(context)) {
            return JSONObject().put("error", "Cannot perform whole body look action while charging flap is open. Please close the charging flap first for safety, or use 'head_only' movement policy.").toString()
        }

        Log.i(TAG, String.format(Locale.US, "Starting LookAt: position(%.2f, %.2f, %.2f) with policy: %s, duration: %.1fs",
            x, y, z, movementPolicy, duration))

        return try {
            // Get QiSDK services
            val qiContext = context.qiContext as QiContext
            val actuation = qiContext.actuation
            val mapping = qiContext.mapping

            // Get robot frame as base
            val robotFrame = actuation.robotFrame()

            // Create 3D transform to target position
            val translation = Vector3(x, y, z)
            val transform = TransformBuilder.create().fromTranslation(translation)

            // Create target frame
            val targetFrame = mapping.makeFreeFrame()
            targetFrame.update(robotFrame, transform, 0L)

            // Create LookAt action with movement policy
            val lookAt = LookAtBuilder.with(qiContext)
                .withFrame(targetFrame.frame())
                .build()

            // Set movement policy based on parameter
            lookAt.policy = if (movementPolicy == "whole_body") {
                LookAtMovementPolicy.HEAD_AND_BASE
            } else {
                LookAtMovementPolicy.HEAD_ONLY
            }

            // Create latch to wait for LookAt to START (not complete)
            val startedLatch = CountDownLatch(1)
            val finalResult = AtomicReference<String>()

            // Calculate wait time based on movement magnitude
            val alignmentWaitMs = calculateAlignmentWaitTime(y, z)
            
            // Add started listener - return success immediately when gaze is aligned
            lookAt.addOnStartedListener {
                Log.i(TAG, "LookAt action started")
                try {
                    val result = JSONObject().apply {
                        put("status", "LookAt aligned successfully")
                        put("x", x)
                        put("y", y)
                        put("z", z)
                        put("movement_policy", movementPolicy)
                        put("duration", duration)
                        put("alignment_wait_ms", alignmentWaitMs)
                        put("message", String.format(Locale.US,
                            "Successfully looking at position (%.2f, %.2f, %.2f) for %.1f seconds.",
                            x, y, z, duration))
                    }
                    finalResult.set(result.toString())
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating success result", e)
                    finalResult.set("{\"error\":\"Failed to create LookAt result\"}")
                }
                startedLatch.countDown()
            }

            // Execute LookAt action asynchronously and store Future for cancellation
            val lookAtFuture: Future<Void> = lookAt.async().run()
            lookAtFuture.thenConsume { future ->
                if (future.hasError()) {
                    Log.w(TAG, "LookAt action failed: ${future.error?.message}")
                    // Only set error if we haven't already returned success
                    if (startedLatch.count > 0) {
                        try {
                            val error = future.error?.message
                            val userFriendlyError = translateLookAtError(error)
                            val errorResult = JSONObject().put("error", String.format(Locale.US, "LookAt failed: %s", userFriendlyError))
                            finalResult.set(errorResult.toString())
                        } catch (e: Exception) {
                            finalResult.set("{\"error\":\"LookAt action failed\"}")
                        }
                        startedLatch.countDown()
                    }
                }
                // Note: We don't handle success here anymore, only in onStartedListener
            }

            // Wait for LookAt to START (with timeout)
            if (startedLatch.await(5, TimeUnit.SECONDS)) {
                // Wait for head to physically move to target position
                // The wait time is proportional to the movement magnitude
                // We sleep here in the tool thread instead of the listener callback to avoid blocking QiSDK threads
                Log.i(TAG, "LookAt started - waiting ${alignmentWaitMs}ms for head alignment")
                try {
                    Thread.sleep(alignmentWaitMs)
                } catch (e: InterruptedException) {
                    Log.w(TAG, "Alignment wait interrupted", e)
                    Thread.currentThread().interrupt()
                }
                
                Log.i(TAG, "Head alignment complete - returning success")

                // LookAt has started successfully and aligned, now schedule auto-cancel
                val cancelTimer = Timer()
                cancelTimer.schedule(object : TimerTask() {
                    override fun run() {
                        Log.i(TAG, String.format(Locale.US, "Auto-cancelling LookAt after %.1f seconds", duration))
                        lookAtFuture.requestCancellation()
                        cancelTimer.cancel() // Clean up timer
                    }
                }, (duration * 1000).toLong()) // Convert to milliseconds

                finalResult.get()
            } else {
                // Cancel the action if startup timeout
                lookAtFuture.requestCancellation()
                JSONObject().put("error", "LookAt failed to start within 5 seconds").toString()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error executing LookAt action", e)
            JSONObject().put("error", "Failed to execute LookAt action: ${e.message}").toString()
        }
    }


    /**
     * Calculate how long to wait for head alignment based on movement magnitude.
     * Larger movements (e.g., looking far left/right or up/down) require more time.
     * 
     * @param y Left/right position (positive = left, negative = right)
     * @param z Height position (0 = ground, 1.2 = eye level, 2.5+ = ceiling)
     * @return Wait time in milliseconds (300-1000ms)
     */
    private fun calculateAlignmentWaitTime(y: Double, z: Double): Long {
        // Calculate deviation from default gaze position
        val yDeviation = kotlin.math.abs(y - DEFAULT_GAZE_Y)  // How far left/right
        val zDeviation = kotlin.math.abs(z - DEFAULT_GAZE_Z)  // How far from eye level
        
        // Calculate overall movement magnitude using Euclidean distance
        val movementMagnitude = kotlin.math.sqrt(yDeviation * yDeviation + zDeviation * zDeviation)
        
        // Normalize to 0.0-1.0 range (2.0 is considered max practical movement)
        val normalizedMagnitude = kotlin.math.min(movementMagnitude / 2.0, 1.0)
        
        // Scale linearly between MIN and MAX wait times
        val waitTime = MIN_ALIGNMENT_WAIT_MS + (normalizedMagnitude * (MAX_ALIGNMENT_WAIT_MS - MIN_ALIGNMENT_WAIT_MS)).toLong()
        
        Log.d(TAG, String.format(Locale.US, 
            "Movement magnitude: %.2f (y_dev=%.2f, z_dev=%.2f) → wait time: %dms",
            movementMagnitude, yDeviation, zDeviation, waitTime))
        
        return waitTime
    }

    /**
     * Check if the charging flap is open, which prevents movement for safety reasons
     */
    private fun isChargingFlapOpen(context: ToolContext): Boolean {
        return try {
            val qiContext = context.qiContext as QiContext
            val power = qiContext.power
            val chargingFlap = power.chargingFlap

            if (chargingFlap != null) {
                val flapState = chargingFlap.state
                val isOpen = flapState.open
                Log.d(TAG, "Charging flap status: ${if (isOpen) "OPEN (action blocked)" else "CLOSED (action allowed)"}")
                isOpen
            } else {
                Log.d(TAG, "No charging flap sensor available - assuming action is allowed")
                false // Assume closed if sensor not available
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not check charging flap status: ${e.message}", e)
            false // Allow action if check fails to avoid false blocking
        }
    }

    /**
     * Translates technical QiSDK LookAt errors into user-friendly messages
     */
    private fun translateLookAtError(error: String?): String {
        if (error.isNullOrEmpty()) {
            return "Something prevented me from looking at the specified position."
        }

        val lowerError = error.lowercase()

        return when {
            lowerError.contains("unreachable") || lowerError.contains("out of range") ->
                "The target position is out of my range or cannot be reached by my head movement."
            lowerError.contains("invalid") || lowerError.contains("bad") ->
                "The specified position coordinates are invalid or impossible to reach."
            lowerError.contains("timeout") || lowerError.contains("too long") ->
                "The look action took too long to complete and was stopped."
            lowerError.contains("cancelled") || lowerError.contains("interrupted") ->
                "My look action was interrupted or cancelled."
            lowerError.contains("obstacle") || lowerError.contains("blocked") ->
                "Something is blocking my head movement to the target position."
            lowerError.contains("safety") || lowerError.contains("emergency") ->
                "Look action stopped for safety reasons."
            else -> "A problem was encountered and the look action cannot be completed."
        }
    }
}


