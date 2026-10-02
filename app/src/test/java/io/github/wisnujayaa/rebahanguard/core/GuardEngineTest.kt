package io.github.wisnujayaa.rebahanguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DebouncerTest {
    @Test
    fun firesOnlyAfterHoldTime() {
        val d = Debouncer(holdMs = 1_000)
        assertFalse(d.update(true, 0))
        assertFalse(d.update(true, 999))
        assertTrue(d.update(true, 1_000))
    }

    @Test
    fun interruptionRestartsTheClock() {
        val d = Debouncer(holdMs = 1_000)
        d.update(true, 0)
        d.update(false, 500)
        assertFalse(d.update(true, 1_200))
        assertTrue(d.update(true, 2_200))
    }

    @Test
    fun zeroHold_firesImmediately() = assertTrue(Debouncer(holdMs = 0).update(true, 123))

    @Test
    fun clockGoingBackwards_restartsInsteadOfFiringEarly() {
        val d = Debouncer(holdMs = 1_000)
        d.update(true, 10_000)
        assertFalse(d.update(true, 5_000)) // clock jumped back 5 s
        assertFalse(d.update(true, 5_999))
        assertTrue(d.update(true, 6_000))
    }

    @Test
    fun reset_forgetsProgress() {
        val d = Debouncer(holdMs = 1_000)
        d.update(true, 0)
        d.reset()
        assertFalse(d.update(true, 1_500))
    }

    @Test
    fun negativeHold_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { Debouncer(holdMs = -1) }
    }
}

class GuardConfigTest {
    @Test
    fun defaults_areValid() {
        GuardConfig()
    }

    @Test
    fun nonsenseValues_areRejected() {
        val bad: List<() -> GuardConfig> = listOf(
            { GuardConfig(triggerDelayMs = -1) },
            { GuardConfig(cooldownMs = -1) },
            { GuardConfig(releaseMs = -1) },
            { GuardConfig(cameraWindowMs = 0) },
            { GuardConfig(cameraWindowMs = 5_000, checkTimeoutMs = 5_000) },
            { GuardConfig(maxLockMs = 0) },
            { GuardConfig(relockWindowMs = -1) },
            { GuardConfig(triggerDelayMs = 2_000, relockDelayMs = 3_000) },
            { GuardConfig(minFaceWidthRatio = 0f) },
            { GuardConfig(minFaceWidthRatio = 1.5f) },
            { GuardConfig(minFaceWidthRatio = Float.NaN) },
            { GuardConfig(minHeadTiltDeg = 0f) },
            { GuardConfig(minHeadTiltDeg = 91f) },
            { GuardConfig(minHeadTiltDeg = Float.NaN) },
            { GuardConfig(lyingElevationDeg = Float.NaN) },
            { GuardConfig(lyingElevationDeg = -61f) },
            { GuardConfig(lyingElevationDeg = 31f) },
            { GuardConfig(lockRecheckMs = 0) },
            { GuardConfig(maxLockMs = 10_000, lockRecheckMs = 20_000) },
        )
        for (make in bad) {
            assertThrows(IllegalArgumentException::class.java) { make() }
        }
    }
}

class GuardEngineTest {
    private val config = GuardConfig(
        triggerDelayMs = 10_000,
        cooldownMs = 60_000,
        releaseMs = 1_500,
        cameraWindowMs = 3_000,
        checkTimeoutMs = 10_000,
        maxLockMs = 300_000,
        relockWindowMs = 600_000,
        relockDelayMs = 3_000,
    )
    private val lyingFace = FaceObservation(0.4f, 5f)

    /** Holds [pose] from [from] until the camera check starts; returns that timestamp. */
    private fun GuardEngine.triggerCheck(pose: Pose, from: Long): Long {
        onPose(pose, from)
        assertEquals(Action.START_CAMERA_CHECK, onPose(pose, from + config.triggerDelayMs))
        return from + config.triggerDelayMs
    }

    // ------------------------------------------------------------ happy paths

