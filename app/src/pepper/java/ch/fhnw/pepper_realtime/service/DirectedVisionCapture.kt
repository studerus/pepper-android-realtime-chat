package ch.fhnw.pepper_realtime.service

import android.os.SystemClock
import android.util.Log
import ch.fhnw.pepper_realtime.tools.vision.VisionLookTarget
import ch.fhnw.pepper_realtime.tools.vision.VisionCaptureReadiness
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.LookAtBuilder
import com.aldebaran.qi.sdk.builder.TransformBuilder
import com.aldebaran.qi.sdk.`object`.actuation.LookAt
import com.aldebaran.qi.sdk.`object`.actuation.LookAtMovementPolicy
import com.aldebaran.qi.sdk.`object`.actuation.Frame
import com.aldebaran.qi.sdk.`object`.geometry.Vector3
import com.aldebaran.qi.sdk.`object`.camera.TakePicture
import com.aldebaran.qi.sdk.`object`.image.TimestampedImageHandle
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class VisionAlignmentException(message: String) : IllegalStateException(message)

internal object DirectedVisionCapture {
    private val captureMutex = Mutex()

    fun targetFrame(context: QiContext, x: Double, y: Double, z: Double): Frame {
        val target = context.mapping.makeFreeFrame()
        target.update(context.actuation.robotFrame(), TransformBuilder.create().fromTranslation(Vector3(x, y, z)), 0L)
        return target.frame()
    }

    suspend fun capture(context: QiContext, camera: Future<TakePicture>, target: VisionLookTarget?): TimestampedImageHandle = captureMutex.withLock {
        var gaze: Future<Void>? = null
        try {
            val action = withTimeout(5000) { camera.awaitResult() }
            if (target != null) {
                val readiness = VisionCaptureReadiness()
                val ready = withTimeoutOrNull(10000) {
                    val frame = targetFrame(context, target.x, target.y, target.z)
                    val look: LookAt = LookAtBuilder.with(context).withFrame(frame).buildAsync().awaitResult()
                    look.policy = LookAtMovementPolicy.HEAD_ONLY
                    val started = CompletableDeferred<Unit>()
                    val listener = LookAt.OnStartedListener { started.complete(Unit) }
                    look.addOnStartedListener(listener)
                    try {
                        gaze = look.async().run()
                        gaze!!.thenConsume { completed ->
                            if (!started.isCompleted) started.completeExceptionally(
                                VisionAlignmentException("Head movement ended before starting: " +
                                    if (completed.hasError()) completed.error?.message else "cancelled or stopped"))
                        }
                        started.await()
                        Log.i("DirectedVisionCapture", "Head movement started for $target t_ms=${SystemClock.elapsedRealtime()}")
                        waitForCaptureReady(context, frame, gaze!!, readiness)
                    } finally {
                        look.async().removeOnStartedListener(listener)
                    }
                    true
                }
                if (ready != true) throw VisionAlignmentException(
                    "Head alignment timed out; no photo was taken. ${bearingDescription(readiness)}. " +
                        "The head did not settle or its position could not be verified. Ask for another viewing direction.")
            }
            check(gaze?.isDone != true) { "Head movement interrupted before capture" }
            Log.i("DirectedVisionCapture", "Capture started t_ms=${SystemClock.elapsedRealtime()}")
            val image = withTimeout(5000) { action.async().run().awaitResult() }
            check(gaze?.isDone != true) { "Head movement interrupted during capture; image discarded" }
            Log.i("DirectedVisionCapture", "Image received t_ms=${SystemClock.elapsedRealtime()}")
            image
        } finally {
            gaze?.requestCancellation()
        }
    }

    private suspend fun waitForCaptureReady(context: QiContext, frame: Frame, gaze: Future<Void>, readiness: VisionCaptureReadiness) {
        val gazeFrame = context.actuation.gazeFrame()
        var lastLogAt = 0L
        while (true) {
            check(!gaze.isDone) { "Head movement ended before capture; no photo was taken" }
            val translation = frame.async().computeTransform(gazeFrame).awaitResult().transform.translation
            val now = SystemClock.elapsedRealtime()
            val decision = readiness.observe(now, translation.x, translation.y, translation.z)
            if (now - lastLogAt >= 300 || decision != VisionCaptureReadiness.Decision.WAIT) {
                Log.i("DirectedVisionCapture", "Gaze check: ${bearingDescription(readiness)} decision=$decision t_ms=$now")
                lastLogAt = now
            }
            when (decision) {
                VisionCaptureReadiness.Decision.CAPTURE -> return
                VisionCaptureReadiness.Decision.OUTSIDE_VIEW -> throw VisionAlignmentException(
                    "The head has stopped, but the requested point is outside the conservative camera viewing region. " +
                        "${bearingDescription(readiness)}. No photo was taken; this is not a camera failure. " +
                        "Explain the head-only viewing limit. For a nearby floor point, suggest a point farther away; " +
                        "do not claim to have seen the requested point or move the robot automatically.")
                VisionCaptureReadiness.Decision.WAIT -> delay(100)
            }
        }
    }

    private fun bearingDescription(readiness: VisionCaptureReadiness): String {
        val bearing = readiness.lastBearing ?: return "Target bearing unavailable"
        return String.format(Locale.US, "Target offset: horizontal=%.1f deg, vertical=%.1f deg, in_front=%s",
            bearing.horizontalDegrees, bearing.verticalDegrees, bearing.inFront)
    }

    private suspend fun <T> Future<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { requestCancellation() }
        thenConsume { completed ->
            if (continuation.isActive) {
                try { continuation.resume(completed.get()) }
                catch (e: Exception) { continuation.resumeWithException(e) }
            }
        }
    }
}
