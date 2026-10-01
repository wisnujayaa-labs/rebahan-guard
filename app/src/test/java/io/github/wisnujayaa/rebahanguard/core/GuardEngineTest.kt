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
            { GuardConfig(maxAlarmMs = 0) },
            { GuardConfig(minFaceWidthRatio = 0f) },
            { GuardConfig(minFaceWidthRatio = 1.5f) },
            { GuardConfig(minFaceWidthRatio = Float.NaN) },
            { GuardConfig(maxSidewaysRollDeg = -1f) },
            { GuardConfig(maxSidewaysRollDeg = 90f) },
            { GuardConfig(maxSidewaysRollDeg = Float.NaN) },
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
        maxAlarmMs = 60_000,
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
    fun alarm_neverRingsLongerThanTheLimit() {
        val e = GuardEngine(config)
        e.triggerCheck(Pose.FACE_DOWN, 0)
        e.onFaceResult(lyingFace, 10_000)
        assertEquals(Action.NONE, e.onPose(Pose.FACE_DOWN, 69_999))
        assertEquals(Action.STOP_ALARM, e.onPose(Pose.FACE_DOWN, 70_000))
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
    fun garbageFaceResult_neverAlarms() {
        val e = GuardEngine(config)
        e.triggerCheck(Pose.FACE_DOWN, 0)
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
        e2.triggerCheck(Pose.FACE_DOWN, t)
        e2.onFaceResult(null, t + 10_000)
        assertEquals(Action.NONE, e2.onPose(Pose.FACE_DOWN, t + 15_000))
        assertEquals(Phase.COOLDOWN, e2.phase)
    }
}
