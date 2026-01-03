package ch.fhnw.pepper_realtime.tools.navigation

import android.util.Log
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.ApproachHumanBuilder
import ch.fhnw.pepper_realtime.robot.RobotSafetyGuard
import ch.fhnw.pepper_realtime.tools.Tool
import ch.fhnw.pepper_realtime.tools.ToolContext
import org.json.JSONObject
import java.util.Locale

/**
 * Tool for making Pepper approach a detected human to initiate interaction.
 * Uses QiSDK's ApproachHuman action with safety checks and status updates.
 */
class ApproachHumanTool : Tool {



    override fun getName(): String = "approach_human"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Make Pepper approach a detected human to initiate interaction. Use this when the user wants Pepper to approach him. Pepper will move closer to the person while maintaining appropriate social distance.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("human_id", JSONObject()
                        .put("type", "integer")
                        .put("description", "Optional ID of specific human to approach (from human detection). If not provided, approaches the most suitable person based on engagement and attention signals."))
                    put("speed", JSONObject()
                        .put("type", "number")
                        .put("description", "Optional movement speed in m/s (0.1-0.55)")
                        .put("minimum", 0.1)
                        .put("maximum", 0.55)
                        .put("default", 0.3))
                })
                // No required parameters - tool can work with or without specific human ID
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        val humanId = if (args.has("human_id")) args.optInt("human_id") else null
        var speed = args.optDouble("speed", 0.3)

        // Validate speed
        if (speed < 0.1 || speed > 0.55) {
            speed = 0.3 // Use default speed if invalid
        }

        // Check if robot is ready
        if (context.isQiContextNotReady()) {
            return JSONObject().put("error", "Robot not ready").toString()
        }

        val qiContext = context.qiContext as QiContext
        val safety = RobotSafetyGuard.evaluateMovementSafety(qiContext)
        if (!safety.isOk()) {
            val message = safety.message ?: "Approach blocked by safety check"
            return JSONObject().put("error", message).toString()
        }



        return try {
            val perceptionService = context.perceptionService

            // Check if PerceptionService is available
            if (!perceptionService.isInitialized) {
                return JSONObject()
                    .put("error", "Human detection service not available. Please wait for the robot to initialize.")
                    .toString()
            }

            // Find target human
            val targetHuman: com.aldebaran.qi.sdk.`object`.human.Human?
            val targetDescription: String

            if (humanId != null) {
                // Try to find specific human by ID using PerceptionService
                targetHuman = perceptionService.getHumanById(humanId)

                if (targetHuman == null) {
                    return JSONObject()
                        .put("error", "Human with ID $humanId not found. The person may have moved away.")
                        .toString()
                }
                targetDescription = "human with ID $humanId"
            } else {
                // Get recommended human to approach using PerceptionService
                targetHuman = perceptionService.getRecommendedHumanToApproach()
                if (targetHuman == null) {
                    return JSONObject()
                        .put("error", "No suitable human found to approach. Make sure there are people nearby who are interested in interacting.")
                        .toString()
                }
                targetDescription = "recommended human"
            }

            // Start asynchronous approach
            val approachHuman = ApproachHumanBuilder.with(qiContext)
                .withHuman(targetHuman)
                .build()

            // Add listener for temporary unreachable state
            approachHuman.addOnHumanIsTemporarilyUnreachableListener {
                Log.i("ApproachHumanTool", "Human temporarily unreachable - sending status update")
                context.sendAsyncUpdate(
                    "[APPROACH INTERRUPTED] Pepper cannot reach the person. The path may be blocked by obstacles. " +
                            "Use vision analysis to identify what is blocking your path - look around your current position, starting with (1.0, 0.0, 0.0) in front of you, to see what obstacles are nearby.",
                    true
                )
            }

            // Execute approach asynchronously
            val approachFuture = approachHuman.async().run()

            approachFuture.thenConsume { future ->
                val success = !future.hasError()
                val error = if (future.hasError()) {
                    future.error?.message ?: "Unknown approach error"
                } else null

                Log.i("ApproachHumanTool", "Approach completed - success: $success, error: $error")

                val message: String
                if (success) {
                    message = String.format(
                        Locale.US,
                        "[APPROACH COMPLETED] Pepper has successfully approached the %s and is now ready for interaction. The robot is positioned at an appropriate social distance.",
                        targetDescription
                    )
                } else {
                    val userFriendlyError = translateApproachError(error)

                    // Build the base error message
                    val messageBuilder = StringBuilder()
                    messageBuilder.append(
                        String.format(
                            Locale.US,
                            "[APPROACH FAILED] Pepper could not approach the %s. %s Please inform the user and suggest alternatives like asking the person to come closer.",
                            targetDescription, userFriendlyError
                        )
                    )

                    // Add vision analysis suggestion for obstacle-related errors in the same message
                    val isObstacleError = isObstacleRelatedApproachError(error)
                    Log.i("ApproachHumanTool", "Approach error: '$error' -> obstacle-related: $isObstacleError")
                    if (isObstacleError) {
                        messageBuilder.append(
                            String.format(
                                Locale.US,
                                " Use vision analysis to identify what is blocking your path - " +
                                        "look around your current position, starting with (%.2f, %.2f, %.2f) in front of you, to see what obstacles are nearby.",
                                1.0, 0.0, 0.0
                            )
                        ) // Always look 1m forward from current position
                    }

                    message = messageBuilder.toString()
                }

                context.sendAsyncUpdate(message, true)
            }

            // Return immediate confirmation
            JSONObject().apply {
                put("status", "approach_started")
                put("target_human", targetDescription)
                put("speed", speed)
                put("message", String.format(
                    Locale.US,
                    "Approach started. Pepper is now moving towards the %s to initiate interaction.",
                    targetDescription
                ))
            }.toString()

        } catch (e: Exception) {
            Log.e("ApproachHumanTool", "Error during human approach", e)
            JSONObject()
                .put("error", "Failed to approach human: ${e.message}")
                .toString()
        }
    }


    /**
     * Check if the approach error is related to obstacles that could benefit from vision analysis
     */
    private fun isObstacleRelatedApproachError(error: String?): Boolean {
        if (error.isNullOrEmpty()) {
            return true // Default fallback suggests obstacles
        }

        val lowerError = error.lowercase()

        // Approach errors that are likely obstacle-related and would benefit from vision analysis
        return lowerError.contains("obstacle") || lowerError.contains("blocked") ||
                lowerError.contains("collision") || lowerError.contains("bump") ||
                lowerError.contains("unreachable") || lowerError.contains("no path") ||
                lowerError.contains("path planning") || lowerError.contains("navigation failed") ||
                lowerError.contains("timeout") || lowerError.contains("took too long") ||
                lowerError.contains("safety") || lowerError.contains("emergency") ||
                lowerError.contains("approach failed") || lowerError.contains("failed to complete")
        // Note: Cancelled/lost/disappeared errors are NOT obstacle-related, so no vision suggestion
    }

    /**
     * Translates technical QiSDK approach errors into user-friendly messages
     */
    private fun translateApproachError(technicalError: String?): String {
        if (technicalError.isNullOrEmpty()) {
            return "An unknown error occurred during approach."
        }

        val lowerError = technicalError.lowercase()

        return when {
            lowerError.contains("obstacle") || lowerError.contains("blocked") ->
                "The path to the person is blocked by obstacles."
            lowerError.contains("unreachable") || lowerError.contains("no path") ->
                "The person cannot be reached from the current position."
            lowerError.contains("timeout") || lowerError.contains("took too long") ->
                "The approach took too long and was cancelled for safety."
            lowerError.contains("lost") || lowerError.contains("disappeared") ->
                "The person was lost during approach - they may have moved away."
            lowerError.contains("cancelled") || lowerError.contains("interrupted") ->
                "The approach was interrupted or cancelled."
            lowerError.contains("safety") || lowerError.contains("emergency") ->
                "The approach was stopped for safety reasons."
            else -> "Approach failed: $technicalError"
        }
    }
}


