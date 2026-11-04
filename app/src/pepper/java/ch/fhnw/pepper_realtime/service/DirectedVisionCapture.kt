package ch.fhnw.pepper_realtime.service

import android.os.SystemClock
import android.util.Log
import ch.fhnw.pepper_realtime.tools.vision.VisionLookTarget
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
                withTimeout(10000) {
                    val frame = targetFrame(context, target.x, target.y, target.z)
                    val look: LookAt = LookAtBuilder.with(context).withFrame(frame).buildAsync().awaitResult()
                    look.policy = LookAtMovementPolicy.HEAD_ONLY
                    gaze = look.async().run()
                    val gazeFrame = context.actuation.gazeFrame()
                    var alignedSince: Long? = null
                    while (true) {
                        check(gaze?.isDone != true) { "Head movement ended before capture" }
                        val translation = frame.async().computeTransform(gazeFrame).awaitResult().transform.translation
                        val now = SystemClock.elapsedRealtime()
                        if (VisionLookTarget.isAligned(translation.x, translation.y, translation.z)) {
                            val since = alignedSince ?: now.also { alignedSince = it }
                            if (now - since >= 300) break
                        } else alignedSince = null
                        delay(100)
                    }
                    Log.i("DirectedVisionCapture", "Gaze aligned with $target t_ms=${SystemClock.elapsedRealtime()}")
                }
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
