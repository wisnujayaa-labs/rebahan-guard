package io.github.wisnujayaa.rebahanguard.service

import android.content.Context
import io.github.wisnujayaa.rebahanguard.core.AlarmSoundPolicy
import io.github.wisnujayaa.rebahanguard.core.Commitment
import io.github.wisnujayaa.rebahanguard.core.PoseClassifier
import io.github.wisnujayaa.rebahanguard.core.Schedule
import io.github.wisnujayaa.rebahanguard.core.SensorInput

/**
 * User settings, stored in SharedPreferences. Every value is sanitised on the way in AND on the
 * way out: the file can be stale (older app version) or corrupted, so it is never trusted.
 */
data class GuardSettings(
    val delaySec: Int = GuardService.DEFAULT_DELAY_SEC,
    val lyingElevationDeg: Float = PoseClassifier.DEFAULT_LYING_ELEVATION_DEG,
    val strictMode: Boolean = false,
    /** null = the phone's default alarm sound. */
    val alarmSoundUri: String? = null,
    /** Cover the screen while lying (needs "Display over other apps"). */
    val lockScreen: Boolean = true,
    /** 0 = no commitment; otherwise the guard can't simply be switched off for this many hours. */
    val commitmentHours: Int = 0,
    /** Nightly window in which the guard is enforced (and can't simply be switched off). */
    val schedule: Schedule = Schedule.DEFAULT,
) {
    fun sanitized() = copy(
        delaySec = SensorInput.sanitizeDelaySec(delaySec),
        lyingElevationDeg = SensorInput.sanitizeLyingElevationDeg(lyingElevationDeg),
        alarmSoundUri = AlarmSoundPolicy.sanitize(alarmSoundUri),
        commitmentHours = commitmentHours.coerceIn(0, Commitment.MAX_HOURS),
        schedule = schedule.sanitized(),
    )

    fun save(context: Context) {
        val s = sanitized()
        prefs(context).edit()
            .putInt(KEY_DELAY, s.delaySec)
            .putFloat(KEY_ELEVATION, s.lyingElevationDeg)
            .putBoolean(KEY_STRICT, s.strictMode)
            .putString(KEY_ALARM_URI, s.alarmSoundUri)
            .putBoolean(KEY_LOCK, s.lockScreen)
            .putInt(KEY_COMMIT_HOURS, s.commitmentHours)
            .putBoolean(KEY_SCHEDULE_ON, s.schedule.enabled)
            .putInt(KEY_SCHEDULE_START, s.schedule.startMinute)
            .putInt(KEY_SCHEDULE_END, s.schedule.endMinute)
            .apply()
    }

    companion object {
        private const val PREFS = "settings"
        private const val KEY_DELAY = "delay_sec"
        private const val KEY_ELEVATION = "lying_elevation_deg"
        private const val KEY_STRICT = "strict_mode"
        private const val KEY_ALARM_URI = "alarm_uri"
        private const val KEY_LOCK = "lock_screen"
        private const val KEY_COMMIT_HOURS = "commitment_hours"
        private const val KEY_SCHEDULE_ON = "schedule_on"
        private const val KEY_SCHEDULE_START = "schedule_start"
        private const val KEY_SCHEDULE_END = "schedule_end"

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun load(context: Context): GuardSettings {
            val p = prefs(context)
            return try {
                GuardSettings(
                    delaySec = p.getInt(KEY_DELAY, GuardService.DEFAULT_DELAY_SEC),
                    lyingElevationDeg = p.getFloat(KEY_ELEVATION, PoseClassifier.DEFAULT_LYING_ELEVATION_DEG),
                    strictMode = p.getBoolean(KEY_STRICT, false),
                    alarmSoundUri = p.getString(KEY_ALARM_URI, null),
                    lockScreen = p.getBoolean(KEY_LOCK, true),
                    commitmentHours = p.getInt(KEY_COMMIT_HOURS, 0),
                    schedule = Schedule(
                        enabled = p.getBoolean(KEY_SCHEDULE_ON, Schedule.DEFAULT.enabled),
                        startMinute = p.getInt(KEY_SCHEDULE_START, Schedule.DEFAULT.startMinute),
                        endMinute = p.getInt(KEY_SCHEDULE_END, Schedule.DEFAULT.endMinute),
                    ),
                ).sanitized()
            } catch (e: ClassCastException) {
                // A key stored with a different type (e.g. by an older version): start fresh.
                GuardSettings()
            }
        }
    }
}
