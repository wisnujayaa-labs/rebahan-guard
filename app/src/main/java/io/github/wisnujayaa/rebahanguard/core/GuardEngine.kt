package io.github.wisnujayaa.rebahanguard.core

data class GuardConfig(
    /** How long a suspicious pose must last before the camera is turned on. */
    val triggerDelayMs: Long = 20_000,
    /** After a negative camera check, wait this long before checking again (saves battery). */
    val cooldownMs: Long = 60_000,
    /** While locked, the user must stay out of a suspicious pose this long to unlock. */
    val releaseMs: Long = 1_500,
    /** Maximum time the camera stays on for one check. */
    val cameraWindowMs: Long = 6_000,
    /**
     * Watchdog: if the camera never reports back (driver hang, lost callback), give up after
     * this long instead of waiting forever. Must be longer than [cameraWindowMs].
     */
    val checkTimeoutMs: Long = 12_000,
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
    /**
     * Below this screen elevation, the pose alone is enough to lock even if the camera sees no
     * face (dark room, face out of frame): see [Evidence.STRONG].
     */
    val strongElevationDeg: Float = -30f,
    /** A MEDIUM pose with repeatedly no usable face locks after this long. */
    val unconfirmedLockMs: Long = 120_000,
    /** After a check without evidence, look again sooner than after a clear "sitting" result. */
    val noEvidenceCooldownMs: Long = 20_000,
    /** A gap this long without any suspicious pose forgets the unconfirmed suspicion. */
    val unconfirmedResetGapMs: Long = 300_000,
    val minFaceWidthRatio: Float = 0.15f,
    /** A head tilted at least this far from vertical (in the screen plane) counts as lying. */
    val minHeadTiltDeg: Float = 45f,
    /** Screen elevation below this = "looking up at the phone" (see [Pose.FACE_DOWN]). */
    val lyingElevationDeg: Float = PoseClassifier.DEFAULT_LYING_ELEVATION_DEG,
    /**
     * Strict mode: also check with the camera while the phone is upright/tilted, so lying on
     * your side with the phone held upright is caught too. Costs more camera checks.
     */
    val strictMode: Boolean = false,
    /** Prone detection threshold (screen elevation); NaN = off. */
    val proneElevationDeg: Float = PoseClassifier.DEFAULT_PRONE_ELEVATION_DEG,
    /**
     * Hysteresis: after a prone lock, the phone must come this far below the threshold (or be
     * put down) to unlock — tilting it a few degrees while still lying doesn't count.
     */
    val proneReleaseMarginDeg: Float = 20f,
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
        require(strongElevationDeg.isFinite() && strongElevationDeg in -90f..0f) { "strongElevationDeg must be in [-90, 0]" }
        require(unconfirmedLockMs > 0) { "unconfirmedLockMs must be > 0" }
        require(noEvidenceCooldownMs >= 0) { "noEvidenceCooldownMs must be >= 0" }
        require(unconfirmedResetGapMs > 0) { "unconfirmedResetGapMs must be > 0" }
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
        require(proneElevationDeg.isNaN() || proneElevationDeg in SensorInput.MIN_PRONE_ELEVATION_DEG..SensorInput.MAX_PRONE_ELEVATION_DEG) {
            "proneElevationDeg out of range"
        }
        require(proneReleaseMarginDeg.isFinite() && proneReleaseMarginDeg in 0f..45f) { "proneReleaseMarginDeg out of range" }
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

/** Why the phone was locked (shown to the user and counted in statistics). */
enum class LockReason {
    /** The camera saw a lying head. */
    CAMERA_CONFIRMED,

    /** Screen clearly facing the floor; the camera saw nothing usable. */
    STRONG_POSE,

    /** A lying pose kept going for minutes while the camera kept failing. */
    PERSISTENT_SUSPICION,
}

/**
 * The whole decision process as a pure state machine (no Android imports), so it can be
 * unit-tested with fake timestamps.
 *
 * WATCHING ──(suspicious pose held ≥ delay*)──▶ CHECKING
 * CHECKING ──(face says lying)──▶ ALARMING (locked)
 * CHECKING ──(no face, but STRONG pose or MEDIUM pose for unconfirmedLock)──▶ ALARMING (locked)
 * CHECKING ──(not lying / no evidence otherwise / watchdog timeout)──▶ COOLDOWN
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

    /** First camera check that found no evidence during the current stretch of suspicion. */
    private var unconfirmedSinceMs: Long? = null
    private var lastSuspiciousMs: Long? = null

    /** Why the current/last lock happened. */
    var lastLockReason: LockReason? = null
        private set

    /** The current lock happened shortly after a previous one (no warning grace period). */
    var lastLockWasRepeat: Boolean = false
        private set

    fun onPose(pose: Pose, nowMs: Long): Action = onPose(Orientation.of(pose), nowMs)

    fun onPose(orientation: Orientation, nowMs: Long): Action {
        lastOrientation = orientation
        val suspicious = config.isSuspicious(orientation.pose)
        trackSuspicion(orientation.pose, nowMs)

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

            Phase.ALARMING -> onPoseWhileLocked(orientation, nowMs)

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

    private fun onPoseWhileLocked(orientation: Orientation, nowMs: Long): Action {
        val pose = orientation.pose
        // Unlock as soon as the phone is held in a way that can't be lying. For a normal lock that
        // means leaving the lying poses; for a strict lock (caught with the phone upright) it
        // means e.g. putting the phone down flat — sitting up is confirmed by the camera instead.
        val calm = when {
            proneLock -> isCalmAfterProne(orientation)
            strictLock -> !config.isSuspicious(pose)
            else -> !pose.isSuspicious
        }

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
        val verdict = LyingJudge.verdict(lastOrientation, face, config)
        return when {
            phase == Phase.CHECKING -> when (verdict) {
                Verdict.LYING -> lock(LockReason.CAMERA_CONFIRMED, nowMs)
                Verdict.NOT_LYING -> {
                    unconfirmedSinceMs = null
                    enterCooldown(nowMs)
                    Action.NONE
                }
                Verdict.NO_EVIDENCE -> onNoEvidence(nowMs)
            }

            phase == Phase.ALARMING && lockRecheckInFlight -> {
                lockRecheckInFlight = false
                lastLockCheckMs = nowMs
                // The prone rule can only make releasing harder, never easier: rolling onto your
                // back after a prone lock must not count as "sitting up".
                val released = verdict == Verdict.NOT_LYING && (!proneLock || proneRecheckReleases(face))
                if (released) {
                    enterCooldown(nowMs) // head is upright again: unlock
                    Action.STOP_ALARM
                } else {
                    lastCaughtMs = nowMs
                    Action.NONE // still lying, or can't tell: stay locked (bounded by maxLock)
                }
            }

            // Late, duplicate or unexpected results (e.g. after a screen-off) are ignored.
            else -> Action.NONE
        }
    }

    /** The camera saw nothing usable. Decide from how strong the gravity evidence is. */
    private fun onNoEvidence(nowMs: Long): Action = when (LyingJudge.evidence(lastOrientation, config)) {
        Evidence.STRONG -> lock(LockReason.STRONG_POSE, nowMs)
        Evidence.MEDIUM -> {
            val since = unconfirmedSinceMs ?: nowMs.also { unconfirmedSinceMs = it }
            if (nowMs - since >= config.unconfirmedLockMs) {
                lock(LockReason.PERSISTENT_SUSPICION, nowMs)
            } else {
                enterCooldown(nowMs, config.noEvidenceCooldownMs)
                Action.NONE
            }
        }
        Evidence.WEAK, Evidence.NONE -> {
            enterCooldown(nowMs)
            Action.NONE
        }
    }

    /**
     * After being caught lying prone: calm only once the phone is clearly lowered (sitting up
     * to look at it) or put down still — not merely tilted a little below the threshold.
     */
    private fun isCalmAfterProne(o: Orientation): Boolean {
        if (o.pose == Pose.RESTING) return true
        val e = o.screenElevationDeg
        if (!e.isFinite()) return false
        val threshold = config.proneElevationDeg.takeIf { it.isFinite() } ?: return !o.pose.isSuspicious
        if (o.pose == Pose.FACE_UP && e >= threshold) return true // still on a surface
        return !o.pose.isSuspicious && e < threshold - config.proneReleaseMarginDeg
    }

    private var proneLock = false

    /**
     * A recheck during a prone lock judges by the prone rule whatever the phone's pose now:
     * still facing the phone squarely while it is held steeply = still lying. Only a face seen
     * at an angle (sitting up) releases it.
     */
    private fun proneRecheckReleases(face: FaceObservation?): Boolean {
        if (face == null || !face.isValid || face.faceWidthRatio < config.minFaceWidthRatio) return false
        val e = lastOrientation.screenElevationDeg
        val threshold = config.proneElevationDeg
        val stillSteep = e.isFinite() && threshold.isFinite() && e >= threshold - config.proneReleaseMarginDeg
        return !(face.isFrontal && stillSteep)
    }

    private fun lock(reason: LockReason, nowMs: Long): Action {
        proneLock = lastOrientation.pose == Pose.PRONE
        val previous = lastCaughtMs
        lastLockWasRepeat = previous != null && nowMs - previous in 0 until config.relockWindowMs
        lastLockReason = reason
        strictLock = !lastOrientation.pose.isSuspicious || proneLock
        lastCaughtMs = nowMs
        unconfirmedSinceMs = null
        enter(Phase.ALARMING, nowMs)
        lastLockCheckMs = nowMs
        return Action.START_ALARM
    }

    private fun trackSuspicion(pose: Pose, nowMs: Long) {
        if (pose.isSuspicious) {
            val last = lastSuspiciousMs
            if (last != null && nowMs - last > config.unconfirmedResetGapMs) unconfirmedSinceMs = null
            lastSuspiciousMs = nowMs
        } else if (pose != Pose.UNKNOWN) {
            unconfirmedSinceMs = null // the user is holding the phone like someone sitting up
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

    private fun enterCooldown(nowMs: Long, durationMs: Long = config.cooldownMs) {
        enter(Phase.COOLDOWN, nowMs)
        // Saturating add: a timestamp near Long.MAX_VALUE must not wrap around to negative,
        // which would silently skip the cooldown.
        cooldownUntilMs = if (nowMs > Long.MAX_VALUE - durationMs) Long.MAX_VALUE else nowMs + durationMs
    }

    /** Time since [startMs]; a clock that jumped backwards counts as zero. */
    private fun elapsedSince(startMs: Long, nowMs: Long): Long = (nowMs - startMs).coerceAtLeast(0)
}
