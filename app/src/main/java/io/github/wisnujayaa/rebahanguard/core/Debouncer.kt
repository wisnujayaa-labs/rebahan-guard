package io.github.wisnujayaa.rebahanguard.core

/**
 * Fires only after a condition has stayed true for [holdMs] without interruption.
 *
 * A single glance at the phone while it is sideways should not count as "lying in bed",
 * so every decision in the app waits for the signal to be stable first.
 */
class Debouncer(private val holdMs: Long) {
    private var trueSince: Long? = null

    /** Returns true once [condition] has been continuously true for at least [holdMs]. */
    fun update(condition: Boolean, nowMs: Long): Boolean {
        if (!condition) {
            trueSince = null
            return false
        }
        val since = trueSince ?: nowMs.also { trueSince = it }
        return nowMs - since >= holdMs
    }

    fun reset() {
        trueSince = null
    }
}
