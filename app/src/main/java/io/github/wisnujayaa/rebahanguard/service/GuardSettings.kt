package io.github.wisnujayaa.rebahanguard.service

import android.content.Context
import io.github.wisnujayaa.rebahanguard.core.AlarmSoundPolicy
import io.github.wisnujayaa.rebahanguard.core.PoseClassifier
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
) {
    fun sanitized() = copy(
        delaySec = SensorInput.sanitizeDelaySec(delaySec),
        lyingElevationDeg = SensorInput.sanitizeLyingElevationDeg(lyingElevationDeg),
        alarmSoundUri = AlarmSoundPolicy.sanitize(alarmSoundUri),
    )

    fun save(context: Context) {
        val s = sanitized()
        prefs(context).edit()
            .putInt(KEY_DELAY, s.delaySec)
            .putFloat(KEY_ELEVATION, s.lyingElevationDeg)
            .putBoolean(KEY_STRICT, s.strictMode)
            .putString(KEY_ALARM_URI, s.alarmSoundUri)
            .apply()
    }

    companion object {
        private const val PREFS = "settings"
        private const val KEY_DELAY = "delay_sec"
        private const val KEY_ELEVATION = "lying_elevation_deg"
        private const val KEY_STRICT = "strict_mode"
        private const val KEY_ALARM_URI = "alarm_uri"

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
                ).sanitized()
            } catch (e: ClassCastException) {
                // A key stored with a different type (e.g. by an older version): start fresh.
                GuardSettings()
            }
        }
    }
}
