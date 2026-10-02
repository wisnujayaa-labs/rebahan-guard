package io.github.wisnujayaa.rebahanguard.core

/**
 * Focus mode: distracting apps are held back while there is work to do. Not a hard ban: a short,
 * deliberate pause ("minta 5 menit") is allowed twice a day. A study of the "one sec" app (PNAS,
 * 2023) found that a brief pause before opening an app cut attempts to open it by 57% over six
 * weeks; the pause, not a wall, is what gives the conscious mind a turn.
 */
object FocusRules {
    const val PASSES_PER_DAY = 2
    const val PASS_WAIT_MS = 30_000L
    const val PASS_LENGTH_MS = 5 * 60_000L

    /** Package names that are distracting by default (social, video, games, shopping). */
    val DEFAULT_BLOCKED = setOf(
        "com.instagram.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.twitter.android",
        "com.facebook.katana", "com.facebook.lite", "com.reddit.frontpage", "com.google.android.youtube",
        "com.netflix.mediaclient", "com.snapchat.android", "com.pinterest", "tv.twitch.android.app",
        "com.shopee.id", "com.tokopedia.tkpd", "com.lazada.android", "com.bukalapak.android", "com.tiket.gits",
        "com.mobile.legends", "com.dts.freefireth", "com.tencent.ig", "com.supercell.clashofclans",
        "com.roblox.client", "com.miHoYo.GenshinImpact",
    )

    /** Never blocked, whatever the user picks: phone, messages, settings, this app. */
    val NEVER_BLOCK = setOf(
        "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer", "com.android.phone",
        "com.android.emergency", "com.google.android.apps.messaging", "com.android.mms",
        "com.android.settings", "com.android.systemui",
    )

    fun isBlocked(pkg: String?, blocked: Set<String>, ownPackage: String): Boolean =
        pkg != null && pkg != ownPackage && pkg !in NEVER_BLOCK && pkg in blocked

    /** Android app categories (ApplicationInfo.CATEGORY_*) that count as distracting. */
    const val CATEGORY_GAME = 0
    const val CATEGORY_VIDEO = 2
    const val CATEGORY_SOCIAL = 4
    const val CATEGORY_NEWS = 5
    val DISTRACTING_CATEGORIES = setOf(CATEGORY_GAME, CATEGORY_VIDEO, CATEGORY_SOCIAL, CATEGORY_NEWS)

    /**
     * Focus is on when any reason holds: a focus or desk session, a habit window, or a deadline
     * within [Plan.URGENT_WITHIN_MS]. Passed in as booleans so the rule is testable.
     */
    fun isFocusTime(sessionRunning: Boolean, inHabitWindow: Boolean, urgentDeadline: Boolean, scheduleActive: Boolean): Boolean =
        sessionRunning || inHabitWindow || urgentDeadline || scheduleActive
}

/** The daily allowance of 5-minute passes, kept on the monotonic clock where it matters. */
data class PassBook(val day: Int = 0, val used: Int = 0, val activeUntilElapsedMs: Long = 0) {
    fun normalized(today: Int): PassBook = if (today != day) PassBook(today, 0, activeUntilElapsedMs) else this

    fun left(today: Int): Int = (FocusRules.PASSES_PER_DAY - normalized(today).used).coerceAtLeast(0)

    fun isActive(nowElapsedMs: Long): Boolean = nowElapsedMs < activeUntilElapsedMs &&
        activeUntilElapsedMs - nowElapsedMs <= FocusRules.PASS_LENGTH_MS

    /** Grants a pass if one is left and the user waited the full [FocusRules.PASS_WAIT_MS]. */
    fun take(today: Int, waitedMs: Long, nowElapsedMs: Long): PassBook? {
        val b = normalized(today)
        if (b.used >= FocusRules.PASSES_PER_DAY || waitedMs < FocusRules.PASS_WAIT_MS) return null
        return b.copy(used = b.used + 1, activeUntilElapsedMs = nowElapsedMs + FocusRules.PASS_LENGTH_MS)
    }
}

/** Attempts to open a blocked app, per day (shown back to the user: awareness is the point). */
data class AttemptCount(val day: Int = 0, val count: Int = 0) {
    fun add(today: Int) = if (today == day) copy(count = count + 1) else AttemptCount(today, 1)
    fun on(today: Int) = if (today == day) count else 0
}
