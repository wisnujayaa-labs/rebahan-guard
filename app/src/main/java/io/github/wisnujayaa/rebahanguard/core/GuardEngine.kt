package io.github.wisnujayaa.rebahanguard.core

data class GuardConfig(
    /** How long a suspicious pose must last before the camera is turned on. */
    val triggerDelayMs: Long = 20_000,
    /** After a negative camera check, wait this long before checking again (saves battery). */
    val cooldownMs: Long = 60_000,
    /** While locked, the user must stay out of a suspicious pose this long to unlock. */
    val releaseMs: Long = 1_500,
    /** Maximum time the camera stays on for one check. */
    val cameraWindowMs: Long = 4_000,
    /**
     * Watchdog: if the camera never reports back (driver hang, lost callback), give up after
     * this long instead of waiting forever. Must be longer than [cameraWindowMs].
     */
    val checkTimeoutMs: Long = 10_000,
    /**
     * Safety cap: one lockdown never lasts longer than this. If detection were ever wrong, the
     * user can't be locked out of their phone indefinitely.
     */
    val maxLockMs: Long = 300_000,
    /** In strict mode the camera re-checks this often while locked (gravity can't see sitting up). */
    val lockRecheckMs: Long = 5_000,
    /**
     * After being caught, turning the screen off and on again doesn't buy a fresh delay: for this
     * long, a suspicious pose is re-checked after only [relockDelayMs].
     */
    val relockWindowMs: Long = 600_000,
    val relockDelayMs: Long = 3_000,
    val minFaceWidthRatio: Float = 0.20f,
    /** A head tilted at least this far from vertical (in the screen plane) counts as lying. */
    val minHeadTiltDeg: Float = 45f,
    /** Screen elevation below this = "looking up at the phone" (see [Pose.FACE_DOWN]). */
    val lyingElevationDeg: Float = PoseClassifier.DEFAULT_LYING_ELEVATION_DEG,
    /**
     * Strict mode: also check with the camera while the phone is upright/tilted, so lying on
     * your side with the phone held upright is caught too. Costs more camera checks.
     */
    val strictMode: Boolean = false,
) {
    init {
        require(triggerDelayMs >= 0) { "triggerDelayMs must be >= 0" }
        require(cooldownMs >= 0) { "cooldownMs must be >= 0" }
        require(releaseMs >= 0) { "releaseMs must be >= 0" }
        require(cameraWindowMs > 0) { "cameraWindowMs must be > 0" }
        require(checkTimeoutMs > cameraWindowMs) { "checkTimeoutMs must exceed cameraWindowMs" }
        require(maxLockMs > 0) { "maxLockMs must be > 0" }
        require(lockRecheckMs in 1..maxLockMs) { "lockRecheckMs must be in (0, maxLockMs]" }
        require(relockWindowMs >= 0) { "relockWindowMs must be >= 0" }
        require(relockDelayMs in 0..triggerDelayMs) { "relockDelayMs must be in [0, triggerDelayMs]" }
        require(minFaceWidthRatio.isFinite() && minFaceWidthRatio > 0f && minFaceWidthRatio <= 1f) {
            "minFaceWidthRatio must be in (0, 1]"
        }
        require(minHeadTiltDeg.isFinite() && minHeadTiltDeg > 0f && minHeadTiltDeg <= 90f) {
            "minHeadTiltDeg must be in (0, 90]"
        }
        require(
            lyingElevationDeg.isFinite() &&
                lyingElevationDeg >= SensorInput.MIN_LYING_ELEVATION_DEG &&
                lyingElevationDeg <= SensorInput.MAX_LYING_ELEVATION_DEG
        ) { "lyingElevationDeg out of range" }
    }

    /** Whether holding the phone like this should start the countdown to a camera check. */
    fun isSuspicious(pose: Pose): Boolean =
        pose.isSuspicious || (strictMode && pose in STRICT_EXTRA_POSES)

    private companion object {
        val STRICT_EXTRA_POSES = setOf(Pose.UPRIGHT, Pose.UPSIDE_DOWN, Pose.TILTED)
    }
}

