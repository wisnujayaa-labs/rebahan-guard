package io.github.wisnujayaa.rebahanguard.core

/**
 * A nightly window such as 22:00–05:00 in which the guard is enforced. Outside it the guard keeps
 * running (Android won't let it restart the camera from the background) but stays quiet.
 * Minutes are counted from local midnight; windows may wrap past midnight.
 */
data class Schedule(val enabled: Boolean, val startMinute: Int, val endMinute: Int) {
    fun isWithin(minuteOfDay: Int): Boolean {
        if (!enabled) return true // no schedule = always on
        val m = Math.floorMod(minuteOfDay, MINUTES_PER_DAY)
        return when {
            startMinute == endMinute -> true // a 24-hour window
            startMinute < endMinute -> m in startMinute until endMinute
            else -> m >= startMinute || m < endMinute // wraps past midnight
        }
    }

    fun sanitized() = copy(
        startMinute = Math.floorMod(startMinute, MINUTES_PER_DAY),
        endMinute = Math.floorMod(endMinute, MINUTES_PER_DAY),
    )

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
        val DEFAULT = Schedule(enabled = false, startMinute = 22 * 60, endMinute = 5 * 60)

        fun format(minute: Int): String {
            val m = Math.floorMod(minute, MINUTES_PER_DAY)
            return "%02d:%02d".format(m / 60, m % 60)
        }
    }
}

/** One night's record. A "night" runs from noon to noon, named after the evening's date. */
data class NightRecord(
    val night: Int, // days since the epoch, of the evening
    val guarded: Boolean = false,
    val caught: Int = 0,
    val lockedMs: Long = 0,
    val emergencies: Int = 0,
)

/**
 * Night-by-night statistics. Kept as plain data so it is easy to test and to store; the last
 * [KEEP_NIGHTS] nights are kept.
 */
object NightStats {
    const val KEEP_NIGHTS = 60
    private const val DAY_MS = 86_400_000L
    private const val NOON_MS = 12 * 3_600_000L

    /** The night a moment belongs to, given the local time-zone offset. */
    fun nightOf(epochMs: Long, zoneOffsetMs: Int): Int = Math.floorDiv(epochMs + zoneOffsetMs - NOON_MS, DAY_MS).toInt()

    fun update(records: List<NightRecord>, night: Int, change: (NightRecord) -> NightRecord): List<NightRecord> {
        val current = records.firstOrNull { it.night == night } ?: NightRecord(night)
        return (records.filter { it.night != night } + change(current))
            .sortedBy { it.night }
            .takeLast(KEEP_NIGHTS)
    }

    /**
     * Consecutive guarded nights without being caught, counting back from [tonight]. Tonight only
     * counts once it's over, so the streak doesn't break in the middle of a good night.
     */
    fun streak(records: List<NightRecord>, tonight: Int): Int {
        val byNight = records.associateBy { it.night }
        var n = tonight
        val t = byNight[tonight]
        if (t == null || t.caught == 0) n-- // tonight is still in progress (or unused)
        var count = 0
        while (true) {
            val r = byNight[n] ?: break
            if (!r.guarded || r.caught > 0) break
            count++
            n--
        }
        return count
    }

    /** Plain-text weekly summary the user can send to their partner. */
    fun summary(records: List<NightRecord>, tonight: Int, partnerName: String?): String {
        val week = records.filter { it.night in (tonight - 6)..tonight && it.guarded }
        val caught = week.sumOf { it.caught }
        val clean = week.count { it.caught == 0 }
        val minutes = week.sumOf { it.lockedMs } / 60_000
        val emergencies = week.sumOf { it.emergencies }
        val greeting = if (partnerName.isNullOrBlank()) "" else "Halo $partnerName, "
        return buildString {
            append("${greeting}ini laporan Rebahan Guard-ku 7 malam terakhir:\n")
            append("• Malam dijaga: ${week.size}\n")
            append("• Malam bersih (tidak ketahuan rebahan): $clean\n")
            append("• Ketahuan rebahan: $caught kali, total terkunci $minutes menit\n")
            if (emergencies > 0) append("• Tombol Darurat dipakai: $emergencies kali\n")
            append("• Streak sekarang: ${streak(records, tonight)} malam")
        }
    }
}

/**
 * The long way out. It costs time and makes the user spell out what they are choosing — the point
 * is that the impulse fades and the cost of the choice becomes visible before it is made.
 */
object EmergencyStopRules {
    /** Without a partner: a short wait, the sentence does the work. */
    const val WAIT_ALONE_MS = 5 * 60_000L

    /** With a partner set up, asking them is the normal way; this is for when they're unreachable. */
    const val WAIT_WITH_PARTNER_MS = 30 * 60_000L

    fun waitMs(hasPartner: Boolean): Long = if (hasPartner) WAIT_WITH_PARTNER_MS else WAIT_ALONE_MS
}
