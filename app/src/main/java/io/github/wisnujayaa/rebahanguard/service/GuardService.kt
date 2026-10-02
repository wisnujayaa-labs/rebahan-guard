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
import android.media.AudioManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import io.github.wisnujayaa.rebahanguard.core.CheckReport
import io.github.wisnujayaa.rebahanguard.core.LockMessages
import io.github.wisnujayaa.rebahanguard.core.Schedule
import io.github.wisnujayaa.rebahanguard.core.FaceObservation
import io.github.wisnujayaa.rebahanguard.core.GuardConfig
import io.github.wisnujayaa.rebahanguard.core.GuardEngine
import io.github.wisnujayaa.rebahanguard.core.GravityFilter
import io.github.wisnujayaa.rebahanguard.core.Phase
import io.github.wisnujayaa.rebahanguard.core.AlarmSoundPolicy
import io.github.wisnujayaa.rebahanguard.core.LyingJudge
import io.github.wisnujayaa.rebahanguard.core.Orientation
import io.github.wisnujayaa.rebahanguard.core.Pose
import io.github.wisnujayaa.rebahanguard.core.PoseClassifier
import io.github.wisnujayaa.rebahanguard.core.SensorInput
import io.github.wisnujayaa.rebahanguard.core.DeskRest
import io.github.wisnujayaa.rebahanguard.core.Plan
import io.github.wisnujayaa.rebahanguard.core.StillnessMeter
import io.github.wisnujayaa.rebahanguard.core.TamperPenalty
import io.github.wisnujayaa.rebahanguard.core.TypedPhrase
import io.github.wisnujayaa.rebahanguard.core.DreamRules
import io.github.wisnujayaa.rebahanguard.core.FocusRules
import io.github.wisnujayaa.rebahanguard.core.Proof

