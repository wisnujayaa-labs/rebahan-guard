package io.github.wisnujayaa.rebahanguard.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import io.github.wisnujayaa.rebahanguard.MainActivity
import io.github.wisnujayaa.rebahanguard.R
import io.github.wisnujayaa.rebahanguard.core.Action
import io.github.wisnujayaa.rebahanguard.core.FaceObservation
import io.github.wisnujayaa.rebahanguard.core.GuardConfig
import io.github.wisnujayaa.rebahanguard.core.GuardEngine
import io.github.wisnujayaa.rebahanguard.core.GravityFilter
import io.github.wisnujayaa.rebahanguard.core.Phase
import io.github.wisnujayaa.rebahanguard.core.AlarmSoundPolicy
import io.github.wisnujayaa.rebahanguard.core.LyingJudge
import io.github.wisnujayaa.rebahanguard.core.PoseClassifier
import io.github.wisnujayaa.rebahanguard.core.SensorInput

/**
 * Foreground service (type = camera) that glues the hardware to [GuardEngine]:
 * gravity sensor → engine → camera / alarm.
 *
 * It is a [LifecycleService] so CameraX can bind the camera to the service's lifecycle.
 */
class GuardService : LifecycleService(), SensorEventListener {

    private lateinit var config: GuardConfig
    private lateinit var engine: GuardEngine
    private lateinit var faceChecker: FaceChecker
    private lateinit var alarm: AlarmPlayer
    private lateinit var sensorManager: SensorManager

    private lateinit var keyguardManager: KeyguardManager

    private var gravitySensor: Sensor? = null
    private var accelerometerFilter: GravityFilter? = null
    private var sensorsRegistered = false
    private var started = false

    // All engine access happens on the main thread (sensor callbacks, this receiver and camera
    // results are all delivered there), so the engine needs no locking.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!started) return
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    registerSensors()
                    GuardStatusStore.update { it.copy(screenOn = true) }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    // Saves battery: nothing to guard while the screen is off.
                    unregisterSensors()
                    perform(engine.onScreenOff())
                    publish(screenOn = false)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY

        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        started = true

        // Even our own intents are treated as untrusted input: clamp before use.
        val delaySec = SensorInput.sanitizeDelaySec(
            intent?.getIntExtra(EXTRA_DELAY_SEC, DEFAULT_DELAY_SEC) ?: DEFAULT_DELAY_SEC
        )
        val lyingElevationDeg = SensorInput.sanitizeLyingElevationDeg(
            intent?.getFloatExtra(EXTRA_LYING_ELEVATION, PoseClassifier.DEFAULT_LYING_ELEVATION_DEG)
                ?: PoseClassifier.DEFAULT_LYING_ELEVATION_DEG
        )
        val alarmSound = AlarmSoundPolicy.sanitize(intent?.getStringExtra(EXTRA_ALARM_URI))
        config = GuardConfig(
            triggerDelayMs = delaySec * 1_000L,
            lyingElevationDeg = lyingElevationDeg,
            strictMode = intent?.getBooleanExtra(EXTRA_STRICT, false) ?: false,
        )
        engine = GuardEngine(config)
        faceChecker = FaceChecker(this, config)
        alarm = AlarmPlayer(this, alarmSound?.let(Uri::parse))

        keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        if (gravitySensor == null) {
            // Some cheap phones have no fused gravity sensor: low-pass the accelerometer instead.
            gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            accelerometerFilter = GravityFilter()
        }
        if (gravitySensor == null) {
            Log.e(TAG, "No gravity or accelerometer sensor on this device")
        }

        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val screenOn = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        if (screenOn) registerSensors()
        GuardStatusStore.update { it.copy(running = true, screenOn = screenOn, phase = Phase.WATCHING) }

