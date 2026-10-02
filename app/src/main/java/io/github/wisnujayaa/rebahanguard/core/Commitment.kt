package io.github.wisnujayaa.rebahanguard.core

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
 * The deliberately slow way out (when no partner can be reached). The user waits, then types a
 * long sentence that names exactly what they are trading away. Impulses fade within minutes; the
 * sentence makes the cost of the choice impossible to skip over.
 */
object EmergencyStop {
    const val PHRASE =
        "Saya sadar bahwa saya memilih rebahan sambil main HP daripada belajar. " +
            "Waktu yang saya buang malam ini tidak akan pernah kembali, " +
            "dan saya sendiri yang akan menanggung akibatnya besok."

    private val phrase = TypedPhrase(PHRASE)

    fun canStop(waitedMs: Long, typed: String, hasPartner: Boolean): Boolean =
        waitedMs >= EmergencyStopRules.waitMs(hasPartner) && phraseMatches(typed)

    /** Case, accents, spacing and punctuation don't matter; every word does, in order. */
    fun phraseMatches(typed: String): Boolean = phrase.matches(typed)

    /** How many leading words are already right — lets the UI show progress while typing. */
    fun correctWords(typed: String): Int = phrase.correctWords(typed)

    val wordCount: Int get() = phrase.wordCount
}

/** Short reminders shown on the lock screen, rotated so they don't become wallpaper. */
object LockMessages {
    val ALL = listOf(
        "Tugasmu tidak akan selesai sambil rebahan.",
        "Setiap menit di sini adalah menit yang tidak kamu pakai untuk belajar.",
        "Besok pagi kamu akan berharap malam ini kamu belajar.",
        "Kamu sendiri yang memasang penjaga ini, karena kamu tahu kamu bisa lebih baik.",
        "Duduk, buka catatanmu. Satu halaman dulu saja.",
        "Rebahan terasa sebentar. Penyesalannya terasa lebih lama.",
    )

    fun pick(index: Long): String = ALL[Math.floorMod(index, ALL.size.toLong()).toInt()]
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