    @Test
    fun fullCycle_alarmThenStopWhenUserSitsUp() {
        val e = GuardEngine(config)
        assertEquals(Action.NONE, e.onPose(Pose.SIDEWAYS, 0))
        assertEquals(Action.NONE, e.onPose(Pose.SIDEWAYS, 9_999))
        assertEquals(Action.START_CAMERA_CHECK, e.onPose(Pose.SIDEWAYS, 10_000))
        assertEquals(Phase.CHECKING, e.phase)

        assertEquals(Action.START_ALARM, e.onFaceResult(lyingFace, 10_500))
        assertEquals(Phase.ALARMING, e.phase)

        assertEquals(Action.NONE, e.onPose(Pose.UPRIGHT, 11_000))
        assertEquals(Action.STOP_ALARM, e.onPose(Pose.UPRIGHT, 12_500))
        assertEquals(Phase.WATCHING, e.phase)
    }

    @Test
    fun negativeCheck_waitsForCooldownThenDelayAgain() {
        val e = GuardEngine(config)
        e.triggerCheck(Pose.SIDEWAYS, 0)
        assertEquals(Action.NONE, e.onFaceResult(null, 10_500))
        assertEquals(Phase.COOLDOWN, e.phase)

        assertEquals(Action.NONE, e.onPose(Pose.SIDEWAYS, 30_000))
        assertEquals(Phase.COOLDOWN, e.phase)

        assertEquals(Action.NONE, e.onPose(Pose.SIDEWAYS, 70_500)) // cooldown over, re-arm
        assertEquals(Phase.WATCHING, e.phase)
        assertEquals(Action.NONE, e.onPose(Pose.SIDEWAYS, 80_499))
        assertEquals(Action.START_CAMERA_CHECK, e.onPose(Pose.SIDEWAYS, 80_500))
    }

    @Test
    fun sittingUpDuringCooldown_reArmsImmediately() {
        val e = GuardEngine(config)
        e.triggerCheck(Pose.SIDEWAYS, 0)
        e.onFaceResult(null, 10_500)
        e.onPose(Pose.UPRIGHT, 11_000)
        assertEquals(Phase.WATCHING, e.phase)
        e.onPose(Pose.FACE_DOWN, 12_000)
        assertEquals(Action.START_CAMERA_CHECK, e.onPose(Pose.FACE_DOWN, 22_000))
    }

    // ------------------------------------------------------------ unexpected situations

    @Test
    fun poseChangeDuringCheck_isNotLying() {
        val e = GuardEngine(config)
        e.triggerCheck(Pose.SIDEWAYS, 0)
        e.onPose(Pose.UPRIGHT, 10_200) // user sat up while camera was looking
        assertEquals(Action.NONE, e.onFaceResult(lyingFace, 10_500))
    }

    @Test
    fun screenOff_stopsAlarmAndCancelsCamera() {
        val e = GuardEngine(config)
        e.triggerCheck(Pose.FACE_DOWN, 0)
        assertEquals(Action.CANCEL_CAMERA_CHECK, e.onScreenOff())

        e.triggerCheck(Pose.FACE_DOWN, 20_000)
        e.onFaceResult(lyingFace, 30_500)
        assertEquals(Action.STOP_ALARM, e.onScreenOff())
        assertEquals(Phase.WATCHING, e.phase)
        assertEquals(Action.NONE, e.onScreenOff()) // twice in a row is harmless
    }

    @Test
    fun cameraNeverAnswers_watchdogGivesUp() {
        val e = GuardEngine(config)
        e.triggerCheck(Pose.SIDEWAYS, 0)
        assertEquals(Action.NONE, e.onPose(Pose.SIDEWAYS, 19_999))
        assertEquals(Action.CANCEL_CAMERA_CHECK, e.onPose(Pose.SIDEWAYS, 20_000))
        assertEquals(Phase.COOLDOWN, e.phase)
        // The lost result finally shows up: must be ignored, no alarm.
        assertEquals(Action.NONE, e.onFaceResult(lyingFace, 20_500))
        assertEquals(Phase.COOLDOWN, e.phase)
    }

