package io.github.anonymous.pepper_realtime.tools.navigation

import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.actuation.Frame
import com.aldebaran.qi.sdk.`object`.actuation.GoTo
import com.aldebaran.qi.sdk.`object`.human.Human
import com.aldebaran.qi.sdk.builder.GoToBuilder
import com.aldebaran.qi.sdk.builder.TransformBuilder
import io.github.anonymous.pepper_realtime.robot.RobotSafetyGuard
import io.github.anonymous.pepper_realtime.tools.Tool
import io.github.anonymous.pepper_realtime.tools.ToolContext
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Tool for making Pepper continuously follow the nearest human at a specified distance.
 * Uses QiSDK's GoTo action with AttachedFrame to track human movement.
 * 
 * The following continues until stop_follow_human is called or the human is lost.
 * GoTo is restarted in a loop to continuously track the moving human.
 */
class FollowHumanTool : Tool {

    companion object {
        private const val TAG = "FollowHumanTool"
        private const val FOLLOW_LOOP_DELAY_MS = 500L
        
        // Lock object for thread-safe state management
        private val stateLock = Any()
        
        // Shared state for stop functionality - all access must be synchronized on stateLock
        private val shouldStop = AtomicBoolean(false)
        
        @Volatile
        private var activeGoToFuture: Future<Void>? = null
        
        @Volatile
        private var activeGoTo: GoTo? = null
        
        @Volatile
        private var isFollowing: Boolean = false
        
        @Volatile
        private var activeContext: ToolContext? = null
        
        @Volatile
        private var activeQiContext: QiContext? = null
        
        @Volatile
        private var followDistance: Double = 1.0
        
        /**
         * Check if currently following a human (thread-safe)
         */
        fun isCurrentlyFollowing(): Boolean {
            synchronized(stateLock) {
                return isFollowing
            }
        }
        
        /**
         * Stop the active following action (thread-safe)
         * @return true if stopped successfully, false if not following
         */
        fun stopFollowing(): Boolean {
            synchronized(stateLock) {
                if (!isFollowing) {
                    return false
                }
                
                Log.i(TAG, "Stopping follow action")
                shouldStop.set(true)
                activeGoToFuture?.requestCancellation()
                // Don't call cleanup() here - let the follow loop handle it
                // This prevents the race condition where cleanup resets shouldStop
                return true
            }
        }
        
        private fun cleanup() {
            synchronized(stateLock) {
                activeGoTo?.removeAllOnStartedListeners()
                activeGoTo = null
                activeGoToFuture = null
                isFollowing = false
                activeContext = null
                activeQiContext = null
                // Note: shouldStop is NOT reset here - it's reset at the start of execute()
            }
        }
    }

