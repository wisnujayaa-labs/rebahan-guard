package io.github.wisnujayaa.rebahanguard.service

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import io.github.wisnujayaa.rebahanguard.core.Commitment
import io.github.wisnujayaa.rebahanguard.core.InterruptionLedger

/** Persists the active commitment and the interruption ledger. Never trusts what it reads. */
object CommitmentStore {
    private const val PREFS = "commitment"
    private const val KEY_START_WALL = "start_wall"
    private const val KEY_START_ELAPSED = "start_elapsed"
    private const val KEY_BOOT = "boot_count"
    private const val KEY_DURATION = "duration"
    private const val KEY_INTERRUPTIONS = "interruptions"
    private const val KEY_SESSION_OPEN = "session_open"
    private const val KEY_EMERGENCIES = "emergencies"
    private const val KEY_LEGIT_STOP = "legit_stop"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun bootCount(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    fun load(context: Context): Commitment? {
        val p = prefs(context)
        if (!p.contains(KEY_DURATION)) return null
        return try {
            Commitment.sanitize(
                Commitment(
                    startWallMs = p.getLong(KEY_START_WALL, 0),
                    startElapsedMs = p.getLong(KEY_START_ELAPSED, -1),
                    bootCount = p.getInt(KEY_BOOT, -1),
                    durationMs = p.getLong(KEY_DURATION, 0),
                )
            )
        } catch (e: ClassCastException) {
            null
        }
    }

    fun begin(context: Context, hours: Int): Commitment {
        val c = Commitment.start(System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootCount(context), hours)
        prefs(context).edit()
            .putLong(KEY_START_WALL, c.startWallMs)
            .putLong(KEY_START_ELAPSED, c.startElapsedMs)
            .putInt(KEY_BOOT, c.bootCount)
            .putLong(KEY_DURATION, c.durationMs)
            .putInt(KEY_INTERRUPTIONS, 0)
            .putInt(KEY_EMERGENCIES, 0)
            .commit() // synchronous: the guard must not start before this is on disk
        return c
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_START_WALL).remove(KEY_START_ELAPSED).remove(KEY_BOOT).remove(KEY_DURATION)
            .commit()
    }

    fun remainingMs(context: Context): Long {
        val c = load(context) ?: return 0
        return c.remainingMs(System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootCount(context))
    }

    fun isActive(context: Context): Boolean = remainingMs(context) > 0

    fun interruptions(context: Context): Int = prefs(context).getInt(KEY_INTERRUPTIONS, 0).coerceAtLeast(0)

    fun emergencies(context: Context): Int = prefs(context).getInt(KEY_EMERGENCIES, 0).coerceAtLeast(0)

    fun recordEmergency(context: Context) {
        prefs(context).edit().putInt(KEY_EMERGENCIES, (emergencies(context) + 1).coerceAtMost(9_999)).apply()
    }

    /** The guard just started: if the previous session never closed cleanly, count it. */
    fun onGuardStart(context: Context) {
        val p = prefs(context)
        val count = InterruptionLedger.onGuardStart(
            previousCount = interruptions(context),
            sessionWasOpen = p.getBoolean(KEY_SESSION_OPEN, false),
            commitmentActive = Protection.isProtected(context),
        )
        p.edit().putInt(KEY_INTERRUPTIONS, count).putBoolean(KEY_SESSION_OPEN, true).putBoolean(KEY_LEGIT_STOP, false).commit()
    }

    fun isSessionOpen(context: Context): Boolean = prefs(context).getBoolean(KEY_SESSION_OPEN, false)

    /** The partner code or the emergency path was used: the next stop is a legitimate one. */
    fun markLegitStop(context: Context) {
        prefs(context).edit().putBoolean(KEY_LEGIT_STOP, true).commit()
    }

    /** Called when the guard stops. Closes the session if the stop was allowed. */
    fun onGuardStopped(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_LEGIT_STOP, false) || !Protection.isProtected(context)) {
            p.edit().putBoolean(KEY_SESSION_OPEN, false).putBoolean(KEY_LEGIT_STOP, false).apply()
        }
    }

    /** The phone's clock or time zone was changed while protected: worth showing the user. */
    fun recordClockChange(context: Context) {
        prefs(context).edit().putInt(KEY_INTERRUPTIONS, (interruptions(context) + 1).coerceAtMost(9_999)).apply()
    }
}
