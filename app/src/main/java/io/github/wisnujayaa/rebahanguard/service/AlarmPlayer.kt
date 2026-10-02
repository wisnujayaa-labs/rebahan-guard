package io.github.wisnujayaa.rebahanguard.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import io.github.wisnujayaa.rebahanguard.core.VibrationLadder

/**
 * Loud, looping alarm + vibration that gets stronger every 10 seconds.
 *
 * The sound plays on the ALARM stream: it is separate from media volume and still plays in
 * silent mode. While the alarm is active the guard also holds that stream at full volume
 * ([enforceVolume]); turning it down is detected by the service and counted as an attempt.
 *
 * @param soundUri the user's chosen sound (already validated by AlarmSoundPolicy), or null for
 *   the phone's default alarm. If the chosen sound can't be played (file deleted, permission
 *   revoked), it falls back to the default instead of staying silent.
 */
class AlarmPlayer(private val context: Context, private val soundUri: Uri? = null) {
    private var ringtone: Ringtone? = null
    private var vibrating = false
    private var vibrationStartMs = 0L
    private var vibrationAtMax = false
    private var vibrationLevel = -1
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** The alarm volume before we raised it, restored when the alarm is fully over. */
    private var savedVolume: Int? = null

    /** What we set the alarm stream to; 0 if we couldn't set it. */
    var enforcedVolume: Int = 0
        private set

    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    private val ladderTick = object : Runnable {
        override fun run() {
            if (!vibrating) return
            val level = VibrationLadder.level(SystemClock.elapsedRealtime() - vibrationStartMs, vibrationAtMax)
            if (level != vibrationLevel) vibrate(level)
            if (level < VibrationLadder.LEVELS - 1) handler.postDelayed(this, 1_000)
        }
    }

    val isPlaying: Boolean get() = ringtone != null || vibrating
    val isSounding: Boolean get() = ringtone != null

    /** Sound + escalating vibration. [vibrateAtMax] skips the gentle steps (repeat offenders). */
    fun start(vibrateAtMax: Boolean = false) {
        enforceVolume()
        if (ringtone == null) {
            // Sound and vibration fail independently: a missing alarm sound (or a sound file the
            // system can't open) must never stop the phone from vibrating, and vice versa.
            ringtone = soundUri?.let { tryPlay(it) } ?: defaultAlarmUri()?.let { tryPlay(it) }
            if (ringtone == null) Log.w(TAG, "No alarm sound could be played; vibrating only")
        }
        startVibration(vibrateAtMax)
    }

    /** Vibration only (the lock stays up after the sound has stopped). */
    fun startVibration(atMax: Boolean) {
        if (vibrating && (vibrationAtMax || !atMax)) return
        vibrationStartMs = SystemClock.elapsedRealtime()
        vibrationAtMax = atMax
        vibrationLevel = -1
        vibrating = true
        handler.removeCallbacks(ladderTick)
        handler.post(ladderTick)
    }

    /** Raise the alarm stream to its maximum. Returns the volume set, or 0 if not allowed. */
    fun enforceVolume(): Int {
        try {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            if (savedVolume == null) savedVolume = current
            if (current < max) audioManager.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            enforcedVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM).takeIf { it >= max } ?: 0
        } catch (e: Exception) {
            // SecurityException under some Do Not Disturb policies, or a fixed-volume device.
            Log.w(TAG, "Could not raise the alarm volume", e)
            enforcedVolume = 0
        }
        return enforcedVolume
    }

    fun currentVolume(): Int = try {
        audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
    } catch (e: Exception) {
        -1
    }

    /** A short double buzz: the heads-up before a lock. */
    fun warn() {
        if (vibrating) return // the alarm's own (stronger) vibration is already running
        try {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 150, 250), -1))
        } catch (e: Exception) {
            Log.w(TAG, "Warning vibration failed", e)
        }
    }

    /** Stops the sound only; vibration (if any) continues. */
    fun silence() {
        try {
            ringtone?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop alarm sound", e)
        }
        ringtone = null
    }

    fun stopVibration() {
        handler.removeCallbacks(ladderTick)
        if (vibrating) {
            try {
                vibrator.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Could not cancel vibration", e)
            }
            vibrating = false
        }
        vibrationLevel = -1
    }

    fun stop() {
        silence()
        stopVibration()
        restoreVolume()
    }

    private fun restoreVolume() {
        val previous = savedVolume ?: return
        savedVolume = null
        enforcedVolume = 0
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, previous, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore the alarm volume", e)
        }
    }

    private fun vibrate(level: Int) {
        vibrationLevel = level
        val p = VibrationLadder.pattern(level)
        try {
            val effect = if (vibrator.hasAmplitudeControl()) {
                VibrationEffect.createWaveform(p.timings, p.amplitudes, 0)
            } else {
                VibrationEffect.createWaveform(p.timings, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, alarmAttributes())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed", e)
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