        // NOT_STICKY on purpose: Android forbids (re)starting a camera foreground service from
        // the background, so a system restart would crash. The user re-enables it from the app.
        return START_NOT_STICKY
    }

    private fun enterForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(getString(R.string.notif_watching)),
            // The "camera" service type only exists from Android 11 (API 30). On Android 10,
            // passing an unknown type would make startForeground() throw.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                0
            },
        )
        true
    } catch (e: Exception) {
        // SecurityException if the CAMERA permission is missing, or the start was not allowed.
        Log.e(TAG, "Could not start foreground service", e)
        false
    }

    // ---------------------------------------------------------------- sensors

    private fun registerSensors() {
        if (sensorsRegistered) return
        val sensor = gravitySensor ?: return
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        sensorsRegistered = true
    }

    private fun unregisterSensors() {
        if (!sensorsRegistered) return
        sensorManager.unregisterListener(this)
        sensorsRegistered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!started) return
        val filter = accelerometerFilter
        val values = if (filter != null) filter.update(event.values) else event.values

        // On the lock screen the user is not using the phone: never trigger the camera there.
        val orientation = SensorInput.toOrientation(
            values,
            deviceLocked = keyguardManager.isKeyguardLocked,
            lyingElevationDeg = config.lyingElevationDeg,
        )
        perform(engine.onPose(orientation, SystemClock.elapsedRealtime()))
        publish()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ---------------------------------------------------------------- actions

    private fun perform(action: Action) {
        when (action) {
            Action.NONE -> Unit
            Action.START_CAMERA_CHECK -> faceChecker.check(this) { face -> onFaceResult(face) }
            Action.CANCEL_CAMERA_CHECK -> faceChecker.cancel()
            Action.START_ALARM -> {
                alarm.start()
                updateNotification(getString(R.string.notif_alarm))
            }
            Action.STOP_ALARM -> {
                alarm.stop()
                updateNotification(getString(R.string.notif_watching))
            }
        }
    }

    private fun onFaceResult(face: FaceObservation?) {
        if (!started) return
        // A late result (after a watchdog timeout or screen-off) is ignored by the engine;
        // don't show it in the UI either.
        if (engine.phase != Phase.CHECKING) return
        perform(engine.onFaceResult(face, SystemClock.elapsedRealtime()))
        val lastCheck = LastCheck(
            atMillis = System.currentTimeMillis(),
            faceWidthRatio = face?.faceWidthRatio,
            rollDeg = face?.rollDeg,
            headTiltDeg = face?.let {
                LyingJudge.headTiltDeg(engine.lastOrientation.inPlaneRotationDeg, it.rollDeg)
            },
            lying = engine.phase == Phase.ALARMING,
        )
        GuardStatusStore.update { it.copy(lastCheck = lastCheck) }
        publish()
    }

    private fun publish(screenOn: Boolean? = null) {
        GuardStatusStore.update {
            it.copy(
                phase = engine.phase,
                pose = engine.lastPose,
                screenElevationDeg = engine.lastOrientation.screenElevationDeg,
                screenOn = screenOn ?: it.screenOn,
            )
        }
    }

    // ---------------------------------------------------------------- notification

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, GuardService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_guard)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        if (started) {
            started = false // late callbacks (sensor, camera) become no-ops from here on
            unregisterSensors()
            unregisterReceiver(screenReceiver)
            alarm.stop()
            faceChecker.release()
        }
        GuardStatusStore.update { it.copy(running = false, phase = Phase.WATCHING) }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GuardService"
        private const val CHANNEL_ID = "guard"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "io.github.wisnujayaa.rebahanguard.STOP"
        private const val EXTRA_DELAY_SEC = "delay_sec"
        private const val EXTRA_LYING_ELEVATION = "lying_elevation_deg"
        private const val EXTRA_STRICT = "strict_mode"
        private const val EXTRA_ALARM_URI = "alarm_uri"
        const val DEFAULT_DELAY_SEC = 20

        /** Must be called while the app is visible (Android's while-in-use camera rule). */
        fun start(context: Context, settings: GuardSettings) {
            val intent = Intent(context, GuardService::class.java)
                .putExtra(EXTRA_DELAY_SEC, settings.delaySec)
                .putExtra(EXTRA_LYING_ELEVATION, settings.lyingElevationDeg)
                .putExtra(EXTRA_STRICT, settings.strictMode)
                .putExtra(EXTRA_ALARM_URI, settings.alarmSoundUri)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GuardService::class.java))
        }
    }
}
