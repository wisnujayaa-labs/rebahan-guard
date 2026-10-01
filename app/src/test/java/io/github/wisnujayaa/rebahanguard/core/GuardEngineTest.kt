package io.github.wisnujayaa.rebahanguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}

class GuardEngineTest {
    private val config = GuardConfig(triggerDelayMs = 10_000, cooldownMs = 60_000, releaseMs = 1_500)
    private val lyingFace = FaceObservation(0.4f, 5f)

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
        e.onPose(Pose.SIDEWAYS, 0)
        e.onPose(Pose.SIDEWAYS, 10_000)
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
    fun poseChangeDuringCheck_isNotLying() {
        val e = GuardEngine(config)
        e.onPose(Pose.SIDEWAYS, 0)
        e.onPose(Pose.SIDEWAYS, 10_000)
        e.onPose(Pose.UPRIGHT, 10_200) // user sat up while camera was looking
        assertEquals(Action.NONE, e.onFaceResult(lyingFace, 10_500))
    }

    @Test
    fun screenOff_stopsAlarmAndCancelsCamera() {
        val e = GuardEngine(config)
        e.onPose(Pose.FACE_DOWN, 0)
        e.onPose(Pose.FACE_DOWN, 10_000)
        assertEquals(Action.CANCEL_CAMERA_CHECK, e.onScreenOff())

        e.onPose(Pose.FACE_DOWN, 20_000)
        e.onPose(Pose.FACE_DOWN, 30_000)
        e.onFaceResult(lyingFace, 30_500)
        assertEquals(Action.STOP_ALARM, e.onScreenOff())
        assertEquals(Phase.WATCHING, e.phase)
    }
}
