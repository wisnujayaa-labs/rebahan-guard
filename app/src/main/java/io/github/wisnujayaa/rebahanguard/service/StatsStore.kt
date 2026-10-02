package io.github.wisnujayaa.rebahanguard.service

import android.content.Context
import io.github.wisnujayaa.rebahanguard.core.NightRecord
import io.github.wisnujayaa.rebahanguard.core.NightStats
import java.util.TimeZone

/** Persists [NightRecord]s as a compact string: "night:guarded:caught:lockedMs:emergencies[:tampers];…"
 * (records written by v1.4 have no tampers field and still load). */
object StatsStore {
    private const val PREFS = "stats"
    private const val KEY = "nights"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun tonight(nowMs: Long = System.currentTimeMillis()): Int =
        NightStats.nightOf(nowMs, TimeZone.getDefault().getOffset(nowMs))

    fun load(context: Context): List<NightRecord> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return raw.split(';').mapNotNull { entry ->
            val f = entry.split(':')
            if (f.size != 5 && f.size != 6) return@mapNotNull null
            try {
                NightRecord(
                    night = f[0].toInt(),
                    guarded = f[1] == "1",
                    caught = f[2].toInt().coerceAtLeast(0),
                    lockedMs = f[3].toLong().coerceAtLeast(0),
                    emergencies = f[4].toInt().coerceAtLeast(0),
                    tampers = f.getOrNull(5)?.toInt()?.coerceAtLeast(0) ?: 0,
                )
            } catch (e: NumberFormatException) {
                null // a corrupted entry is skipped, not fatal
            }
        }
    }

    fun tampersTonight(context: Context): Int = load(context).firstOrNull { it.night == tonight() }?.tampers ?: 0

    fun update(context: Context, change: (NightRecord) -> NightRecord) {
        val records = NightStats.update(load(context), tonight(), change)
        val raw = records.joinToString(";") {
            "${it.night}:${if (it.guarded) 1 else 0}:${it.caught}:${it.lockedMs}:${it.emergencies}:${it.tampers}"
        }
        prefs(context).edit().putString(KEY, raw).apply()
    }
}