    @Test
    fun lock_staysUntilTheUserSitsUp_evenForMinutes() {
        val e = GuardEngine(config)
        e.triggerCheck(Pose.FACE_DOWN, 0)
        assertEquals(Action.START_ALARM, e.onFaceResult(lyingFace, 10_000))
        var t = 10_000L
        while (t < 200_000) { // still lying 3 minutes later: still locked
            assertEquals(Action.NONE, e.onPose(Pose.FACE_DOWN, t))
            t += 1_000
        }
        assertEquals(Phase.ALARMING, e.phase)
        e.onPose(Pose.UPRIGHT, 200_000)
        assertEquals(Action.STOP_ALARM, e.onPose(Pose.UPRIGHT, 201_500))
    }

    @Test
    fun lock_neverLastsLongerThanTheSafetyCap() {
        val e = GuardEngine(config)
        e.triggerCheck(Pose.FACE_DOWN, 0)
        e.onFaceResult(lyingFace, 10_000)
        assertEquals(Action.NONE, e.onPose(Pose.FACE_DOWN, 309_999))
        assertEquals(Action.STOP_ALARM, e.onPose(Pose.FACE_DOWN, 310_000))
        assertEquals(Phase.COOLDOWN, e.phase)
    }

    @Test
    fun briefWobble_doesNotUnlock() {
        val e = GuardEngine(config)
        e.triggerCheck(Pose.FACE_DOWN, 0)
        e.onFaceResult(lyingFace, 10_000)
        e.onPose(Pose.UPRIGHT, 11_000) // phone tilted up for a moment...
        e.onPose(Pose.FACE_DOWN, 12_000) // ...and back: not sitting up
        assertEquals(Action.NONE, e.onPose(Pose.UPRIGHT, 12_600))
        assertEquals(Phase.ALARMING, e.phase)
    }

    // ------------------------------------------------------------ relock (anti-escape)

    @Test
    fun screenOffAndOnAgain_afterBeingCaught_relocksQuickly() {
        val e = GuardEngine(config)
        e.triggerCheck(Pose.FACE_DOWN, 0)
        e.onFaceResult(lyingFace, 10_000)
        assertEquals(Action.STOP_ALARM, e.onScreenOff()) // the "escape"
        e.onPose(Pose.FACE_DOWN, 20_000) // screen on again, still lying
        assertEquals(Action.NONE, e.onPose(Pose.FACE_DOWN, 22_999))
        assertEquals(Action.START_CAMERA_CHECK, e.onPose(Pose.FACE_DOWN, 23_000)) // 3 s, not 10 s
        assertEquals(Action.START_ALARM, e.onFaceResult(lyingFace, 23_500))
    }

    @Test
    fun relockWindow_expires() {
        val e = GuardEngine(config)
        e.triggerCheck(Pose.FACE_DOWN, 0)
        e.onFaceResult(lyingFace, 10_000)
        e.onScreenOff()
        val later = 10_000L + 600_000
        e.onPose(Pose.FACE_DOWN, later)
        assertEquals(Action.NONE, e.onPose(Pose.FACE_DOWN, later + 3_000))
        assertEquals(Action.START_CAMERA_CHECK, e.onPose(Pose.FACE_DOWN, later + 10_000))
    }

    @Test
    fun relock_onAMediumPose_stillNeedsTheCamera() {
        val e = GuardEngine(config)
        e.triggerCheck(Pose.SIDEWAYS, 0)
        e.onFaceResult(FaceObservation(0.4f, 3f), 10_000) // sideways phone, upright face: lying on side
        e.onScreenOff()
        e.onPose(Pose.SIDEWAYS, 20_000)
        e.onPose(Pose.SIDEWAYS, 23_000)
        assertEquals(Action.NONE, e.onFaceResult(null, 23_500)) // no face yet: not enough
        assertEquals(Phase.COOLDOWN, e.phase)
    }

