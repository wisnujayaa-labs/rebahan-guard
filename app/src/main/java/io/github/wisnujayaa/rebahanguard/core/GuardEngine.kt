package io.github.wisnujayaa.rebahanguard.core

data class GuardConfig(
    /** How long a suspicious pose must last before the camera is turned on. */
    val triggerDelayMs: Long = 20_000,
    /** After a negative camera check, wait this long before checking again (saves battery). */
    val cooldownMs: Long = 60_000,
    /** While the alarm rings, the user must stay out of a suspicious pose this long to stop it. */
    val releaseMs: Long = 1_500,
    /** Maximum time the camera stays on for one check. */
    val cameraWindowMs: Long = 4_000,
    /**
     * Watchdog: if the camera never reports back (driver hang, lost callback), give up after
     * this long instead of waiting forever. Must be longer than [cameraWindowMs].
     */
    val checkTimeoutMs: Long = 10_000,
    /** The alarm never rings longer than this in one go (protects roommates and the battery). */
    val maxAlarmMs: Long = 60_000,
    val minFaceWidthRatio: Float = 0.20f,
    val maxSidewaysRollDeg: Float = 45f,
) {
    init {
        require(triggerDelayMs >= 0) { "triggerDelayMs must be >= 0" }
        require(cooldownMs >= 0) { "cooldownMs must be >= 0" }
        require(releaseMs >= 0) { "releaseMs must be >= 0" }
        require(cameraWindowMs > 0) { "cameraWindowMs must be > 0" }
        require(checkTimeoutMs > cameraWindowMs) { "checkTimeoutMs must exceed cameraWindowMs" }
        require(maxAlarmMs > 0) { "maxAlarmMs must be > 0" }
        require(minFaceWidthRatio.isFinite() && minFaceWidthRatio > 0f && minFaceWidthRatio <= 1f) {
            "minFaceWidthRatio must be in (0, 1]"
        }
        require(maxSidewaysRollDeg.isFinite() && maxSidewaysRollDeg >= 0f && maxSidewaysRollDeg < 90f) {
            "maxSidewaysRollDeg must be in [0, 90)"
        }
    }
}

enum class Phase {
    /** Only the cheap gravity sensor is running. */
    WATCHING,

    /** The front camera is looking for a face. */
    CHECKING,

    /** The user was caught lying down: alarm is ringing. */
    ALARMING,

    /** A check came back negative (or the alarm hit its limit); waiting before the next check. */
    COOLDOWN,
}

/** Side effects the Android layer must perform. The engine itself never touches hardware. */
enum class Action { NONE, START_CAMERA_CHECK, CANCEL_CAMERA_CHECK, START_ALARM, STOP_ALARM }

/**
 * The whole decision process as a pure state machine (no Android imports), so it can be
 * unit-tested with fake timestamps.
 *
 * WATCHING ──(suspicious pose held ≥ triggerDelay)──▶ CHECKING
 * CHECKING ──(face says lying)──▶ ALARMING
 * CHECKING ──(no face / not lying / watchdog timeout)──▶ COOLDOWN
 * ALARMING ──(normal pose held ≥ release)──▶ WATCHING
 * ALARMING ──(rang for maxAlarm)──▶ COOLDOWN
 * COOLDOWN ──(cooldown over, or user sat up)──▶ WATCHING
 * any      ──(screen off)──▶ WATCHING
 *
 * Guarantees (checked by randomized tests): the camera is never started twice at once, the
 * alarm only starts after a positive camera check, every START_ALARM is followed by exactly one
 * STOP_ALARM, and the engine can never stay stuck in CHECKING or ALARMING.
 */
class GuardEngine(private val config: GuardConfig = GuardConfig()) {
    var phase: Phase = Phase.WATCHING
        private set
    var lastPose: Pose = Pose.UNKNOWN
        private set

    private val trigger = Debouncer(config.triggerDelayMs)
    private val release = Debouncer(config.releaseMs)
    private var cooldownUntilMs = 0L
    private var phaseStartedMs = 0L

    fun onPose(pose: Pose, nowMs: Long): Action {
        lastPose = pose
        val suspicious = pose.isSuspicious

        return when (phase) {
            Phase.WATCHING -> {
                if (trigger.update(suspicious, nowMs)) {
                    enter(Phase.CHECKING, nowMs)
                    Action.START_CAMERA_CHECK
                } else {
                    Action.NONE
                }
            }

            Phase.CHECKING -> {
                if (elapsedInPhase(nowMs) >= config.checkTimeoutMs) {
                    // Camera never answered. Don't stay blind forever: give up and retry later.
                    enterCooldown(nowMs)
                    Action.CANCEL_CAMERA_CHECK
                } else {
                    Action.NONE // waiting for onFaceResult
                }
            }

            Phase.ALARMING -> {
                when {
                    release.update(!suspicious, nowMs) -> {
                        enter(Phase.WATCHING, nowMs)
                        Action.STOP_ALARM
                    }
                    elapsedInPhase(nowMs) >= config.maxAlarmMs -> {
                        enterCooldown(nowMs)
                        Action.STOP_ALARM
                    }
                    else -> Action.NONE
                }
            }

            Phase.COOLDOWN -> {
                if (!suspicious) {
                    // User sat up: re-arm immediately so lying down again is caught.
                    enter(Phase.WATCHING, nowMs)
                } else if (nowMs >= cooldownUntilMs) {
                    enter(Phase.WATCHING, nowMs)
                    trigger.update(true, nowMs) // start counting the delay again from now
                }
                Action.NONE
            }
        }
    }

    fun onFaceResult(face: FaceObservation?, nowMs: Long): Action {
        // Late, duplicate or unexpected results (e.g. after a screen-off) are ignored.
        if (phase != Phase.CHECKING) return Action.NONE

        return if (LyingJudge.isLying(lastPose, face, config)) {
            enter(Phase.ALARMING, nowMs)
            Action.START_ALARM
        } else {
            enterCooldown(nowMs)
            Action.NONE
        }
    }

    /** Screen off = the user stopped using the phone, which is exactly the goal. */
    fun onScreenOff(): Action {
        val action = when (phase) {
            Phase.ALARMING -> Action.STOP_ALARM
            Phase.CHECKING -> Action.CANCEL_CAMERA_CHECK
            else -> Action.NONE
        }
        trigger.reset()
        release.reset()
        phase = Phase.WATCHING
        lastPose = Pose.UNKNOWN
        return action
    }

    private fun enter(next: Phase, nowMs: Long) {
        trigger.reset()
        release.reset()
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

    /** Time spent in the current phase; a clock that jumped backwards counts as zero. */
    private fun elapsedInPhase(nowMs: Long): Long = (nowMs - phaseStartedMs).coerceAtLeast(0)
}
