package ch.fhnw.pepper_realtime.controller

import android.util.Log
import ch.fhnw.pepper_realtime.tools.OperationScope
import java.io.Closeable
import ch.fhnw.pepper_realtime.robot.MotionValidation
import ch.fhnw.pepper_realtime.robot.RobotSafetyGuard
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.GoToBuilder
import com.aldebaran.qi.sdk.builder.TransformBuilder
import com.aldebaran.qi.sdk.`object`.actuation.OrientationPolicy
import com.aldebaran.qi.sdk.`object`.actuation.GoTo
import com.aldebaran.qi.sdk.`object`.geometry.Quaternion
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin

class MovementController(
    private val timeoutExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
) {
    
    interface MovementListener {
        fun onMovementStarted()
        fun onMovementFinished(success: Boolean, error: String?)
    }

    companion object {
        private const val TAG = "MovementController"
        internal const val MOVEMENT_CANCELLED = "Movement cancelled"
        private const val MOVEMENT_TIMEOUT_SECONDS = 15L
    }

    private var listener: MovementListener? = null
    private val movementLock = Any()
    private class Movement(val listener: MovementListener?, val owner: OperationScope?) {
        var future: Future<Void>? = null
        var timeout: ScheduledFuture<*>? = null
        var action: GoTo? = null
        var finished = false
        var cancellation: Closeable? = null
    }
    private var currentMovement: Movement? = null
    private var closed = false

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
    fun movePepper(qiContext: QiContext?, distanceForward: Double, distanceSideways: Double, speed: Double, owner: OperationScope? = null, callbacks: MovementListener? = listener) {
        if (qiContext == null) {
            Log.e(TAG, "QiContext is null - cannot move Pepper")
            callbacks?.onMovementFinished(false, "Robot not ready")
            return
        }

        try {
            MotionValidation.move(distanceForward, distanceSideways, speed)
            if (!checkMovementSafety(qiContext, callbacks)) return

            runMovement(owner, callbacks) {
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
                GoToBuilder.with(qiContext)
                    .withFrame(targetFrame.frame())
                    .withMaxSpeed(speed.toFloat())
                    .withFinalOrientationPolicy(OrientationPolicy.ALIGN_X)
                    .build()

            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating movement", e)
            callbacks?.onMovementFinished(false, e.message)
        }
    }

    /**
     * Turn Pepper by a specified angle
     * @param qiContext QiContext for accessing robot services
     * @param direction "left", "right"
     * @param degrees degrees to turn (15-180)
     * @param speed optional GoTo maximum speed in m/s (0.1-0.55), default 0.5
     */
    fun turnPepper(qiContext: QiContext?, direction: String, degrees: Double, speed: Double, owner: OperationScope? = null, callbacks: MovementListener? = listener) {
        if (qiContext == null) {
            Log.e(TAG, "QiContext is null - cannot turn Pepper")
            callbacks?.onMovementFinished(false, "Robot not ready")
            return
        }

        try {
            MotionValidation.turn(direction, degrees, speed)
            if (!checkMovementSafety(qiContext, callbacks)) return

            runMovement(owner, callbacks) {
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
                GoToBuilder.with(qiContext)
                    .withFrame(targetFrame.frame())
                    .withMaxSpeed(speed.toFloat())
                    .withFinalOrientationPolicy(OrientationPolicy.ALIGN_X)
                    .build()

            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating turn", e)
            callbacks?.onMovementFinished(false, e.message)
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
    fun navigateToLocation(qiContext: QiContext?, savedLocation: Any?, speed: Float, owner: OperationScope? = null, callbacks: MovementListener? = listener) {
        if (qiContext == null || savedLocation == null) {
            Log.e(TAG, "QiContext or savedLocation is null")
            callbacks?.onMovementFinished(false, "Invalid parameters")
            return
        }

        // Validate speed
        if (!speed.isFinite() || speed !in 0.1f..0.55f) {
            Log.e(TAG, "Invalid navigation speed: $speed. Must be between 0.1 and 0.55 m/s")
            callbacks?.onMovementFinished(false, "Invalid speed: $speed")
            return
        }

        Log.d(TAG, "Starting navigation to saved location with speed: $speed m/s")

        try {
            if (!checkMovementSafety(qiContext, callbacks)) return

            // Extract location data using reflection
            val translationField = savedLocation.javaClass.getDeclaredField("translation")
            translationField.isAccessible = true
            val translation = translationField.get(savedLocation) as? DoubleArray

            if (translation == null || translation.size < 2 || translation.any { !it.isFinite() }) {
                Log.e(TAG, "Invalid translation data in saved location")
                callbacks?.onMovementFinished(false, "Invalid location data - translation missing or not finite")
                return
            }

            runMovement(owner, callbacks) {
                // Create target transform from saved location data
                val targetTransform = TransformBuilder.create().from2DTranslation(translation[0], translation[1])

                // Get map frame as reference
                val mapFrame = try {
                    qiContext.mapping.mapFrame()
                } catch (e: Exception) {
                    Log.e(TAG, "Map frame not available for navigation: ${e.message}")
                    throw IllegalStateException("No map available for navigation. Please create a map first.", e)
            }

            // Create a FreeFrame at the target location
            val targetFrame = qiContext.mapping.makeFreeFrame()
            targetFrame.update(mapFrame, targetTransform, 0L)

            // Create GoTo action
            GoToBuilder.with(qiContext)
                .withFrame(targetFrame.frame())
                .withMaxSpeed(speed)
                .withFinalOrientationPolicy(OrientationPolicy.FREE_ORIENTATION)
                .build()

            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting navigation", e)
            callbacks?.onMovementFinished(false, "Navigation setup failed: ${e.message}")
        }
    }

    private fun checkMovementSafety(qiContext: QiContext, callbacks: MovementListener?): Boolean {
        val safety = RobotSafetyGuard.evaluateMovementSafety(qiContext)
        if (safety.isOk()) return true
        callbacks?.onMovementFinished(false, safety.message)
        return false
    }

    /** Each completion/timeout holds its own action; an old callback cannot affect a successor. */
    internal fun runMovement(owner: OperationScope? = null, callbacks: MovementListener? = listener, build: () -> GoTo) {
        val movement = Movement(callbacks, owner)
        synchronized(movementLock) {
            if (closed || currentMovement != null) {
                movement.listener?.onMovementFinished(false, if (closed) "Movement controller stopped" else "Another movement is already in progress")
                return
            }
            currentMovement = movement
        }
        val registration = owner?.onCancel { finish(movement, false, MOVEMENT_CANCELLED, cancel = true) }
        synchronized(movementLock) {
            if (movement.finished) { registration?.close(); return }
            movement.cancellation = registration
        }
        try {
            val action = build()
            synchronized(movementLock) {
                if (movement.finished) return // Cancellation while building: never run the action.
                movement.action = action
                action.addOnStartedListener {
                    synchronized(movementLock) {
                        if (!movement.finished) movement.listener?.onMovementStarted()
                    }
                }
                val future = action.async().run()
                movement.future = future
                movement.timeout = timeoutExecutor.schedule({
                    finish(movement, false, "Movement timeout", cancel = true)
                }, MOVEMENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                future.thenConsume { result ->
                    when {
                        result.isCancelled -> finish(movement, false, MOVEMENT_CANCELLED)
                        result.hasError() -> finish(movement, false, result.error?.message ?: "Movement failed")
                        else -> finish(movement, true, null)
                    }
                }
            }
        } catch (error: Exception) {
            finish(movement, false, error.message ?: "Movement setup failed", cancel = true)
        }
    }

    private fun finish(movement: Movement, success: Boolean, error: String?, cancel: Boolean = false) {
        synchronized(movementLock) {
            if (movement.finished) return
            movement.finished = true
            movement.timeout?.cancel(false)
            movement.timeout = null
            movement.cancellation?.close()
            movement.cancellation = null
            if (currentMovement === movement) currentMovement = null
        }
        try {
            if (cancel) movement.future?.requestCancellation()
        } finally {
            try { movement.action?.removeAllOnStartedListeners() } catch (ignored: Exception) { }
            movement.listener?.onMovementFinished(success, error)
        }
    }

    fun cancelMovement(owner: OperationScope) {
        val movement = synchronized(movementLock) { currentMovement?.takeIf { it.owner === owner } } ?: return
        finish(movement, false, MOVEMENT_CANCELLED, cancel = true)
    }

    fun cancelCurrentMovement() {
        val movement = synchronized(movementLock) { currentMovement } ?: return
        finish(movement, false, MOVEMENT_CANCELLED, cancel = true)
    }

    fun shutdown() {
        synchronized(movementLock) { closed = true }
        cancelCurrentMovement()
        timeoutExecutor.shutdownNow()
    }
}