    @Test
    fun faceResultWithoutACheck_isIgnored() {
        val e = GuardEngine(config)
        assertEquals(Action.NONE, e.onFaceResult(lyingFace, 0))
        assertEquals(Phase.WATCHING, e.phase)
    }

    @Test
    fun duplicateFaceResult_doesNotStartASecondAlarm() {
        val e = GuardEngine(config)
        e.triggerCheck(Pose.FACE_DOWN, 0)
        assertEquals(Action.START_ALARM, e.onFaceResult(lyingFace, 10_100))
        assertEquals(Action.NONE, e.onFaceResult(lyingFace, 10_200))
    }

    @Test
    fun garbageFaceResult_onAMediumPose_doesNotLockImmediately() {
        val e = GuardEngine(config)
        e.triggerCheck(Pose.SIDEWAYS, 0)
        assertEquals(Action.NONE, e.onFaceResult(FaceObservation(Float.NaN, 0f), 10_100))
        assertEquals(Phase.COOLDOWN, e.phase)
    }

    @Test
    fun flickeringPose_neverTriggers() {
        // Phone being turned in the hand: suspicious and normal readings alternate.
        val e = GuardEngine(config)
        var t = 0L
        repeat(1_000) { i ->
            val pose = if (i % 2 == 0) Pose.SIDEWAYS else Pose.UPRIGHT
            assertEquals(Action.NONE, e.onPose(pose, t))
            t += 200
        }
    }

    @Test
    fun singleGlitchReading_onlyDelaysDetection() {
        val e = GuardEngine(config)
        e.onPose(Pose.SIDEWAYS, 0)
        e.onPose(Pose.UNKNOWN, 5_000) // one bad sensor sample
        e.onPose(Pose.SIDEWAYS, 5_200)
        assertEquals(Action.NONE, e.onPose(Pose.SIDEWAYS, 10_000))
        assertEquals(Action.START_CAMERA_CHECK, e.onPose(Pose.SIDEWAYS, 15_200))
    }

    @Test
    fun lockScreenReadings_neverTrigger() {
        val e = GuardEngine(config)
        var t = 0L
        repeat(500) {
            assertEquals(Action.NONE, e.onPose(SensorInput.toPose(floatArrayOf(0f, 0f, -9.81f), true), t))
            t += 1_000
        }
    }

    @Test
    fun clockJumpingBackwards_doesNotCrashOrFireEarly() {
        val e = GuardEngine(config)
        e.onPose(Pose.SIDEWAYS, 100_000)
        assertEquals(Action.NONE, e.onPose(Pose.SIDEWAYS, 50_000))
        assertEquals(Action.NONE, e.onPose(Pose.SIDEWAYS, 59_999))
        assertEquals(Action.START_CAMERA_CHECK, e.onPose(Pose.SIDEWAYS, 60_000))
    }

    @Test
    fun hugeTimestamps_doNotOverflow() {
        val e = GuardEngine(config)
        val start = Long.MAX_VALUE - 1_000_000
        e.triggerCheck(Pose.FACE_DOWN, start)
        e.onFaceResult(lyingFace, start + 10_500)
        assertEquals(Phase.ALARMING, e.phase)

        // A negative check right at the edge must still respect the cooldown (no wrap-around).
        val e2 = GuardEngine(config)
        val t = Long.MAX_VALUE - 20_000
        e2.triggerCheck(Pose.SIDEWAYS, t)
        e2.onFaceResult(FaceObservation(0.4f, 88f), t + 10_000) // clearly sitting
        assertEquals(Action.NONE, e2.onPose(Pose.SIDEWAYS, t + 15_000))
        assertEquals(Phase.COOLDOWN, e2.phase)
    }

    // ------------------------------------------------------------ strict mode

    private val strict = config.copy(strictMode = true, lockRecheckMs = 5_000)
    private val sidewaysHead = FaceObservation(0.4f, 88f) // phone upright, face rotated → head sideways
    private val uprightHead = FaceObservation(0.4f, 3f)

    @Test
    fun normalMode_uprightPhone_neverChecks() {
        val e = GuardEngine(config)
        var t = 0L
        repeat(1_000) {
            assertEquals(Action.NONE, e.onPose(Pose.UPRIGHT, t))
            t += 1_000
        }
    }

