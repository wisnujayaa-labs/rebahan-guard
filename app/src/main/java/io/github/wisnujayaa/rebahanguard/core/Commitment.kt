package io.github.wisnujayaa.rebahanguard.core

import java.text.Normalizer

/**
 * A promise the user makes to themselves: "keep the guard on until …".
 *
 * The end is tracked twice, because the wall clock can be changed by the user:
 *  - [startElapsedMs] + [durationMs] on the monotonic clock (SystemClock.elapsedRealtime), which
 *    nobody can move, valid as long as the phone hasn't restarted ([bootCount] unchanged);
 *  - [endWallMs] on the wall clock, used only after a restart (the monotonic clock resets then).
 * Moving the clock forward therefore can't end a commitment early.
 */
data class Commitment(
    val startWallMs: Long,
    val startElapsedMs: Long,
    val bootCount: Int,
    val durationMs: Long,
) {
    val endWallMs: Long get() = startWallMs + durationMs

    fun isActive(nowWallMs: Long, nowElapsedMs: Long, nowBootCount: Int): Boolean =
        remainingMs(nowWallMs, nowElapsedMs, nowBootCount) > 0

    fun remainingMs(nowWallMs: Long, nowElapsedMs: Long, nowBootCount: Int): Long {
        val sameBoot = nowBootCount == bootCount && nowElapsedMs >= startElapsedMs
        val left = if (sameBoot) {
            durationMs - (nowElapsedMs - startElapsedMs)
        } else {
            // After a restart. If the wall clock is now *before* the start, it was moved back:
            // keep the full duration rather than trusting it.
            if (nowWallMs < startWallMs) durationMs else endWallMs - nowWallMs
        }
        return left.coerceIn(0, durationMs)
    }

    companion object {
        const val MIN_HOURS = 1
        const val MAX_HOURS = 12
        const val HOUR_MS = 3_600_000L

        fun start(nowWallMs: Long, nowElapsedMs: Long, bootCount: Int, hours: Int): Commitment =
            Commitment(
                startWallMs = nowWallMs,
                startElapsedMs = nowElapsedMs,
                bootCount = bootCount,
                durationMs = hours.coerceIn(MIN_HOURS, MAX_HOURS) * HOUR_MS,
            )

        /** Rejects stored values that a real commitment could never have (corrupted settings). */
        fun sanitize(c: Commitment?): Commitment? = c?.takeIf {
            it.durationMs in (MIN_HOURS * HOUR_MS)..(MAX_HOURS * HOUR_MS) &&
                it.startWallMs > 0 && it.startElapsedMs >= 0
        }
    }
}

/**
 * The deliberately slow way out of a commitment. Impulses fade within minutes; a forced wait
 * plus typing a sentence gives the reflective part of the brain time to catch up.
 */
object EmergencyStop {
    /** The countdown only runs while the app stays open and in front. */
    const val WAIT_MS = 120_000L

    const val PHRASE = "aku memilih rebahan daripada tidur"

    fun canStop(waitedMs: Long, typed: String): Boolean = waitedMs >= WAIT_MS && phraseMatches(typed)

    /** Case, accents, spacing and trailing punctuation don't matter; the words do. */
    fun phraseMatches(typed: String): Boolean = normalize(typed) == normalize(PHRASE)

    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
}

/**
 * Counts times the guard went down while a commitment was active without going through the
 * emergency stop (force stop, restart, battery saver). Shown to the user — accountability
 * instead of an impossible lock.
 */
object InterruptionLedger {
    /**
     * Called when the guard starts. [sessionWasOpen] is true if the previous session never
     * closed cleanly. Returns the new interruption count.
     */
    fun onGuardStart(previousCount: Int, sessionWasOpen: Boolean, commitmentActive: Boolean): Int =
        if (sessionWasOpen && commitmentActive) (previousCount + 1).coerceAtMost(9_999) else previousCount.coerceAtLeast(0)
}
