package ch.fhnw.pepper_realtime.tools.navigation

import android.util.Log
import com.aldebaran.qi.sdk.QiContext
import ch.fhnw.pepper_realtime.robot.RobotSafetyGuard
import ch.fhnw.pepper_realtime.robot.MotionValidation
import ch.fhnw.pepper_realtime.tools.Tool
import ch.fhnw.pepper_realtime.tools.ToolContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Tool for turning Pepper robot left or right by specific degrees.
 * This is a synchronous tool that waits for turn completion.
 */
class TurnPepperTool : Tool {

    companion object {
        private const val TAG = "TurnPepperTool"
    }

    override fun getName(): String = "turn_pepper"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Turn Pepper robot left or right by a specific number of degrees. Use this when the user asks Pepper to turn or rotate.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("direction", JSONObject()
                        .put("type", "string")
                        .put("description", "Direction to turn")
                        .put("enum", JSONArray().put("left").put("right")))
                    put("degrees", JSONObject()
                        .put("type", "number")
                        .put("description", "Degrees to turn (15-180)")
                        .put("minimum", 15)
                        .put("maximum", 180))
                    put("speed", JSONObject()
                        .put("type", "number")
                        .put("description", "Optional GoTo maximum speed in m/s (0.1-0.55)")
                        .put("minimum", 0.1)
                        .put("maximum", 0.55)
                        .put("default", 0.5))
                })
                put("required", JSONArray().put("direction").put("degrees"))
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        val direction = args.optString("direction", "")
        val degrees: Double
        val speed: Double
        try {
            degrees = MotionValidation.number(args, "degrees")
            speed = MotionValidation.number(args, "speed", 0.5)
            MotionValidation.turn(direction, degrees, speed)
        } catch (e: IllegalArgumentException) {
            return JSONObject().put("error", e.message).toString()
        }

        // Check if robot is ready
        if (context.isQiContextNotReady()) {
            return JSONObject().put("error", "Robot not ready").toString()
        }

        val qiContext = context.qiContext as QiContext
        val safety = RobotSafetyGuard.evaluateMovementSafety(qiContext)
        if (!safety.isOk()) {
            return JSONObject().put("error", safety.message).toString()
        }

        // Use NavigationServiceManager for coordinated turn with service management
        val navManager = context.navigationServiceManager

        Log.i(TAG, "Starting synchronous turn: $direction $degrees degrees at max speed $speed m/s")

        // Create latch to wait for turn completion
        val latch = CountDownLatch(1)
        val finalResult = AtomicReference<String>()

        // Execute coordinated turn with service management; return result via Future
        navManager.turnPepper(qiContext, direction, degrees, speed, context.operation)
            .thenConsume { f ->
                val success = !f.hasError() && f.value?.success == true
                val error = if (f.hasError()) {
                    f.error?.message ?: "turn error"
                } else {
                    f.value?.error
                }
                Log.i(TAG, "Turn finished (tool future), success=$success, error=$error")
                try {
                    val result = JSONObject()
                    if (success) {
                        result.put("status", "Turn completed successfully")
                        result.put("direction", direction)
                        result.put("degrees", degrees)
                        result.put("message", String.format(
                            Locale.US,
                            "Turn completed successfully. Pepper has turned %s %.0f degrees.",
                            direction, degrees
                        ))
                    } else {
                        val userFriendlyError = translateTurnError(error)
                        result.put("error", String.format(
                            Locale.US,
                            "Turn failed: %s", userFriendlyError
                        ))
                    }
                    finalResult.set(result.toString())
                } catch (e: Exception) {
                    finalResult.set("{\"error\":\"Failed to create turn result\"}")
                }
                latch.countDown()
            }

        // Wait for turn to complete (with timeout)
        return if (latch.await(20, TimeUnit.SECONDS)) {
            finalResult.get()
        } else {
            JSONObject().put("error", "Turn timeout after 20 seconds").toString()
        }
    }



    /**
     * Translates technical QiSDK turn errors into user-friendly messages
     */
    private fun translateTurnError(error: String?): String {
        if (error.isNullOrEmpty()) {
            return "Something prevented me from turning."
        }

        val lowerError = error.lowercase()

        return when {
            lowerError.contains("obstacle") || lowerError.contains("blocked") ||
                    lowerError.contains("collision") || lowerError.contains("bump") ->
                "There's an obstacle preventing me from turning safely."
            lowerError.contains("timeout") || lowerError.contains("too long") ->
                "Turn took too long to complete and was stopped. There might be obstacles in the way."
            lowerError.contains("safety") || lowerError.contains("emergency") ->
                "Turn stopped for safety reasons - something is in the path."
            lowerError.contains("cancelled") || lowerError.contains("interrupted") ->
                "My turn was interrupted or cancelled."
            lowerError.contains("unreachable") || lowerError.contains("no space") ->
                "Not enough space available to complete this turn safely."
            else -> "A problem was encountered and the turn cannot be completed."
        }
    }
}