enum class Phase {
    /** Only the cheap gravity sensor is running. */
    WATCHING,

    /** The front camera is looking for a face. */
    CHECKING,

    /** The user was caught lying down: the phone is locked (and the alarm sounds). */
    ALARMING,

    /** A check came back negative (or the lock hit its limit); waiting before the next check. */
    COOLDOWN,
}

/**
 * Side effects the Android layer must perform. The engine itself never touches hardware.
 *
 * STOP_ALARM means "unlock, silence, and cancel any camera check still running".
 */
enum class Action { NONE, START_CAMERA_CHECK, CANCEL_CAMERA_CHECK, START_ALARM, STOP_ALARM }

/**
 * The whole decision process as a pure state machine (no Android imports), so it can be
 * unit-tested with fake timestamps.
 *
 * WATCHING ──(suspicious pose held ≥ delay*)──▶ CHECKING
 * CHECKING ──(face says lying)──▶ ALARMING (locked)
 * CHECKING ──(no face / not lying / watchdog timeout)──▶ COOLDOWN
 * ALARMING ──(normal pose held ≥ release)──▶ WATCHING            (unlocked: user sat up)
 * ALARMING ──(strict lock: camera re-check says not lying)──▶ COOLDOWN
 * ALARMING ──(locked for maxLock)──▶ COOLDOWN                    (safety cap)
 * COOLDOWN ──(cooldown over, or user sat up)──▶ WATCHING
 * any      ──(screen off / phone call)──▶ WATCHING
 *
 * *delay = relockDelay instead of triggerDelay within relockWindow of the last catch.
 *
 * Guarantees (checked by randomized tests): the alarm only starts after a positive camera check,
 * every START_ALARM is followed by exactly one STOP_ALARM, the camera is never started twice at
 * once, and nothing stays on forever.
 */
class GuardEngine(private val config: GuardConfig = GuardConfig()) {
    var phase: Phase = Phase.WATCHING
        private set
    var lastOrientation: Orientation = Orientation.UNKNOWN
        private set
    val lastPose: Pose get() = lastOrientation.pose

    /** True while a strict-mode lock is waiting for a camera re-check result. */
    var lockRecheckInFlight = false
        private set

    private val trigger = Debouncer(config.triggerDelayMs)
    private val release = Debouncer(config.releaseMs)
    private var cooldownUntilMs = 0L
    private var phaseStartedMs = 0L

    /** The current lock was caught in a strict-mode-only pose (e.g. phone upright). */
    private var strictLock = false
    private var lastLockCheckMs = 0L
    private var recheckStartedMs = 0L

    /** When the user was last caught lying. Survives screen-off on purpose. */
    private var lastCaughtMs: Long? = null

    fun onPose(pose: Pose, nowMs: Long): Action = onPose(Orientation.of(pose), nowMs)

    fun onPose(orientation: Orientation, nowMs: Long): Action {
        lastOrientation = orientation
        val suspicious = config.isSuspicious(orientation.pose)

        return when (phase) {
            Phase.WATCHING -> {
                if (trigger.update(suspicious, nowMs, currentTriggerDelay(nowMs))) {
                    enter(Phase.CHECKING, nowMs)
                    Action.START_CAMERA_CHECK
                } else {
                    Action.NONE
                }
            }

            Phase.CHECKING -> {
                if (elapsedSince(phaseStartedMs, nowMs) >= config.checkTimeoutMs) {
                    // Camera never answered. Don't stay blind forever: give up and retry later.
                    enterCooldown(nowMs)
                    Action.CANCEL_CAMERA_CHECK
                } else {
                    Action.NONE // waiting for onFaceResult
                }
            }

            Phase.ALARMING -> onPoseWhileLocked(orientation.pose, nowMs)

            Phase.COOLDOWN -> {
                if (!suspicious) {
                    // User sat up: re-arm immediately so lying down again is caught.
                    enter(Phase.WATCHING, nowMs)
                } else if (nowMs >= cooldownUntilMs) {
                    enter(Phase.WATCHING, nowMs)
                    trigger.update(true, nowMs, currentTriggerDelay(nowMs)) // count from now
                }
                Action.NONE
            }
        }
    }

