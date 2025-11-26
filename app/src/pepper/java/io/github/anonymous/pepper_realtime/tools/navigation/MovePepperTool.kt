package io.github.anonymous.pepper_realtime.tools.navigation

import android.util.Log
import com.aldebaran.qi.sdk.QiContext
import io.github.anonymous.pepper_realtime.robot.RobotSafetyGuard
import io.github.anonymous.pepper_realtime.tools.BaseTool
import io.github.anonymous.pepper_realtime.tools.ToolContext
import org.json.JSONObject
import java.util.Locale

/**
 * Tool for moving Pepper robot in specific directions.
 * Supports forward, backward, left, right movement with safety checks.
 */
class MovePepperTool : BaseTool() {

    companion object {
        private const val TAG = "MovePepperTool"
    }

    override fun getName(): String = "move_pepper"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Move Pepper robot in a specific direction for a given distance. Use this when the user asks Pepper to move around the room. Call the function directly without announcing it. You can combine forward/backward and sideways movements.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("distance_forward", JSONObject()
                        .put("type", "number")
                        .put("description", "Distance to move forward (positive) or backward (negative) in meters (-4.0 to 4.0). Optional, defaults to 0.")
                        .put("minimum", -4.0)
                        .put("maximum", 4.0))
                    put("distance_sideways", JSONObject()
                        .put("type", "number")
                        .put("description", "Distance to move left (positive) or right (negative) in meters (-4.0 to 4.0). Optional, defaults to 0.")
                        .put("minimum", -4.0)
                        .put("maximum", 4.0))
                    put("speed", JSONObject()
                        .put("type", "number")
                        .put("description", "Optional maximum speed in m/s (0.1-0.55)")
                        .put("minimum", 0.1)
                        .put("maximum", 0.55)
                        .put("default", 0.4))
                })
                // No required parameters, as at least one of the distances should be provided.
                // This is handled in the execute logic.
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        val distanceForward = args.optDouble("distance_forward", 0.0)
        val distanceSideways = args.optDouble("distance_sideways", 0.0)
        val speed = args.optDouble("speed", 0.4)

        // Validate that at least one movement is requested
        if (distanceForward == 0.0 && distanceSideways == 0.0) {
            return JSONObject().put("error", "Please provide a non-zero distance for 'distance_forward' or 'distance_sideways'.").toString()
        }

        // Check if robot is ready
        if (context.isQiContextNotReady()) {
            return JSONObject().put("error", "Robot not ready").toString()
        }

        val qiContext = context.qiContext as QiContext
        val safety = RobotSafetyGuard.evaluateMovementSafety(qiContext)
        if (!safety.isOk()) {
            val message = safety.message ?: "Movement blocked by safety check"
            return JSONObject().put("error", message).toString()
        }

        Log.i(TAG, "Starting movement: forward=${distanceForward}m, sideways=${distanceSideways}m at ${speed}m/s")

        // Use NavigationServiceManager for coordinated movement with service management
        val navManager = context.navigationServiceManager

        // Execute movement using manager with tool-level callback (does not override manager listener)
        navManager.movePepper(qiContext, distanceForward, distanceSideways, speed)
            .thenConsume { f ->
                val success = !f.hasError() && f.value?.success == true
                val error = if (f.hasError()) {
                    f.error?.message ?: "movement error"
                } else {
                    f.value?.error
                }
                Log.i(TAG, "Movement finished (tool future), success=$success, error=$error")

                val message: String
                val movementDesc = buildMovementDescription(distanceForward, distanceSideways)

                if (success) {
                    message = String.format(
                        Locale.US,
                        "[MOVEMENT COMPLETED] You have successfully moved %s and arrived at your destination. Please inform the user that you have completed the movement.",
                        movementDesc
                    )
                } else {
                    val userFriendlyError = translateMovementError(error)

                    // Build the base error message
                    val messageBuilder = StringBuilder()
                    messageBuilder.append(
                        String.format(
                            Locale.US,
                            "[MOVEMENT FAILED] You couldn't complete the movement %s. %s Please inform the user about this problem and offer alternative solutions or ask if they want you to try a different direction.",
                            movementDesc, userFriendlyError
                        )
                    )

                    // Add vision analysis suggestion for obstacle-related errors in the same message
                    val isObstacleError = isObstacleRelatedError(error)
                    Log.i(TAG, "Movement error: '$error' -> obstacle-related: $isObstacleError")
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

        // Return immediate confirmation - emphasize that movement is NOT completed yet
        return JSONObject().apply {
            put("status", "Movement started")
            put("distance_forward", distanceForward)
            put("distance_sideways", distanceSideways)
            put("speed", speed)
            put("message", String.format(
                Locale.US,
                "IMPORTANT: Movement has JUST STARTED and is currently in progress. Pepper is moving %s but has NOT YET arrived at the destination. " +
                        "Do NOT tell the user you have completed the movement or arrived yet. You will receive a separate [MOVEMENT COMPLETED] update " +
                        "when the movement is actually finished. Until then, you can acknowledge that the movement has started.",
                buildMovementDescription(distanceForward, distanceSideways)
            ))
        }.toString()
    }

    override fun requiresApiKey(): Boolean = false

    override fun getApiKeyType(): String? = null

    /**
     * Builds a human-readable description of the movement for logs and messages.
     */
    private fun buildMovementDescription(forward: Double, sideways: Double): String {
        val desc = StringBuilder()
        if (forward != 0.0) {
            desc.append(String.format(Locale.US, "%.1f meters %s", kotlin.math.abs(forward), if (forward > 0) "forward" else "backward"))
        }
        if (sideways != 0.0) {
            if (desc.isNotEmpty()) {
                desc.append(" and ")
            }
            desc.append(String.format(Locale.US, "%.1f meters to the %s", kotlin.math.abs(sideways), if (sideways > 0) "left" else "right"))
        }
        return desc.toString()
    }

    /**
     * Check if the error is related to obstacles that could benefit from vision analysis
     */
    private fun isObstacleRelatedError(error: String?): Boolean {
        if (error.isNullOrEmpty()) {
            return true // Default fallback suggests obstacles
        }

        val lowerError = error.lowercase()

        // Most movement errors are obstacle-related and would benefit from vision analysis
        return lowerError.contains("obstacle") || lowerError.contains("blocked") ||
                lowerError.contains("collision") || lowerError.contains("bump") ||
                lowerError.contains("unreachable") || lowerError.contains("no path") ||
                lowerError.contains("path planning") || lowerError.contains("navigation failed") ||
                lowerError.contains("timeout") || lowerError.contains("too long") ||
                lowerError.contains("safety") || lowerError.contains("emergency") ||
                lowerError.contains("goto failed") || lowerError.contains("failed to complete") ||
                lowerError.contains("movement failed") || lowerError.contains("cannot move")
        // Note: Cancelled/interrupted errors are NOT obstacle-related, so no vision suggestion
    }

    /**
     * Translates technical QiSDK movement errors into user-friendly messages
     */
    private fun translateMovementError(error: String?): String {
        if (error.isNullOrEmpty()) {
            return "My path is blocked by an obstacle."
        }

        val lowerError = error.lowercase()

        return when {
            lowerError.contains("obstacle") || lowerError.contains("blocked") ||
                    lowerError.contains("collision") || lowerError.contains("bump") ->
                "My path is blocked by an obstacle in front of me."
            lowerError.contains("unreachable") || lowerError.contains("no path") ||
                    lowerError.contains("path planning") || lowerError.contains("navigation failed") ->
                "No safe path could be found to reach that location."
            lowerError.contains("timeout") || lowerError.contains("too long") ->
                "Movement took too long and was stopped. There are likely obstacles blocking the path."
            lowerError.contains("safety") || lowerError.contains("emergency") ->
                "Movement stopped for safety reasons - there's something in the path."
            lowerError.contains("cancelled") || lowerError.contains("interrupted") ->
                "My movement was interrupted or cancelled."
            else -> "An obstacle was encountered and movement cannot continue in that direction."
        }
    }
}

