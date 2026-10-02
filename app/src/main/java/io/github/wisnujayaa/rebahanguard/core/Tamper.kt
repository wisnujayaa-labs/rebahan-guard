package io.github.wisnujayaa.rebahanguard.core

import java.text.Normalizer

/**
 * What happens when the user tries to silence the alarm (turns the alarm volume down while the
 * lock is up). Each attempt the same night costs more. The steps stay proportionate on purpose:
 * a punishment that feels unfair pushes people to uninstall, and then nothing is guarded at all.
 */
object TamperPenalty {
    /** Extra time the lock stays up after the user has already sat up. */
    fun extraLockMs(attemptsTonight: Int): Long = when {
        attemptsTonight <= 0 -> 0
        attemptsTonight == 1 -> 5 * MINUTE_MS
        attemptsTonight == 2 -> 10 * MINUTE_MS
        else -> 15 * MINUTE_MS
    }

    /** From the second attempt the vibration starts at full strength. */
    fun startsAtMaxVibration(attemptsTonight: Int): Boolean = attemptsTonight >= 2

    /** From the third attempt, leaving the lock also means typing the commitment sentence. */
    fun requiresPhrase(attemptsTonight: Int): Boolean = attemptsTonight >= 3

    /** A volume reading below what the guard set counts as an attempt. */
    fun isAttempt(currentVolume: Int, enforcedVolume: Int): Boolean =
        enforcedVolume > 0 && currentVolume in 0 until enforcedVolume

    /**
     * Time left on the penalty. Measured on the monotonic clock; if that clock went backwards
     * (it can't, short of a restart) the penalty is kept in full rather than cancelled.
     */
    fun remainingMs(untilElapsedMs: Long, nowElapsedMs: Long, fullMs: Long): Long =
        (untilElapsedMs - nowElapsedMs).coerceIn(0, fullMs.coerceAtLeast(0))

    const val PHRASE =
        "Aku memilih bangun sekarang. Mengecilkan suara tidak menghapus kenyataan bahwa " +
            "tanggung jawabku masih menunggu, dan aku tidak mau menjadi orang yang lari darinya."

    const val MINUTE_MS = 60_000L
}

/**
 * Vibration that gets stronger every [STEP_MS] while the lock is up: longer pulses, shorter
 * pauses, more force. Muffling the speaker under a pillow doesn't help against this.
 */
object VibrationLadder {
    const val STEP_MS = 10_000L
    const val LEVELS = 4

    /** One repeating pattern: (timings in ms, amplitudes 1..255), same length, starting with a pause. */
    data class Pattern(val timings: LongArray, val amplitudes: IntArray) {
        override fun equals(other: Any?) =
            other is Pattern && timings.contentEquals(other.timings) && amplitudes.contentEquals(other.amplitudes)

        override fun hashCode() = 31 * timings.contentHashCode() + amplitudes.contentHashCode()
    }

    fun level(elapsedMs: Long, startAtMax: Boolean): Int =
        if (startAtMax) LEVELS - 1 else (elapsedMs.coerceAtLeast(0) / STEP_MS).coerceAtMost(LEVELS - 1L).toInt()

    fun pattern(level: Int): Pattern {
        val l = level.coerceIn(0, LEVELS - 1)
        val on = longArrayOf(300, 500, 700, 900)[l]
        val off = longArrayOf(700, 500, 300, 150)[l]
        val amp = intArrayOf(110, 170, 220, 255)[l]
        return Pattern(longArrayOf(0, on, off), intArrayOf(0, amp, 0))
    }
}

/**
 * A sentence the user has to type word by word. Case, accents, spacing and punctuation don't
 * matter; every word does, in order.
 */
class TypedPhrase(val text: String) {
    private val target = normalize(text).split(" ").filter { it.isNotEmpty() }

    val wordCount: Int get() = target.size

    fun matches(typed: String): Boolean = target.isNotEmpty() && normalize(typed).split(" ").filter { it.isNotEmpty() } == target

    /** How many leading words are already right — lets the UI show progress while typing. */
    fun correctWords(typed: String): Int {
        val got = normalize(typed).split(" ").filter { it.isNotEmpty() }
        return target.zip(got).takeWhile { (a, b) -> a == b }.count()
    }

    companion object {
        fun normalize(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFKD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase()
                .replace(Regex("[^a-z0-9 ]"), " ")
                .trim()
                .replace(Regex("\\s+"), " ")
    }
}
