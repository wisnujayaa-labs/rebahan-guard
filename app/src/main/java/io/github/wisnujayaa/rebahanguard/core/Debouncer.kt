package io.github.wisnujayaa.rebahanguard.core

/**
 * Fires only after a condition has stayed true for [holdMs] without interruption.
 *
 * A single glance at the phone while it is sideways should not count as "lying in bed",
 * so every decision in the app waits for the signal to be stable first.
 */
class Debouncer(private val holdMs: Long) {
    init {
        require(holdMs >= 0) { "holdMs must be >= 0, was $holdMs" }
    }

    private var trueSince: Long? = null

    /** Returns true once [condition] has been continuously true for at least [holdMs]. */
    fun update(condition: Boolean, nowMs: Long): Boolean = update(condition, nowMs, holdMs)

    /** Same, with a hold time chosen per call (e.g. shorter right after being caught). */
    fun update(condition: Boolean, nowMs: Long, holdMs: Long): Boolean {
        require(holdMs >= 0) { "holdMs must be >= 0, was $holdMs" }
        if (!condition) {
            trueSince = null
            return false
        }
        val since = trueSince
        if (since == null || nowMs < since) {
            // First true sample, or the clock went backwards: start counting from now.
            trueSince = nowMs
            return holdMs == 0L
        }
        return nowMs - since >= holdMs
    }

    fun reset() {
        trueSince = null
    }
}