    private fun onPoseWhileLocked(pose: Pose, nowMs: Long): Action {
        // Unlock as soon as the phone is held in a way that can't be lying. For a normal lock that
        // means leaving the lying poses; for a strict lock (caught with the phone upright) it
        // means e.g. putting the phone down flat — sitting up is confirmed by the camera instead.
        val calm = if (strictLock) !config.isSuspicious(pose) else !pose.isSuspicious

        return when {
            release.update(calm, nowMs) -> {
                enter(Phase.WATCHING, nowMs)
                Action.STOP_ALARM
            }

            elapsedSince(phaseStartedMs, nowMs) >= config.maxLockMs -> {
                enterCooldown(nowMs)
                Action.STOP_ALARM
            }

            strictLock && !lockRecheckInFlight &&
                elapsedSince(lastLockCheckMs, nowMs) >= config.lockRecheckMs -> {
                lockRecheckInFlight = true
                recheckStartedMs = nowMs
                Action.START_CAMERA_CHECK
            }

            lockRecheckInFlight && elapsedSince(recheckStartedMs, nowMs) >= config.checkTimeoutMs -> {
                // Camera didn't answer. Stay locked (bounded by maxLock) and try again later.
                lockRecheckInFlight = false
                lastLockCheckMs = nowMs
                Action.CANCEL_CAMERA_CHECK
            }

            else -> Action.NONE
        }
    }

    fun onFaceResult(face: FaceObservation?, nowMs: Long): Action {
        val lying = LyingJudge.isLying(lastOrientation, face, config)
        return when {
            phase == Phase.CHECKING -> if (lying) {
                strictLock = !lastOrientation.pose.isSuspicious
                lastCaughtMs = nowMs
                enter(Phase.ALARMING, nowMs)
                lastLockCheckMs = nowMs
                Action.START_ALARM
            } else {
                enterCooldown(nowMs)
                Action.NONE
            }

            phase == Phase.ALARMING && lockRecheckInFlight -> {
                lockRecheckInFlight = false
                lastLockCheckMs = nowMs
                if (lying) {
                    lastCaughtMs = nowMs
                    Action.NONE // still lying: stay locked
                } else {
                    enterCooldown(nowMs) // head is upright again: unlock
                    Action.STOP_ALARM
                }
            }

            // Late, duplicate or unexpected results (e.g. after a screen-off) are ignored.
            else -> Action.NONE
        }
    }

    /** Screen off (or a phone call) = the user stopped using the phone, which is the goal. */
    fun onScreenOff(): Action {
        val action = when (phase) {
            Phase.ALARMING -> Action.STOP_ALARM
            Phase.CHECKING -> Action.CANCEL_CAMERA_CHECK
            else -> Action.NONE
        }
        trigger.reset()
        release.reset()
        lockRecheckInFlight = false
        phase = Phase.WATCHING
        lastOrientation = Orientation.UNKNOWN
        return action
    }

    private fun currentTriggerDelay(nowMs: Long): Long {
        val caught = lastCaughtMs ?: return config.triggerDelayMs
        val since = nowMs - caught
        return if (since >= 0 && since < config.relockWindowMs) config.relockDelayMs else config.triggerDelayMs
    }

    private fun enter(next: Phase, nowMs: Long) {
        trigger.reset()
        release.reset()
        lockRecheckInFlight = false
        phase = next
        phaseStartedMs = nowMs
    }

    private fun enterCooldown(nowMs: Long) {
        enter(Phase.COOLDOWN, nowMs)
        // Saturating add: a timestamp near Long.MAX_VALUE must not wrap around to negative,
        // which would silently skip the cooldown.
        cooldownUntilMs =
            if (nowMs > Long.MAX_VALUE - config.cooldownMs) Long.MAX_VALUE else nowMs + config.cooldownMs
    }

    /** Time since [startMs]; a clock that jumped backwards counts as zero. */
    private fun elapsedSince(startMs: Long, nowMs: Long): Long = (nowMs - startMs).coerceAtLeast(0)
}