    @Test
    fun strictMode_staysLocked_whileCameraStillSeesALyingHead() {
        val e = GuardEngine(strict)
        e.triggerCheck(Pose.UPRIGHT, 0)
        assertEquals(Action.START_ALARM, e.onFaceResult(sidewaysHead, 10_300))

        // Gravity can't see the user sit up (phone stays upright): the camera re-checks while
        // the phone stays locked.
        assertEquals(Action.NONE, e.onPose(Pose.UPRIGHT, 15_299))
        assertEquals(Action.START_CAMERA_CHECK, e.onPose(Pose.UPRIGHT, 15_300))
        assertTrue(e.lockRecheckInFlight)
        assertEquals(Action.NONE, e.onPose(Pose.UPRIGHT, 15_600)) // no double start
        assertEquals(Action.NONE, e.onFaceResult(sidewaysHead, 16_000)) // still lying: stay locked
        assertEquals(Phase.ALARMING, e.phase)

        assertEquals(Action.START_CAMERA_CHECK, e.onPose(Pose.UPRIGHT, 21_000))
        assertEquals(Action.STOP_ALARM, e.onFaceResult(uprightHead, 21_500)) // sat up
        assertEquals(Phase.COOLDOWN, e.phase)
    }

    @Test
    fun strictMode_recheckWatchdog_keepsTheLockAndTriesAgain() {
        val e = GuardEngine(strict)
        e.triggerCheck(Pose.UPRIGHT, 0)
        e.onFaceResult(sidewaysHead, 10_300)
        e.onPose(Pose.UPRIGHT, 15_300) // recheck starts
        assertEquals(Action.CANCEL_CAMERA_CHECK, e.onPose(Pose.UPRIGHT, 25_300)) // never answered
        assertEquals(Phase.ALARMING, e.phase)
        assertEquals(Action.START_CAMERA_CHECK, e.onPose(Pose.UPRIGHT, 30_300))
    }

    @Test
    fun strictMode_baseLock_unlocksAsSoonAsUserSitsUp() {
        // Caught with the screen facing down; sitting up makes the phone UPRIGHT, which is
        // "suspicious" in strict mode — but it must still count as having sat up.
        val e = GuardEngine(strict)
        e.triggerCheck(Pose.FACE_DOWN, 0)
        e.onFaceResult(lyingFace, 10_100)
        assertEquals(Action.NONE, e.onPose(Pose.UPRIGHT, 11_000))
        assertEquals(Action.STOP_ALARM, e.onPose(Pose.UPRIGHT, 12_500))
    }

    @Test
    fun strictMode_puttingThePhoneDownFlat_unlocks() {
        val e = GuardEngine(strict)
        e.triggerCheck(Pose.UPRIGHT, 0)
        e.onFaceResult(sidewaysHead, 10_300)
        e.onPose(Pose.FACE_UP, 11_000)
        assertEquals(Action.STOP_ALARM, e.onPose(Pose.FACE_UP, 12_500))
        assertEquals(Phase.WATCHING, e.phase)
    }

    @Test
    fun strictMode_screenOffDuringRecheck_cancelsEverything() {
        val e = GuardEngine(strict)
        e.triggerCheck(Pose.UPRIGHT, 0)
        e.onFaceResult(sidewaysHead, 10_300)
        e.onPose(Pose.UPRIGHT, 15_300) // recheck in flight
        assertEquals(Action.STOP_ALARM, e.onScreenOff())
        assertEquals(false, e.lockRecheckInFlight)
        assertEquals(Action.NONE, e.onFaceResult(sidewaysHead, 15_800)) // late result ignored
    }

    // ------------------------------------------------------------ tiered evidence (no face)

    private val strongDown = Orientation(Pose.FACE_DOWN, -60f, 0f) // phone held overhead
    private val weakDown = Orientation(Pose.FACE_DOWN, -15f, 0f)   // propped up, slightly tilted

