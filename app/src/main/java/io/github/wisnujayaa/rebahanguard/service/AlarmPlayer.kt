package io.github.wisnujayaa.rebahanguard.service

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Loud, looping alarm + vibration. Played on the ALARM stream so it ignores media volume.
 *
 * @param soundUri the user's chosen sound (already validated by AlarmSoundPolicy), or null for
 *   the phone's default alarm. If the chosen sound can't be played (file deleted, permission
 *   revoked), it falls back to the default instead of staying silent.
 */
class AlarmPlayer(private val context: Context, private val soundUri: Uri? = null) {
    private var ringtone: Ringtone? = null
    private var vibrating = false

    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    val isPlaying: Boolean get() = ringtone != null || vibrating

    fun start() {
        if (isPlaying) return

        // Sound and vibration fail independently: a missing alarm sound (or a sound file the
        // system can't open) must never stop the phone from vibrating, and vice versa.
        ringtone = soundUri?.let { tryPlay(it) } ?: defaultAlarmUri()?.let { tryPlay(it) }
        if (ringtone == null) Log.w(TAG, "No alarm sound could be played; vibrating only")

        try {
            // 0 ms wait, 600 ms buzz, 400 ms pause — repeat from index 0 until stop().
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))
            vibrating = true
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed", e)
        }
    }

    fun stop() {
        try {
            ringtone?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop alarm sound", e)
        }
        ringtone = null
        if (vibrating) {
            vibrator.cancel()
            vibrating = false
        }
    }

    private fun tryPlay(uri: Uri): Ringtone? = try {
        RingtoneManager.getRingtone(context, uri)?.apply {
            audioAttributes = alarmAttributes()
            isLooping = true
            play()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not play $uri", e)
        null
    }

    companion object {
        private const val TAG = "AlarmPlayer"

        fun defaultAlarmUri(): Uri? =
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        fun alarmAttributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}
