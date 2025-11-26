package io.github.anonymous.pepper_realtime.tools.navigation

import android.util.Log
import com.aldebaran.qi.sdk.QiContext
import io.github.anonymous.pepper_realtime.tools.Tool
import io.github.anonymous.pepper_realtime.tools.ToolContext
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
                        .put("description", "Optional turning speed in rad/s (0.1-1.0)")
                        .put("minimum", 0.1)
                        .put("maximum", 1.0)
                        .put("default", 0.5))
                })
                put("required", JSONArray().put("direction").put("degrees"))
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        val direction = args.optString("direction", "")
        val degrees = args.optDouble("degrees", 0.0)
        val speed = args.optDouble("speed", 0.5)

        // Validate required parameters
        if (direction.isEmpty()) {
            return JSONObject().put("error", "Missing required parameter: direction").toString()
        }
        if (degrees <= 0) {
            return JSONObject().put("error", "Missing or invalid parameter: degrees").toString()
        }

        // Validate direction
        if (direction != "left" && direction != "right") {
            return JSONObject().put("error", "Invalid direction. Use: left, right").toString()
        }

        // Validate degrees range
        if (degrees < 15 || degrees > 180) {
            return JSONObject().put("error", "Degrees must be between 15 and 180").toString()
        }

        // Check if robot is ready
        if (context.isQiContextNotReady()) {
            return JSONObject().put("error", "Robot not ready").toString()
        }

        // Check if charging flap is open (prevents movement for safety)
        if (isChargingFlapOpen(context)) {
            return JSONObject().put("error", "Cannot turn while charging flap is open. Please close the charging flap first for safety.").toString()
        }

        // Use NavigationServiceManager for coordinated turn with service management
        val navManager = context.navigationServiceManager

        Log.i(TAG, "Starting synchronous turn: $direction $degrees degrees at $speed rad/s")

        // Create latch to wait for turn completion
        val latch = CountDownLatch(1)
        val finalResult = AtomicReference<String>()

        // Execute coordinated turn with service management; return result via Future
        val qiContext = context.qiContext as QiContext
        navManager.turnPepper(qiContext, direction, degrees, speed)
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

    override fun requiresApiKey(): Boolean = false

    override fun getApiKeyType(): String? = null

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
                Log.d(TAG, "Charging flap status: ${if (isOpen) "OPEN (movement blocked)" else "CLOSED (movement allowed)"}")
                isOpen
            } else {
                Log.d(TAG, "No charging flap sensor available - assuming movement is allowed")
                false // Assume closed if sensor not available
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not check charging flap status: ${e.message}", e)
            false // Allow movement if check fails to avoid false blocking
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


