package io.github.wisnujayaa.rebahanguard.core

data class GuardConfig(
    /** How long a suspicious pose must last before the camera is turned on. */
    val triggerDelayMs: Long = 20_000,
    /** After a negative camera check, wait this long before checking again (saves battery). */
    val cooldownMs: Long = 60_000,
    /** While the alarm rings, the user must stay out of a suspicious pose this long to stop it. */
    val releaseMs: Long = 1_500,
    /** Maximum time the camera stays on for one check. */
    val cameraWindowMs: Long = 3_000,
    val minFaceWidthRatio: Float = 0.20f,
    val maxSidewaysRollDeg: Float = 45f,
)

enum class Phase {
    /** Only the cheap gravity sensor is running. */
    WATCHING,

    /** The front camera is looking for a face. */
    CHECKING,

    /** The user was caught lying down: alarm is ringing. */
    ALARMING,

    /** A check came back negative; waiting before the next one. */
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
 * CHECKING ──(no face / not lying)──▶ COOLDOWN
 * ALARMING ──(normal pose held ≥ release)──▶ WATCHING
 * COOLDOWN ──(cooldown over, or user sat up)──▶ WATCHING
 * any      ──(screen off)──▶ WATCHING
 */
class GuardEngine(private val config: GuardConfig = GuardConfig()) {
    var phase: Phase = Phase.WATCHING
        private set
    var lastPose: Pose = Pose.UNKNOWN
        private set

    private val trigger = Debouncer(config.triggerDelayMs)
    private val release = Debouncer(config.releaseMs)
    private var cooldownUntilMs = 0L

    fun onPose(pose: Pose, nowMs: Long): Action {
        lastPose = pose
        val suspicious = pose.isSuspicious

        return when (phase) {
            Phase.WATCHING -> {
                if (trigger.update(suspicious, nowMs)) {
                    trigger.reset()
                    phase = Phase.CHECKING
                    Action.START_CAMERA_CHECK
                } else {
                    Action.NONE
                }
            }

            Phase.CHECKING -> Action.NONE // waiting for onFaceResult

            Phase.ALARMING -> {
                if (release.update(!suspicious, nowMs)) {
                    release.reset()
                    trigger.reset()
                    phase = Phase.WATCHING
                    Action.STOP_ALARM
                } else {
                    Action.NONE
                }
            }

            Phase.COOLDOWN -> {
                if (!suspicious) {
                    // User sat up: re-arm immediately so lying down again is caught.
                    trigger.reset()
                    phase = Phase.WATCHING
                } else if (nowMs >= cooldownUntilMs) {
                    trigger.reset()
                    trigger.update(true, nowMs) // start counting the delay again from now
                    phase = Phase.WATCHING
                }
                Action.NONE
            }
        }
    }

    fun onFaceResult(face: FaceObservation?, nowMs: Long): Action {
        if (phase != Phase.CHECKING) return Action.NONE

        return if (LyingJudge.isLying(lastPose, face, config)) {
            release.reset()
            phase = Phase.ALARMING
            Action.START_ALARM
        } else {
            cooldownUntilMs = nowMs + config.cooldownMs
            phase = Phase.COOLDOWN
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
        return action
    }
}
