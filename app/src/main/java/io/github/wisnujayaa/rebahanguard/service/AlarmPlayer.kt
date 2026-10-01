package io.github.wisnujayaa.rebahanguard.service

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/** Loud, looping alarm + vibration. Played on the ALARM stream so it ignores media volume. */
class AlarmPlayer(private val context: Context) {
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

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        // Sound and vibration fail independently: a missing alarm sound (or a sound file the
        // system can't open) must never stop the phone from vibrating, and vice versa.
        try {
            ringtone = uri?.let { RingtoneManager.getRingtone(context, it) }?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                isLooping = true
                play()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Alarm sound failed; vibrating only", e)
            ringtone = null
        }

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

    private companion object {
        const val TAG = "AlarmPlayer"
    }
}
