package io.github.wisnujayaa.rebahanguard.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.RingtoneManager
import android.net.Uri
import io.github.wisnujayaa.rebahanguard.core.GravityFilter
import io.github.wisnujayaa.rebahanguard.core.SensorInput
import io.github.wisnujayaa.rebahanguard.service.AlarmPlayer
import kotlinx.coroutines.delay

/**
 * Records the screen elevation angle for [durationMs] (used by calibration). Must be called
 * from the main thread: sensor callbacks also arrive there, so no locking is needed.
 */
suspend fun recordScreenElevations(context: Context, durationMs: Long): List<Float> {
    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    val sensor = gravity ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return emptyList()
    val filter = if (gravity == null) GravityFilter() else null

    val samples = mutableListOf<Float>()
    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val values = if (filter != null) (filter.update(event.values) ?: return) else event.values
            val angle = SensorInput.toOrientation(values, deviceLocked = false).screenElevationDeg
            if (angle.isFinite()) samples += angle
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
    try {
        delay(durationMs)
    } finally {
        sensorManager.unregisterListener(listener)
    }
    return samples.toList()
}

/** Plays [uri] (or the default alarm) for a few seconds so the user can hear their choice. */
suspend fun previewAlarmSound(context: Context, uri: Uri?, durationMs: Long = 3_000) {
    val ringtone = try {
        RingtoneManager.getRingtone(context, uri ?: AlarmPlayer.defaultAlarmUri() ?: return)
    } catch (e: Exception) {
        null
    } ?: return
    try {
        ringtone.audioAttributes = AlarmPlayer.alarmAttributes()
        ringtone.play()
        delay(durationMs)
    } catch (e: Exception) {
        // Unplayable file: nothing to preview.
    } finally {
        try {
            ringtone.stop()
        } catch (e: Exception) {
            // ignore
        }
    }
}

/** Human-readable name of a sound, never throwing. */
fun soundTitle(context: Context, uri: String?): String {
    if (uri == null) return "Bawaan (alarm sistem)"
    return try {
        RingtoneManager.getRingtone(context, Uri.parse(uri))?.getTitle(context) ?: "Nada pilihan"
    } catch (e: Exception) {
        "Nada pilihan"
    }
}