    override fun getName(): String = "follow_human"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Make Pepper continuously follow the nearest human at a specified distance. " +
                    "The robot will keep following until stop_follow_human is called or the person is lost. " +
                    "Use this when the user wants Pepper to follow them around.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("distance", JSONObject()
                        .put("type", "number")
                        .put("description", "Distance to maintain from the human in meters (0.5-3.0)")
                        .put("minimum", 0.5)
                        .put("maximum", 3.0)
                        .put("default", 1.0))
                })
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        var distance = args.optDouble("distance", 1.0)

        // Validate distance
        if (distance < 0.5 || distance > 3.0) {
            distance = 1.0
        }

        // Check if robot is ready (before acquiring lock to fail fast)
        if (context.isQiContextNotReady()) {
            return JSONObject().put("error", "Robot not ready").toString()
        }

        val qiContext = context.qiContext as QiContext
        
        // Safety check (before acquiring lock to fail fast)
        val safety = RobotSafetyGuard.evaluateMovementSafety(qiContext)
        if (!safety.isOk()) {
            val message = safety.message ?: "Follow blocked by safety check"
            return JSONObject().put("error", message).toString()
        }

        val perceptionService = context.perceptionService

        // Check if PerceptionService is available
        if (!perceptionService.isInitialized) {
            return JSONObject()
                .put("error", "Human detection service not available. Please wait for robot to initialize.")
                .toString()
        }

        // Find closest human for initial check
        val humans = perceptionService.getDetectedHumans()
        if (humans.isEmpty()) {
            return JSONObject()
                .put("error", "No human found nearby to follow. Make sure someone is visible to the robot.")
                .toString()
        }

        // Synchronized block for state management - prevents race conditions
        synchronized(stateLock) {
            // Check if already following (inside lock to prevent race)
            if (isFollowing) {
                return JSONObject()
                    .put("error", "Already following a human. Call stop_follow_human first.")
                    .toString()
            }

            Log.i(TAG, String.format(Locale.US, "Starting follow human - distance: %.1fm", distance))

            // Reset shouldStop flag BEFORE setting isFollowing
            // This ensures any previous stop request is cleared
            shouldStop.set(false)
            
            // Store state atomically
            activeContext = context
            activeQiContext = qiContext
            followDistance = distance
            isFollowing = true
        }

        // Start the follow loop in a background thread (outside lock)
        Thread {
            followLoop(context, qiContext)
        }.start()

        // Return immediate success
        return JSONObject().apply {
            put("status", "following_started")
            put("distance", distance)
            put("message", String.format(
                Locale.US,
                "Pepper is now following the nearest person at %.1f meters distance. " +
                        "Call stop_follow_human to stop.",
                distance
            ))
        }.toString()
    }

    /**
     * Main follow loop - continuously tracks and follows the human
     */
    private fun followLoop(context: ToolContext, qiContext: QiContext) {
        Log.i(TAG, "Follow loop started")
        var consecutiveFailures = 0
        val maxConsecutiveFailures = 3
        
        try {
            while (!shouldStop.get() && isCurrentlyFollowing()) {
                try {
                    // Find closest human
                    val humans = context.perceptionService.getDetectedHumans()
                    if (humans.isEmpty()) {
                        consecutiveFailures++
                        Log.w(TAG, "No humans detected (failure $consecutiveFailures/$maxConsecutiveFailures)")
                        
                        if (consecutiveFailures >= maxConsecutiveFailures) {
                            Log.e(TAG, "Lost track of human after $maxConsecutiveFailures attempts")
                            context.sendAsyncUpdate(
                                "[FOLLOW INTERRUPTED] Pepper has lost track of the person. Ask the user if they want to restart following.",
                                true
                            )
                            break
                        }
                        
                        Thread.sleep(FOLLOW_LOOP_DELAY_MS)
                        continue
                    }
                    
                    consecutiveFailures = 0 // Reset on successful detection
                    
                    val closestHuman = getClosestHuman(humans, qiContext)
                    if (closestHuman == null) {
                        Log.w(TAG, "Could not determine closest human")
                        Thread.sleep(FOLLOW_LOOP_DELAY_MS)
                        continue
                    }

                    // Create target frame attached to human with distance offset
                    val targetFrame = createTargetFrame(closestHuman, followDistance)

                    // Build GoTo action
                    val goTo = GoToBuilder.with(qiContext)
                        .withFrame(targetFrame)
                        .build()

                    synchronized(stateLock) {
                        activeGoTo = goTo
                    }

                    // Execute synchronously within the loop
                    Log.d(TAG, "Starting GoTo iteration")
                    val goToFuture = goTo.async().run()
                    
                    synchronized(stateLock) {
                        activeGoToFuture = goToFuture
                    }

                    // Wait for this GoTo to complete
                    try {
                        goToFuture.get() // Block until done
                        Log.d(TAG, "GoTo iteration completed successfully")
                    } catch (e: Exception) {
                        if (shouldStop.get()) {
                            Log.i(TAG, "GoTo cancelled by stop request")
                            break
                        }
                        
                        val errorMsg = e.message ?: "Unknown error"
                        Log.w(TAG, "GoTo iteration failed: $errorMsg")
                        
                        // Check for blocking errors
                        if (errorMsg.contains("obstacle", ignoreCase = true) ||
                            errorMsg.contains("blocked", ignoreCase = true) ||
                            errorMsg.contains("unreachable", ignoreCase = true)) {
                            context.sendAsyncUpdate(
                                "[FOLLOW BLOCKED] Path to person is obstructed. Pepper cannot continue following.",
                                true
                            )
                            break
                        }
                    }

                    // Clean up this iteration's GoTo
                    goTo.removeAllOnStartedListeners()
                    
                    // Small delay before next iteration to avoid CPU spinning
                    Thread.sleep(FOLLOW_LOOP_DELAY_MS)
                    
                } catch (e: InterruptedException) {
                    Log.i(TAG, "Follow loop interrupted")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in follow loop", e)
                    if (!shouldStop.get()) {
                        context.sendAsyncUpdate(
                            "[FOLLOW INTERRUPTED] Pepper stopped following due to an error. Ask the user if they want to restart.",
                            true
                        )
                    }
                    break
                }
            }
        } finally {
            Log.i(TAG, "Follow loop ended")
            cleanup()
        }
    }

    /**
     * Find the closest human to the robot
     */
    private fun getClosestHuman(humans: List<Human>, qiContext: QiContext): Human? {
        if (humans.isEmpty()) return null
        
        val robotFrame = qiContext.actuation.robotFrame()
        
        return humans.minByOrNull { human ->
            try {
                val humanFrame = human.headFrame
                val translation = humanFrame.computeTransform(robotFrame).transform.translation
                val x = translation.x
                val y = translation.y
                sqrt(x * x + y * y)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to compute distance to human", e)
                Double.MAX_VALUE
            }
        }
    }

    /**
     * Create a target frame attached to the human with specified distance offset.
     * The frame moves with the human automatically.
     */
    private fun createTargetFrame(human: Human, distance: Double): Frame {
        // Get the human head frame
        val humanFrame = human.headFrame
        
        // Create transform with distance offset on X axis (in front of human)
        val transform = TransformBuilder.create().fromXTranslation(distance)
        
        // Create AttachedFrame that updates with human movement
        val attachedFrame = humanFrame.makeAttachedFrame(transform)
        
        return attachedFrame.frame()
    }
}
