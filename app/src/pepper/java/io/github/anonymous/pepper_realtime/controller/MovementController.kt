package io.github.anonymous.pepper_realtime.controller

import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.GoToBuilder
import com.aldebaran.qi.sdk.builder.TransformBuilder
import com.aldebaran.qi.sdk.`object`.actuation.OrientationPolicy
import com.aldebaran.qi.sdk.`object`.geometry.Quaternion
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin

class MovementController {
    
    interface MovementListener {
        fun onMovementStarted()
        fun onMovementFinished(success: Boolean, error: String?)
    }

    companion object {
        private const val TAG = "MovementController"
        private const val MOVEMENT_TIMEOUT_SECONDS = 15L
    }

    private var listener: MovementListener? = null
    private val timeoutExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var currentMovement: Future<Void>? = null
    private var currentTimeoutTask: ScheduledFuture<*>? = null

    fun setListener(listener: MovementListener?) {
        this.listener = listener
    }

    /**
     * Move Pepper in a specified direction
     * @param qiContext QiContext for accessing robot services
     * @param distanceForward distance forward (positive) or backward (negative) in meters
     * @param distanceSideways distance left (positive) or right (negative) in meters
     * @param speed optional speed in m/s (0.1-0.55), default 0.4
     */
    fun movePepper(qiContext: QiContext?, distanceForward: Double, distanceSideways: Double, speed: Double) {
        if (qiContext == null) {
            Log.e(TAG, "QiContext is null - cannot move Pepper")
            listener?.onMovementFinished(false, "Robot not ready")
            return
        }

        try {
            // Validate parameters
            val validSpeed = if (speed < 0.1 || speed > 0.55) 0.4 else speed

            // Get Actuation and Mapping services
            val actuation = qiContext.actuation
            val mapping = qiContext.mapping

            // Get robot frame
            val robotFrame = actuation.robotFrame()

            // Create transform based on direction
            val transform = TransformBuilder.create().from2DTranslation(distanceForward, distanceSideways)

            // Create target frame
            val targetFrame = mapping.makeFreeFrame()
            targetFrame.update(robotFrame, transform, 0L)

            // Create and execute GoTo action
            val goTo = GoToBuilder.with(qiContext)
                .withFrame(targetFrame.frame())
                .withMaxSpeed(validSpeed.toFloat())
                .withFinalOrientationPolicy(OrientationPolicy.ALIGN_X)
                .build()

            // Add listeners
            goTo.addOnStartedListener {
                Log.i(TAG, "Movement started: forward=${distanceForward}m, sideways=${distanceSideways}m")
                listener?.onMovementStarted()
            }

            // Execute movement
            currentMovement = goTo.async().run()

            // Set up timeout
            timeoutExecutor.schedule({
                currentMovement?.let { movement ->
                    if (!movement.isDone) {
                        Log.w(TAG, "Movement timeout after $MOVEMENT_TIMEOUT_SECONDS seconds - cancelling")
                        movement.requestCancellation()
                        listener?.onMovementFinished(false, "Movement timeout - the robot took too long to reach the destination, possibly due to obstacles")
                    }
                }
            }, MOVEMENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            currentMovement?.thenConsume { future ->
                goTo.removeAllOnStartedListeners()

                when {
                    future.isSuccess -> {
                        Log.i(TAG, "Movement finished successfully")
                        listener?.onMovementFinished(true, null)
                    }
                    future.isCancelled -> {
                        Log.w(TAG, "Movement was cancelled (likely due to timeout)")
                        // Don't call listener here - timeout handler already did
                    }
                    future.hasError() -> {
                        val errorMsg = future.error?.message ?: "Unknown movement error"
                        Log.e(TAG, "Movement failed: $errorMsg", future.error)
                        listener?.onMovementFinished(false, errorMsg)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating movement", e)
            listener?.onMovementFinished(false, e.message)
        }
    }

    /**
     * Turn Pepper by a specified angle
     * @param qiContext QiContext for accessing robot services
     * @param direction "left", "right"
     * @param degrees degrees to turn (15-180)
     * @param speed optional angular speed in rad/s (0.1-1.0), default 0.5
     */
    fun turnPepper(qiContext: QiContext?, direction: String, degrees: Double, speed: Double) {
        if (qiContext == null) {
            Log.e(TAG, "QiContext is null - cannot turn Pepper")
            listener?.onMovementFinished(false, "Robot not ready")
            return
        }

        try {
            // Validate parameters
            if (degrees < 15 || degrees > 180) {
                throw IllegalArgumentException("Degrees must be between 15 and 180")
            }
            val validSpeed = if (speed < 0.1 || speed > 1.0) 0.5 else speed

            // Get Actuation and Mapping services
            val actuation = qiContext.actuation
            val mapping = qiContext.mapping

            // Get robot frame
            val robotFrame = actuation.robotFrame()

            // Create rotation transform
            val transform = createRotationTransform(direction, degrees)

            // Create target frame
            val targetFrame = mapping.makeFreeFrame()
            targetFrame.update(robotFrame, transform, 0L)

            // Create and execute GoTo action
            val goTo = GoToBuilder.with(qiContext)
                .withFrame(targetFrame.frame())
                .withMaxSpeed(validSpeed.toFloat())
                .withFinalOrientationPolicy(OrientationPolicy.ALIGN_X)
                .build()

            // Add listeners
            goTo.addOnStartedListener {
                Log.i(TAG, "Turn started: $direction $degrees degrees")
                listener?.onMovementStarted()
            }

            // Execute turn
            currentMovement = goTo.async().run()

            // Set up timeout
            timeoutExecutor.schedule({
                currentMovement?.let { movement ->
                    if (!movement.isDone) {
                        Log.w(TAG, "Turn timeout after $MOVEMENT_TIMEOUT_SECONDS seconds - cancelling")
                        movement.requestCancellation()
                        listener?.onMovementFinished(false, "Turn timeout - the robot took too long to complete the turn, possibly due to obstacles")
                    }
                }
            }, MOVEMENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            currentMovement?.thenConsume { future ->
                goTo.removeAllOnStartedListeners()

                when {
                    future.isSuccess -> {
                        Log.i(TAG, "Turn finished successfully")
                        listener?.onMovementFinished(true, null)
                    }
                    future.isCancelled -> {
                        Log.w(TAG, "Turn was cancelled (likely due to timeout)")
                        // Don't call listener here - timeout handler already did
                    }
                    future.hasError() -> {
                        val errorMsg = future.error?.message ?: "Unknown turn error"
                        Log.e(TAG, "Turn failed: $errorMsg", future.error)
                        listener?.onMovementFinished(false, errorMsg)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating turn", e)
            listener?.onMovementFinished(false, e.message)
        }
    }

    private fun createRotationTransform(direction: String, degrees: Double): com.aldebaran.qi.sdk.`object`.geometry.Transform {
        val radians = Math.toRadians(degrees)

        return when (direction.lowercase()) {
            "left" -> {
                // Positive rotation around Z-axis (counter-clockwise)
                val halfAngle = radians / 2.0
                val quaternion = Quaternion(0.0, 0.0, sin(halfAngle), cos(halfAngle))
                TransformBuilder.create().fromRotation(quaternion)
            }
            "right" -> {
                // Negative rotation around Z-axis (clockwise)
                val halfAngle = -radians / 2.0
                val quaternion = Quaternion(0.0, 0.0, sin(halfAngle), cos(halfAngle))
                TransformBuilder.create().fromRotation(quaternion)
            }
            else -> throw IllegalArgumentException("Invalid turn direction: $direction. Use left or right.")
        }
    }

    /**
     * Navigate to a saved location using GoTo
     */
    fun navigateToLocation(qiContext: QiContext?, savedLocation: Any?, speed: Float) {
        currentMovement?.let { movement ->
            if (!movement.isDone) {
                Log.w(TAG, "Cannot start navigation - another movement is in progress")
                listener?.onMovementFinished(false, "Another movement is already in progress")
                return
            }
        }

        if (qiContext == null || savedLocation == null) {
            Log.e(TAG, "QiContext or savedLocation is null")
            listener?.onMovementFinished(false, "Invalid parameters")
            return
        }

        // Validate speed
        if (speed < 0.1f || speed > 0.55f) {
            Log.e(TAG, "Invalid navigation speed: $speed. Must be between 0.1 and 0.55 m/s")
            listener?.onMovementFinished(false, "Invalid speed: $speed")
            return
        }

        Log.d(TAG, "Starting navigation to saved location with speed: $speed m/s")

        try {
            // Extract location data using reflection
            val translationField = savedLocation.javaClass.getDeclaredField("translation")
            translationField.isAccessible = true
            val translation = translationField.get(savedLocation) as? DoubleArray

            if (translation == null || translation.size < 2) {
                Log.e(TAG, "Invalid translation data in saved location")
                listener?.onMovementFinished(false, "Invalid location data - translation missing")
                return
            }

            // Create target transform from saved location data
            val targetTransform = TransformBuilder.create().from2DTranslation(translation[0], translation[1])

            // Get map frame as reference
            val mapFrame = try {
                qiContext.mapping.mapFrame()
            } catch (e: Exception) {
                Log.e(TAG, "Map frame not available for navigation: ${e.message}")
                listener?.onMovementFinished(false, "No map available for navigation. Please create a map first.")
                return
            }

            // Create a FreeFrame at the target location
            val targetFrame = qiContext.mapping.makeFreeFrame()
            targetFrame.update(mapFrame, targetTransform, 0L)

            // Create GoTo action
            val goTo = GoToBuilder.with(qiContext)
                .withFrame(targetFrame.frame())
                .withMaxSpeed(speed)
                .withFinalOrientationPolicy(OrientationPolicy.FREE_ORIENTATION)
                .build()

            // Set up timeout
            currentTimeoutTask = timeoutExecutor.schedule({
                currentMovement?.let { movement ->
                    if (!movement.isDone) {
                        Log.w(TAG, "Navigation timeout after $MOVEMENT_TIMEOUT_SECONDS seconds")
                        movement.requestCancellation()
                        listener?.onMovementFinished(false, "timeout")
                    }
                }
            }, MOVEMENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            // Execute navigation
            currentMovement = goTo.async().run()
            listener?.onMovementStarted()

            // Handle completion
            currentMovement?.thenConsume { future ->
                // Cancel timeout task
                currentTimeoutTask?.let { task ->
                    if (!task.isDone) {
                        task.cancel(false)
                    }
                }

                when {
                    future.isSuccess -> {
                        Log.d(TAG, "Navigation completed successfully")
                        listener?.onMovementFinished(true, null)
                    }
                    future.isCancelled -> {
                        Log.w(TAG, "Navigation was cancelled")
                        listener?.onMovementFinished(false, "cancelled")
                    }
                    future.hasError() -> {
                        val error = future.error?.message ?: "Unknown error"
                        Log.e(TAG, "Navigation failed: $error")
                        listener?.onMovementFinished(false, error)
                    }
                }

                currentMovement = null
                currentTimeoutTask = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting navigation", e)
            listener?.onMovementFinished(false, "Navigation setup failed: ${e.message}")
        }
    }

    /**
     * Clean up resources when MovementController is no longer needed
     */
    @Suppress("unused")
    fun shutdown() {
        if (!timeoutExecutor.isShutdown) {
            timeoutExecutor.shutdown()
            Log.d(TAG, "MovementController executor shutdown")
        }
    }
}

