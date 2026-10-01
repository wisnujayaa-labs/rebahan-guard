package io.github.wisnujayaa.rebahanguard.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.wisnujayaa.rebahanguard.core.GravityFilter
import io.github.wisnujayaa.rebahanguard.core.SensorInput

/**
 * The phone's live screen elevation (+90 ceiling … -90 floor), read only while the app is in
 * the foreground. Lets the dial move even when the guard is off, which makes calibration and
 * the whole idea easy to understand. NaN until the first reading arrives.
 */
@Suppress("DEPRECATION") // LocalLifecycleOwner moved packages; this one still works on our BOM.
@Composable
fun rememberLiveScreenElevation(): State<Float> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val angle = remember { mutableFloatStateOf(Float.NaN) }

    DisposableEffect(lifecycleOwner) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val sensor = gravity ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val filter = if (gravity == null) GravityFilter() else null

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val values = if (filter != null) (filter.update(event.values) ?: return) else event.values
                val a = SensorInput.toOrientation(values, deviceLocked = false).screenElevationDeg
                if (a.isFinite()) angle.floatValue = a
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        var registered = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (!registered && sensor != null) {
                    sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
                    registered = true
                }
                Lifecycle.Event.ON_PAUSE -> if (registered) {
                    sensorManager.unregisterListener(listener)
                    registered = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer) // replays ON_RESUME if already resumed

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (registered) sensorManager.unregisterListener(listener)
        }
    }
    return angle
}

/** True when the user turned animations off in Android's accessibility settings. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