    private fun GuardEngine.checkWith(o: Orientation, from: Long): Long {
        onPose(o, from)
        assertEquals(Action.START_CAMERA_CHECK, onPose(o, from + config.triggerDelayMs))
        return from + config.triggerDelayMs
    }

    @Test
    fun strongPose_withoutAFace_locksAnyway() {
        val e = GuardEngine(config)
        val t = e.checkWith(strongDown, 0)
        assertEquals(Action.START_ALARM, e.onFaceResult(null, t + 500))
        assertEquals(LockReason.STRONG_POSE, e.lastLockReason)
    }

    @Test
    fun sidewaysPhone_withAClearlySittingFace_doesNotLock() {
        val e = GuardEngine(config)
        val o = Orientation(Pose.SIDEWAYS, 0f, 90f)
        val t = e.checkWith(o, 0)
        assertEquals(Action.NONE, e.onFaceResult(FaceObservation(0.4f, 88f), t + 500))
    }

    @Test
    fun mediumPose_repeatedlyWithoutAFace_locksAfterTwoMinutes() {
        val e = GuardEngine(config)
        var t = e.checkWith(weakDown, 0)
        assertEquals(Action.NONE, e.onFaceResult(null, t)) // first failed check: start the clock
        val first = t
        var locked = false
        while (t < first + 200_000 && !locked) {
            t += 1_000
            when (e.onPose(weakDown, t)) {
                Action.START_CAMERA_CHECK -> {
                    if (e.onFaceResult(null, t + 1) == Action.START_ALARM) locked = true
                }
                else -> Unit
            }
        }
        assertTrue("never locked", locked)
        assertTrue("locked too early at ${t - first}", t - first >= config.unconfirmedLockMs)
        assertEquals(LockReason.PERSISTENT_SUSPICION, e.lastLockReason)
    }

    @Test
    fun mediumPose_suspicionIsForgotten_whenTheUserSitsUp() {
        val e = GuardEngine(config)
        var t = e.checkWith(weakDown, 0)
        e.onFaceResult(null, t)
        t += 60_000
        e.onPose(Pose.UPRIGHT, t) // sat up for a moment
        t = e.checkWith(weakDown, t + 1)
        e.onFaceResult(null, t) // a fresh suspicion starts here, not 70 s ago
        t = e.checkWith(weakDown, t + 70_000)
        // Only ~80 s of fresh suspicion: not enough to lock without a face.
        assertEquals(Action.NONE, e.onFaceResult(null, t))
    }

    @Test
    fun mediumPose_aVisibleSittingFace_resetsTheSuspicion() {
        val e = GuardEngine(config)
        val o = Orientation(Pose.SIDEWAYS, 0f, 90f)
        var t = e.checkWith(o, 0)
        e.onFaceResult(null, t)
        t = e.checkWith(o, t + 70_000)
        e.onFaceResult(FaceObservation(0.4f, 88f), t) // seen sitting: clears the suspicion
        t = e.checkWith(o, t + 70_000)
        assertEquals(Action.NONE, e.onFaceResult(null, t))
    }

    @Test
    fun weakPose_withoutAFace_neverLocks() {
        val e = GuardEngine(config.copy(strictMode = true))
        var t = 0L
        repeat(30) {
            t = e.checkWith(Orientation.of(Pose.UPRIGHT), t + 1)
            assertEquals(Action.NONE, e.onFaceResult(null, t))
            t += 70_000
        }
    }

    @Test
    fun repeatLocks_areMarked_forSkippingTheWarning() {
        val e = GuardEngine(config)
        e.triggerCheck(Pose.FACE_DOWN, 0)
        e.onFaceResult(lyingFace, 10_000)
        assertEquals(false, e.lastLockWasRepeat)
        e.onScreenOff()
        e.onPose(Pose.FACE_DOWN, 20_000)
        e.onPose(Pose.FACE_DOWN, 23_000)
        e.onFaceResult(lyingFace, 23_500)
        assertEquals(true, e.lastLockWasRepeat)
    }
}
