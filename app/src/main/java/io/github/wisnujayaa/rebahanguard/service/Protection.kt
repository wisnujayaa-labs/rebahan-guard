package io.github.wisnujayaa.rebahanguard.service

import android.content.Context
import java.util.Calendar

/**
 * Whether the guard may currently be switched off with a single tap. It may not while:
 *  - a commitment is running,
 *  - a trusted partner is set up (then only they can switch it off), or
 *  - the nightly schedule is in its active window.
 */
object Protection {
    fun minuteOfDay(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    fun isProtected(context: Context): Boolean {
        if (CommitmentStore.isActive(context)) return true
        if (PartnerStore.hasPartner(context)) return true
        val schedule = GuardSettings.load(context).schedule
        return schedule.enabled && schedule.isWithin(minuteOfDay())
    }
}
