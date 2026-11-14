package ch.fhnw.pepper_realtime.tools

import com.aldebaran.qi.Future

/** Start and register atomically with respect to cancellation of the owning call. */
fun <T> ToolContext.startRobotAction(start: () -> Future<T>): Future<T> = guard {
    val future = start()
    val registration = onCancel { future.requestCancellation() }
    future.thenConsume { registration.close() }
    future
}
