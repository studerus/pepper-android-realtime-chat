package ch.fhnw.pepper_realtime.manager

import kotlinx.coroutines.delay

enum class ClockRecoveryStage { WAITING_FOR_NETWORK, SYNCHRONIZING }

/** Bounded recovery of a reset clock; Android remains responsible for setting the time. */
internal class ClockRecovery(private val environment: Environment) {
    interface Environment {
        fun wallTimeMs(): Long
        fun elapsedTimeMs(): Long
        fun automaticTimeEnabled(): Boolean
        fun isConnected(): Boolean
        fun requestNetworkTime()
    }

    enum class Result { ALREADY_VALID, RECOVERED, DISABLED, TIMED_OUT, EXHAUSTED, REJECTED }

    suspend fun recover(onProgress: (ClockRecoveryStage) -> Unit = {}): Result {
        if (environment.wallTimeMs() >= MIN_VALID_TIME_MS) return Result.ALREADY_VALID
        val startedAt = environment.elapsedTimeMs()
        var attempts = 0
        var lastAttemptAt: Long? = null
        var lastStage: ClockRecoveryStage? = null
        while (true) {
            if (environment.wallTimeMs() >= MIN_VALID_TIME_MS) return Result.RECOVERED
            if (!environment.automaticTimeEnabled()) return Result.DISABLED
            val now = environment.elapsedTimeMs()
            if (now - startedAt >= MAX_DURATION_MS) return Result.TIMED_OUT
            val retryDue = lastAttemptAt?.let { now - it >= RETRY_INTERVAL_MS } ?: true
            // Give the final request time to complete before reporting exhaustion.
            if (attempts >= MAX_ATTEMPTS && retryDue) return Result.EXHAUSTED
            val connected = environment.isConnected()
            val stage = if (connected) ClockRecoveryStage.SYNCHRONIZING else ClockRecoveryStage.WAITING_FOR_NETWORK
            if (stage != lastStage) {
                onProgress(stage)
                lastStage = stage
            }
            if (attempts < MAX_ATTEMPTS && retryDue && connected) {
                try {
                    environment.requestNetworkTime()
                } catch (_: SecurityException) {
                    return Result.REJECTED
                }
                attempts++
                lastAttemptAt = now
            }
            delay(CHECK_INTERVAL_MS)
        }
    }

    companion object {
        const val MIN_VALID_TIME_MS = 1704067200000L // 2024-01-01 UTC; catches Pepper's 2010 reset.
        const val CHECK_INTERVAL_MS = 5000L
        const val RETRY_INTERVAL_MS = 30000L
        const val MAX_DURATION_MS = 600000L
        const val MAX_ATTEMPTS = 8

        fun isSupported(flavor: String, sdk: Int): Boolean = flavor == "pepper" && sdk == 23
    }
}