/**
 * Foreground service (type = camera) that glues the hardware to [GuardEngine]:
 * gravity sensor → engine → camera / lock screen / alarm.
 *
 * Safety rules enforced here, outside the engine: phone calls are never blocked, the lock screen
 * always offers an emergency exit, and no lock outlives GuardConfig.maxLockMs.
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
    private lateinit var audioManager: AudioManager
    private lateinit var overlay: LockOverlay
    private val handler = Handler(Looper.getMainLooper())
    /** Locked: the sound stops after a while, the (escalating) vibration doesn't. */
    private val stopSound = Runnable { if (overlay.isShowing) alarm.silence() else alarm.stop() }

    /**
     * Penalty for turning the alarm volume down. Counted down only while the screen is on, the
     * lock is shown and the user is NOT lying — lying time and screen-off time don't count.
     */
    private var penaltyLeftMs = 0L
    private var penaltyNeedsPhrase = false
    private var lastTamperMs = 0L
    private var lastPenaltyTickMs = 0L
    private val penaltyTick = object : Runnable {
        override fun run() {
            if (!started || penaltyLeftMs <= 0) return
            val now = SystemClock.elapsedRealtime()
            val dt = (now - lastPenaltyTickMs).coerceIn(0, 2_000)
            lastPenaltyTickMs = now
            val interactive = powerManager.isInteractive && !keyguardManager.isKeyguardLocked
            if (interactive && overlay.isShowing && engine.phase != Phase.ALARMING && !overlay.isAskingPhrase) {
                penaltyLeftMs = (penaltyLeftMs - dt).coerceAtLeast(0)
                if (penaltyLeftMs > 0) {
                    overlay.showPenalty((penaltyLeftMs + 999) / 1000, penaltyNeedsPhrase)
                } else if (penaltyNeedsPhrase) {
                    penaltyLeftMs = 1 // keep the lock until the sentence is typed
                    overlay.askPhrase(TypedPhrase(TamperPenalty.PHRASE)) { releasePenalty() }
                } else {
                    releasePenalty()
                    return
                }
            }
            handler.postDelayed(this, 1_000)
        }
    }

    /** While the alarm is active the alarm stream is held at full volume. */
    private val volumeWatch = object : Runnable {
        override fun run() {
            if (!started) return
            val active = alarm.isPlaying || warningUntilMs > 0
            if (!active) return
            if (TamperPenalty.isAttempt(alarm.currentVolume(), alarm.enforcedVolume)) {
                alarm.enforceVolume()
                onTamper()
            }
            handler.postDelayed(this, VOLUME_CHECK_MS)
        }
    }

    private var lockEnabled = true

    /**
     * The overlay stays up across a screen-off that happened while locked, so turning the screen
     * off and on again is not a way out. It goes away once the user is shown not to be lying.
     */
    private var stickyLock = false
    private var stickySinceMs = 0L

    /** "Darurat" pressed on the lock screen: the guard pauses until this time. */
    private var emergencyUntilMs = 0L

    private var schedule = Schedule.DEFAULT

    /** The 10-second "sit up now" warning that precedes a (first) lock. */
    private var warningUntilMs = 0L
    private val warningTick = object : Runnable {
        override fun run() {
            if (!started || engine.phase != Phase.ALARMING) return
            val left = warningUntilMs - SystemClock.elapsedRealtime()
            if (left <= 0) {
                engageLock()
            } else {
                overlay.showWarning(((left + 999) / 1000).toInt())
                handler.postDelayed(this, 1_000)
            }
        }
    }

    private var lockStartedMs = 0L
    private var lastGuardedNight = Int.MIN_VALUE
    private var messageIndex = 0L

    private lateinit var powerManager: PowerManager
    private lateinit var sessions: SessionRunner
    private var sessionOrientation: Orientation = Orientation.UNKNOWN
    private lateinit var blocker: AppBlocker
    private var stepSensor: Sensor? = null
    private var stepRegistered = false
    private var lastNagMs = HashMap<Long, Long>()
    private var strictWasOn = false
    private val minuteTick = object : Runnable {
        override fun run() {
            if (!started) return
            onMinute()
            handler.postDelayed(this, 60_000)
        }
    }
    private var gravitySensor: Sensor? = null
    private var proximitySensor: Sensor? = null
    private var rawAccelSensor: Sensor? = null
    private var proximityNear: Boolean? = null
    private val stillness = StillnessMeter()
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
                    blocker.start()
                    GuardStatusStore.update { it.copy(screenOn = true) }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    // Saves battery: nothing to guard while the screen is off — except during a
                    // desk session, where the camera checks need the phone's orientation.
                    if (!sessions.needsSensorsWithScreenOff) unregisterSensors()
                    blocker.stop()
                    val wasLocked = engine.phase == Phase.ALARMING
                    if (wasLocked && overlay.isShowing) {
                        stickyLock = true
                        stickySinceMs = SystemClock.elapsedRealtime()
                    }
                    perform(engine.onScreenOff())
                    overlay.hideRingLight()
                    publish(screenOn = false)
                }
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                    // Moving the clock is a way around the schedule; make it visible.
                    if (Protection.isProtected(this@GuardService)) CommitmentStore.recordClockChange(this@GuardService)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (!started && intent?.action == ACTION_STOP_SESSION) {
            stopSelf() // nothing running, nothing to stop
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            // While protected (commitment, partner, schedule) the only way out is inside the app.
            if (!Protection.isProtected(this)) stopSelf()
            return START_NOT_STICKY
        }
        if (started) {
            handleSessionIntent(intent)
            return START_NOT_STICKY
        }

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
        lockEnabled = intent?.getBooleanExtra(EXTRA_LOCK, true) ?: true
        schedule = GuardSettings.load(this).schedule
        engine = GuardEngine(config)
        overlay = LockOverlay(this, onEmergency = ::onEmergency, onVolumeKey = { onTamper() })
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        CommitmentStore.onGuardStart(this)
        BootReceiver.dismiss(this)
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
        // Telling "face down on a desk" from "held above the face" (see DeskRest).
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        rawAccelSensor = if (accelerometerFilter == null) sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null

        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        sessions = SessionRunner(
            context = this,
            owner = this,
            config = config,
            faceChecker = faceChecker,
            alarm = alarm,
            orientation = { sessionOrientation },
            screenOn = { powerManager.isInteractive },
            notify = ::notifySession,
            onAlarm = ::startVolumeWatch,
            paused = { isInCall() || SystemClock.elapsedRealtime() < emergencyUntilMs },
        )
        blocker = AppBlocker(this, focusReason = ::focusReason)
        if (powerManager.isInteractive) blocker.start()
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        strictWasOn = FocusStore.isStrictEnabled(this)
        handler.postDelayed(minuteTick, 60_000)

        val screenOn = powerManager.isInteractive
        if (screenOn) registerSensors()
        handleSessionIntent(intent)
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
                // Location too when allowed, for "Tempat" sessions (Android 14 requires the type).
                val location = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or (if (location) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
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
        proximitySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        rawAccelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sensorsRegistered = true
    }

    private fun unregisterSensors() {
        if (!sensorsRegistered) return
        // One by one: unregisterListener(this) would also drop the step counter of a MOVE session.
        gravitySensor?.let { sensorManager.unregisterListener(this, it) }
        proximitySensor?.let { sensorManager.unregisterListener(this, it) }
        rawAccelSensor?.let { sensorManager.unregisterListener(this, it) }
        sensorsRegistered = false
        stillness.reset()
        proximityNear = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!started) return
        val now = SystemClock.elapsedRealtime()
        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                proximityNear = DeskRest.isNear(event.values.getOrElse(0) { Float.NaN }, event.sensor.maximumRange)
                return
            }
            Sensor.TYPE_STEP_COUNTER -> {
                event.values.getOrNull(0)?.let { sessions.onStepCounter(it) }
                return
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (event.values.size >= 3) stillness.add(event.values[0], event.values[1], event.values[2], now)
                if (accelerometerFilter == null) return // only a stillness input; gravity comes from TYPE_GRAVITY
            }
        }
        val filter = accelerometerFilter
        val values = if (filter != null) filter.update(event.values) else event.values

        // Sessions need the phone's real orientation even when it is locked on a desk stand or
        // outside the schedule (the guard's own reading is UNKNOWN then).
        if (values.size >= 3) {
            sessionOrientation = DeskRest.resolve(
                PoseClassifier.measure(values[0], values[1], values[2], config.lyingElevationDeg),
                proximityNear,
                stillness.isStill(now),
            )
        }
        // Screen off (sensors kept on only for a desk session): nobody is using the phone, so
        // the lying check has nothing to look at.
        if (!powerManager.isInteractive) return

        // Phone calls (including WhatsApp/VoIP), the emergency pause and hours outside the
        // schedule always win.
        val outside = !schedule.isWithin(Protection.minuteOfDay())
        if (isInCall() || now < emergencyUntilMs || outside) {
            if (isInCall() || now < emergencyUntilMs) sessions.pauseAlarm()
            releaseStickyLock()
            perform(engine.onScreenOff())
            if (penaltyLeftMs > 0) overlay.hide() // paused, not forgiven
            publish(outsideSchedule = outside)
            return
        }
        markGuardedTonight()
        resumePenaltyIfNeeded()

        // On the lock screen the user is not using the phone: never trigger the camera there.
        val keyguardLocked = keyguardManager.isKeyguardLocked
        val measured = SensorInput.toOrientation(
            values,
            deviceLocked = keyguardLocked,
            lyingElevationDeg = config.lyingElevationDeg,
        )
        val orientation = DeskRest.resolve(measured, proximityNear, stillness.isStill(now))
        perform(engine.onPose(orientation, now))
        if (stickyLock && !keyguardLocked) updateStickyLock(orientation, now)
        if (overlay.isShowing) overlay.update(orientation.screenElevationDeg, config.lyingElevationDeg)
        publish()
    }

    private fun updateStickyLock(orientation: Orientation, now: Long) {
        when {
            engine.phase == Phase.ALARMING -> stickyLock = false // the engine owns the lock again
            orientation.pose != Pose.UNKNOWN && !config.isSuspicious(orientation.pose) -> releaseStickyLock()
            engine.phase == Phase.COOLDOWN -> releaseStickyLock() // camera says: not lying
            now - stickySinceMs >= config.maxLockMs -> releaseStickyLock() // safety cap
        }
    }

    private fun releaseStickyLock() {
        if (!stickyLock) return
        stickyLock = false
        if (engine.phase != Phase.ALARMING && penaltyLeftMs <= 0) overlay.hide()
    }

    // ---------------------------------------------------------------- tamper penalty

    /** The alarm volume went down (slider or key) while the alarm was active. */
    private fun onTamper() {
        if (!started) return
        if (!alarm.isPlaying && warningUntilMs <= 0) return // nothing to silence: not an attempt
        val now = SystemClock.elapsedRealtime()
        if (now - lastTamperMs < TAMPER_DEBOUNCE_MS) return // one press-and-hold = one attempt
        lastTamperMs = now

        StatsStore.update(this) { it.copy(tampers = it.tampers + 1) }
        val attempts = StatsStore.tampersTonight(this).coerceAtLeast(1)
        val extra = TamperPenalty.extraLockMs(attempts)
        penaltyLeftMs = (penaltyLeftMs + extra).coerceAtMost(MAX_PENALTY_MS)
        penaltyNeedsPhrase = penaltyNeedsPhrase || TamperPenalty.requiresPhrase(attempts)

        if (warningUntilMs > 0) engageLock() // no more warning for someone silencing it
        alarm.start(vibrateAtMax = TamperPenalty.startsAtMaxVibration(attempts)) // sound comes back
        if (engine.phase == Phase.ALARMING || overlay.isShowing) {
            handler.removeCallbacks(stopSound)
            handler.postDelayed(stopSound, LOCKED_SOUND_MS)
        } // else: a desk-session alarm, which the session stops when the user is back
        overlay.showTamper(attempts, (extra / TamperPenalty.MINUTE_MS).toInt(), penaltyNeedsPhrase)
        startPenaltyTick()
        publish()
    }

    private fun startPenaltyTick() {
        lastPenaltyTickMs = SystemClock.elapsedRealtime()
        handler.removeCallbacks(penaltyTick)
        handler.postDelayed(penaltyTick, 1_000)
    }

    /** After a call, an emergency pause or a restart of the screen: the penalty picks up again. */
    private fun resumePenaltyIfNeeded() {
        if (penaltyLeftMs <= 0 || overlay.isShowing || !lockEnabled) return
        if (overlay.show(lockMessage())) {
            overlay.showPenalty((penaltyLeftMs + 999) / 1000, penaltyNeedsPhrase)
            startPenaltyTick()
        }
    }

    private fun releasePenalty() {
        penaltyLeftMs = 0
        penaltyNeedsPhrase = false
        handler.removeCallbacks(penaltyTick)
        if (engine.phase != Phase.ALARMING && !stickyLock) overlay.hide()
        publish()
    }

    /** A pressing deadline first; otherwise the habit that is behind, in the user's own words. */
    private fun lockMessage(): String {
        val now = System.currentTimeMillis()
        val urgent = Plan.mostUrgent(PlanStore.load(this), now)
        lockQuote = null
        if (urgent != null) return Plan.lockMessage(urgent, now)
        val copy = DreamRules.lockCopy(DreamStore.load(this), DreamStore.today(), Protection.minuteOfDay(), messageIndex++)
        if (copy != null) {
            lockQuote = copy.quote
            return copy.body
        }
        return LockMessages.pick(messageIndex++)
    }

    private var lockQuote: String? = null

    // ---------------------------------------------------------------- sessions & focus

    private fun handleSessionIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_START_SESSION -> {
                val habit = DreamStore.load(this).habit(intent.getLongExtra(EXTRA_HABIT, -1)) ?: return
                sessions.start(habit, intent.getIntExtra(EXTRA_MINUTES, 25))
                if (habit.proof == Proof.MOVE) registerSteps()
                if (habit.proof == Proof.PLACE) enterForeground() // adds the location type if now allowed
                if (habit.proof == Proof.DESK) registerSensors()
                updateNotification("Sesi berjalan: ${habit.title}")
            }
            ACTION_STOP_SESSION -> {
                sessions.stop()
                unregisterSteps()
                if (!powerManager.isInteractive) unregisterSensors()
                updateNotification(getString(R.string.notif_watching))
            }
        }
    }

    private fun registerSteps() {
        val sensor = stepSensor ?: return
        if (stepRegistered) return
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACTIVITY_RECOGNITION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notifySession("Izinkan \"Aktivitas fisik\" agar langkah bisa dihitung.")
            return
        }
        stepRegistered = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun unregisterSteps() {
        val sensor = stepSensor ?: return
        if (!stepRegistered) return
        sensorManager.unregisterListener(this, sensor)
        stepRegistered = false
    }

    private var focusCache: Pair<Long, String?> = 0L to null

    /** Why focus mode is on right now; re-evaluated at most every 20 s (it reads files). */
    private fun focusReason(): String? {
        val now = SystemClock.elapsedRealtime()
        if (sessions.isRunning) return "sesi berjalan"
        if (isInCall() || now < emergencyUntilMs) return null
        if (now - focusCache.first < FOCUS_CACHE_MS) return focusCache.second
        return computeFocusReason().also { focusCache = now to it }
    }

    private fun computeFocusReason(): String? {
        if (isInCall() || SystemClock.elapsedRealtime() < emergencyUntilMs) return null
        if (sessions.isRunning) return "sesi berjalan"
        val book = DreamStore.load(this)
        val minute = Protection.minuteOfDay()
        val inWindow = DreamRules.behindToday(book, DreamStore.today(), minute).any { DreamRules.inWindow(it, minute) }
        if (inWindow) return "jam target"
        val now = System.currentTimeMillis()
        val urgent = Plan.mostUrgent(PlanStore.load(this), now)?.dueWallMs?.let { it - now <= FOCUS_DEADLINE_MS } == true
        val scheduleOn = schedule.enabled && schedule.isWithin(minute)
        return when {
            urgent -> "deadline dekat"
            scheduleOn -> "jadwal jaga"
            else -> null
        }.takeIf { FocusRules.isFocusTime(false, inWindow, urgent, scheduleOn) }
    }

    /** Once a minute: habit-window reminders, and strict mode switched off during protection. */
    private fun onMinute() {
        val strictOn = FocusStore.isStrictEnabled(this)
        if (strictWasOn && !strictOn && Protection.isProtected(this)) CommitmentStore.recordClockChange(this)
        strictWasOn = strictOn
        if (sessions.isRunning) return
        val now = SystemClock.elapsedRealtime()
        val minute = Protection.minuteOfDay()
        for (h in DreamRules.behindToday(DreamStore.load(this), DreamStore.today(), minute)) {
            if (!DreamRules.inWindow(h, minute) || h.proof == Proof.HONEST || h.proof == Proof.PHOTO) continue
            if (now - (lastNagMs[h.id] ?: 0L) < NAG_EVERY_MS) continue
            lastNagMs[h.id] = now
            notifySession("Jam target \u201C${h.title}\u201D sudah mulai. Buka Rebahan Guard dan mulai sesinya.")
        }
    }

    private fun notifySession(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(SESSION_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(SESSION_CHANNEL_ID, getString(R.string.notif_channel_sessions), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val openApp = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, SESSION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_guard)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(SESSION_NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notifications not allowed", e)
        }
    }

    private fun markGuardedTonight() {
        val night = StatsStore.tonight()
        if (night == lastGuardedNight) return
        lastGuardedNight = night
        StatsStore.update(this) { it.copy(guarded = true) }
    }

    private fun isInCall(): Boolean = when (audioManager.mode) {
        AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> true
        else -> false
    }

    private fun onEmergency() {
        if (!started) return
        emergencyUntilMs = SystemClock.elapsedRealtime() + EMERGENCY_PAUSE_MS
        sessions.pauseAlarm()
        CommitmentStore.recordEmergency(this)
        StatsStore.update(this) { it.copy(emergencies = it.emergencies + 1) }
        stickyLock = false
        perform(engine.onScreenOff())
        overlay.hide()
        publish()
        try {
            startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "Could not open the dialer", e)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ---------------------------------------------------------------- actions

    private fun perform(action: Action) {
        when (action) {
            Action.NONE -> Unit
            Action.START_CAMERA_CHECK -> faceChecker.check(
                owner = this,
                // Light the face in a dark room — but never over the lock screen, where it would
                // hide the emergency button and flash full white at night.
                onDark = { if (lockEnabled && !overlay.isShowing) overlay.showRingLight() },
            ) { face, report -> onFaceResult(face, report) }
            Action.CANCEL_CAMERA_CHECK -> {
                faceChecker.cancel()
                overlay.hideRingLight()
            }
            Action.START_ALARM -> {
                lockStartedMs = SystemClock.elapsedRealtime()
                StatsStore.update(this) { it.copy(caught = it.caught + 1) }
                updateNotification(getString(R.string.notif_alarm))
                when {
                    !lockEnabled || !overlay.canShow() -> {
                        // No lock screen available: the alarm has to do all the work.
                        alarm.start()
                        handler.removeCallbacks(stopSound)
                        handler.postDelayed(stopSound, UNLOCKED_SOUND_MS)
                    }
                    engine.lastLockWasRepeat || overlay.isShowing -> engageLock() // no second warning
                    else -> {
                        alarm.warn()
                        alarm.enforceVolume() // turning it down during the warning counts too
                        warningUntilMs = lockStartedMs + WARNING_MS
                        handler.removeCallbacks(warningTick)
                        handler.post(warningTick)
                        startVolumeWatch()
                    }
                }
                publish()
            }
            Action.STOP_ALARM -> {
                handler.removeCallbacks(stopSound)
                handler.removeCallbacks(warningTick)
                warningUntilMs = 0
                alarm.stop()
                faceChecker.cancel() // a strict-mode re-check may still be running
                overlay.hideRingLight()
                overlay.hideWarning()
                when {
                    penaltyLeftMs > 0 && overlay.isShowing -> startPenaltyTick() // sat up, penalty remains
                    !stickyLock -> overlay.hide()
                }
                if (lockStartedMs > 0) {
                    val lockedMs = SystemClock.elapsedRealtime() - lockStartedMs
                    StatsStore.update(this) { it.copy(lockedMs = it.lockedMs + lockedMs) }
                    lockStartedMs = 0
                }
                updateNotification(getString(R.string.notif_watching))
            }
        }
    }

    /** Warning over and still lying: cover the screen and sound the alarm. */
    private fun engageLock() {
        handler.removeCallbacks(warningTick)
        warningUntilMs = 0
        overlay.hideWarning()
        overlay.show(lockMessage())
        overlay.setQuote(lockQuote)
        alarm.start(vibrateAtMax = TamperPenalty.startsAtMaxVibration(StatsStore.tampersTonight(this)))
        startVolumeWatch()
        handler.removeCallbacks(stopSound)
        handler.postDelayed(stopSound, LOCKED_SOUND_MS)
        publish()
    }

    private fun startVolumeWatch() {
        handler.removeCallbacks(volumeWatch)
        handler.postDelayed(volumeWatch, VOLUME_CHECK_MS)
    }

    private fun onFaceResult(face: FaceObservation?, report: CheckReport) {
        overlay.hideRingLight()
        if (!started) return
        // A late result (after a watchdog timeout or screen-off) is ignored by the engine;
        // don't show it in the UI either.
        if (engine.phase != Phase.CHECKING && !engine.lockRecheckInFlight) return
        perform(engine.onFaceResult(face, SystemClock.elapsedRealtime()))
        val lastCheck = LastCheck(
            atMillis = System.currentTimeMillis(),
            faceWidthRatio = face?.faceWidthRatio,
            rollDeg = face?.rollDeg,
            headTiltDeg = face?.let {
                LyingJudge.headTiltDeg(engine.lastOrientation.inPlaneRotationDeg, it.rollDeg)
            },
            lying = engine.phase == Phase.ALARMING,
            report = report,
            lockReason = if (engine.phase == Phase.ALARMING) engine.lastLockReason else null,
        )
        GuardStatusStore.update { it.copy(lastCheck = lastCheck, checks = (listOf(lastCheck) + it.checks).take(10)) }
        publish()
    }

    private fun publish(screenOn: Boolean? = null, outsideSchedule: Boolean = false) {
        GuardStatusStore.update {
            it.copy(
                phase = engine.phase,
                pose = engine.lastPose,
                screenElevationDeg = engine.lastOrientation.screenElevationDeg,
                screenOn = screenOn ?: it.screenOn,
                outsideSchedule = outsideSchedule,
                warningUntilElapsedMs = warningUntilMs,
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
            .apply {
                // No "stop" shortcut while the guard is protected.
                if (!Protection.isProtected(this@GuardService)) {
                    addAction(0, getString(R.string.action_stop), stop)
                }
            }
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
            handler.removeCallbacks(stopSound)
            handler.removeCallbacks(warningTick)
            handler.removeCallbacks(penaltyTick)
            handler.removeCallbacks(volumeWatch)
            handler.removeCallbacks(minuteTick)
            sessions.stop(silent = true)
            sessions.release()
            blocker.stop()
            unregisterSteps()
            alarm.stop()
            overlay.hideAll()
            faceChecker.release()
            CommitmentStore.onGuardStopped(this)
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
        private const val EXTRA_LOCK = "lock_screen"
        private const val LOCKED_SOUND_MS = 8_000L
        private const val UNLOCKED_SOUND_MS = 60_000L
        private const val EMERGENCY_PAUSE_MS = 180_000L
        private const val WARNING_MS = 10_000L
        private const val VOLUME_CHECK_MS = 400L
        private const val TAMPER_DEBOUNCE_MS = 3_000L
        private const val MAX_PENALTY_MS = 30 * 60_000L
        private const val ACTION_START_SESSION = "io.github.wisnujayaa.rebahanguard.START_SESSION"
        private const val ACTION_STOP_SESSION = "io.github.wisnujayaa.rebahanguard.STOP_SESSION"
        private const val EXTRA_HABIT = "habit_id"
        private const val EXTRA_MINUTES = "minutes"
        private const val SESSION_CHANNEL_ID = "sessions"
        private const val SESSION_NOTIFICATION_ID = 2
        private const val NAG_EVERY_MS = 20 * 60_000L
        private const val FOCUS_DEADLINE_MS = 6 * 3_600_000L
        private const val FOCUS_CACHE_MS = 20_000L
        const val DEFAULT_DELAY_SEC = 20

        /** Must be called while the app is visible (Android's while-in-use camera rule). */
        fun start(context: Context, settings: GuardSettings, extra: Intent? = null) {
            // Without the camera permission startForeground() would throw, and Android kills an
            // app whose startForegroundService() never reaches the foreground.
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "Camera permission missing; not starting the guard")
                return
            }
            val intent = Intent(context, GuardService::class.java)
                .apply { if (extra != null) { action = extra.action; extra.extras?.let { putExtras(it) } } }
                .putExtra(EXTRA_DELAY_SEC, settings.delaySec)
                .putExtra(EXTRA_LYING_ELEVATION, settings.lyingElevationDeg)
                .putExtra(EXTRA_STRICT, settings.strictMode)
                .putExtra(EXTRA_ALARM_URI, settings.alarmSoundUri)
                .putExtra(EXTRA_LOCK, settings.lockScreen)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException if the app isn't visible.
                Log.w(TAG, "Could not start the guard", e)
            }
        }

        /** Starts (or joins) the guard with a session for [habitId]. Call while the app is visible. */
        fun startSession(context: Context, settings: GuardSettings, habitId: Long, minutes: Int) {
            start(context, settings, Intent().setAction(ACTION_START_SESSION).putExtra(EXTRA_HABIT, habitId).putExtra(EXTRA_MINUTES, minutes))
        }

        fun stopSession(context: Context) {
            try {
                context.startService(Intent(context, GuardService::class.java).setAction(ACTION_STOP_SESSION))
            } catch (e: Exception) {
                Log.w(TAG, "Could not stop the session", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GuardService::class.java))
        }
    }
}
