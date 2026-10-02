package io.github.wisnujayaa.rebahanguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Lying on the stomach (tengkurap) with the phone facing up. */
class ProneTest {
    private val config = GuardConfig() // prone detection on, threshold 55°

    /** Gravity for a phone held upright-ish with the screen tilted up to [elevationDeg]. */
    private fun gravity(elevationDeg: Float): Triple<Float, Float, Float> {
        val e = Math.toRadians(elevationDeg.toDouble())
        return Triple(0f, (9.81 * cos(e)).toFloat(), (9.81 * sin(e)).toFloat())
    }

    private fun measure(e: Float, prone: Float = config.proneElevationDeg): Orientation {
        val (x, y, z) = gravity(e)
        return PoseClassifier.measure(x, y, z, config.lyingElevationDeg, prone)
    }

    private val frontal = FaceObservation(faceWidthRatio = 0.3f, rollDeg = 0f, pitchDeg = 5f, yawDeg = -4f)
    private val fromBelow = FaceObservation(faceWidthRatio = 0.2f, rollDeg = 0f, pitchDeg = 38f, yawDeg = 2f)

    @Test
    fun steepFaceUp_isProne_onlyWhenEnabled() {
        assertEquals(Pose.PRONE, measure(70f).pose)
        assertEquals(Pose.PRONE, measure(55f).pose)
        assertEquals(Pose.FACE_UP, measure(50f).pose)
        assertEquals(Pose.FACE_UP, measure(70f, prone = Float.NaN).pose) // disabled
        assertEquals(Pose.UPRIGHT, measure(20f).pose) // normal sitting
    }

    @Test
    fun stillPhone_onADesk_isNotProne() {
        val o = measure(85f)
        assertEquals(Pose.FACE_UP, DeskRest.resolve(o, proximityNear = false, still = true).pose)
        assertEquals(Pose.PRONE, DeskRest.resolve(o, proximityNear = false, still = false).pose)
    }

    @Test
    fun camera_frontalFaceAbovePhone_isLying_angledFaceIsSitting() {
        val o = measure(70f)
        assertTrue(LyingJudge.isLying(o, frontal, config))
        assertEquals(Verdict.LYING, LyingJudge.verdict(o, frontal, config))
        assertFalse(LyingJudge.isLying(o, fromBelow, config))
        assertEquals(Verdict.NOT_LYING, LyingJudge.verdict(o, fromBelow, config))
        assertEquals(Verdict.NO_EVIDENCE, LyingJudge.verdict(o, null, config))
    }

    @Test
    fun withoutCamera_proneNeverLocks() {
        assertEquals(Evidence.WEAK, LyingJudge.evidence(measure(75f), config))
    }

    @Test
    fun engine_locksProne_andReleasesOnlyWithHysteresis() {
        val engine = GuardEngine(config)
        var t = 0L
        val prone = measure(70f)
        assertEquals(Action.NONE, engine.onPose(prone, t))
        t += config.triggerDelayMs
        assertEquals(Action.START_CAMERA_CHECK, engine.onPose(prone, t))
        assertEquals(Action.START_ALARM, engine.onFaceResult(frontal, t + 1_000))
        assertEquals(LockReason.CAMERA_CONFIRMED, engine.lastLockReason)

        // Tilting a few degrees below the threshold while still lying: stays locked.
        t += 2_000
        val slightlyLower = measure(45f)
        repeat(5) { engine.onPose(slightlyLower, t); t += 1_000 }
        assertEquals(Phase.ALARMING, engine.phase)

        // Sitting up and lowering the phone clearly: unlocks.
        val sitting = measure(20f)
        engine.onPose(sitting, t)
        assertEquals(Action.STOP_ALARM, engine.onPose(sitting, t + config.releaseMs))
    }

    @Test
    fun engine_proneLock_putPhoneDown_unlocks() {
        val engine = GuardEngine(config)
        val prone = measure(70f)
        engine.onPose(prone, 0)
        engine.onPose(prone, config.triggerDelayMs)
        engine.onFaceResult(frontal, config.triggerDelayMs + 500)
        val onTable = DeskRest.resolve(measure(88f), proximityNear = false, still = true)
        engine.onPose(onTable, 30_000)
        assertEquals(Action.STOP_ALARM, engine.onPose(onTable, 30_000 + config.releaseMs))
    }

    @Test
    fun engine_proneLock_recheck_doesNotReleaseJustBecauseOfTilt() {
        val engine = GuardEngine(config)
        val prone = measure(70f)
        engine.onPose(prone, 0)
        engine.onPose(prone, config.triggerDelayMs)
        engine.onFaceResult(frontal, config.triggerDelayMs + 500)
        // tilt to 45°, a recheck sees the same frontal face: still lying
        var t = config.triggerDelayMs + 500 + config.lockRecheckMs
        assertEquals(Action.START_CAMERA_CHECK, engine.onPose(measure(45f), t))
        assertEquals(Action.NONE, engine.onFaceResult(frontal, t + 500))
        assertEquals(Phase.ALARMING, engine.phase)
        // a recheck that sees the face from below (sitting up) releases
        t += config.lockRecheckMs + 1_000
        assertEquals(Action.START_CAMERA_CHECK, engine.onPose(measure(45f), t))
        assertEquals(Action.STOP_ALARM, engine.onFaceResult(fromBelow, t + 500))
    }

    @Test
    fun angledFace_atProneCheck_coolsDownWithoutLocking() {
        val engine = GuardEngine(config)
        val prone = measure(70f)
        engine.onPose(prone, 0)
        engine.onPose(prone, config.triggerDelayMs)
        assertEquals(Action.NONE, engine.onFaceResult(fromBelow, config.triggerDelayMs + 500))
        assertEquals(Phase.COOLDOWN, engine.phase)
        // and with no face at all: never locks, however long it goes on
        var t = config.triggerDelayMs + 1_000
        repeat(50) {
            t += config.cooldownMs + config.triggerDelayMs
            engine.onPose(prone, t)
            if (engine.phase == Phase.CHECKING) engine.onFaceResult(null, t + 100)
            assertTrue(engine.phase != Phase.ALARMING)
        }
    }

    @Test
    fun calibrateProne() {
        val samples = (0 until 30).map { 62f + (it % 10) }
        val t = Calibrator.calibrateProne(samples)!!
        assertTrue("$t", t in 55f..62f)
        assertNull(Calibrator.calibrateProne(listOf(20f, 21f, 22f, 23f, 24f, 25f, 26f, 27f, 28f, 29f))) // not prone-like
        assertNull(Calibrator.calibrateProne(listOf(70f, 71f))) // too short
    }

    @Test
    fun sanitize() {
        assertTrue(SensorInput.sanitizeProneElevationDeg(Float.NaN).isNaN())
        assertEquals(85f, SensorInput.sanitizeProneElevationDeg(120f))
        assertEquals(40f, SensorInput.sanitizeProneElevationDeg(-10f))
        assertEquals(PoseClassifier.DEFAULT_PRONE_ELEVATION_DEG, SensorInput.sanitizeProneElevationDeg(Float.POSITIVE_INFINITY))
    }

    @Test
    fun faceValidity_includesPitchAndYaw() {
        assertFalse(FaceObservation(0.3f, 0f, Float.NaN, 0f).isValid)
        assertFalse(FaceObservation(0.3f, 0f, 0f, Float.POSITIVE_INFINITY).isValid)
    }
}
