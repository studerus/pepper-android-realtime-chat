package ch.fhnw.pepper_realtime.controller

/** Counts uninterrupted listening time; starting a new chat or leaving the screen gets a new timer. */
internal class ConversationIdleTimer {
    private var since: Long? = null

    fun tick(now: Long, canListen: Boolean, lastInput: Long, evaluate: (Long) -> Boolean) {
        if (!canListen) {
            since = null
            return
        }
        val start = maxOf(since ?: now, lastInput)
        since = if (evaluate(now - start)) now else start
    }
}
