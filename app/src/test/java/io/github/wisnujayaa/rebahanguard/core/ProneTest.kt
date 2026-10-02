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
    private val config = GuardConfig() // prone detection on, threshold 65°, lying threshold -5°
    private val dwell = maxOf(config.triggerDelayMs, config.proneTriggerDelayMs)

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
        assertEquals(Pose.PRONE, measure(66f).pose)
        assertEquals(Pose.FACE_UP, measure(60f).pose) // sitting and looking down: not even checked
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
        assertEquals(Action.NONE, engine.onPose(prone, t)) // prone needs a longer dwell
        t = dwell
        assertEquals(Action.START_CAMERA_CHECK, engine.onPose(prone, t))
        assertEquals(Action.START_ALARM, engine.onFaceResult(frontal, t + 1_000))
        assertEquals(LockReason.CAMERA_CONFIRMED, engine.lastLockReason)

        // Tilting below the threshold while still lying: stays locked (margin = 20°, release < 45°).
        t += 2_000
        val slightlyLower = measure(50f)
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
        engine.onPose(prone, dwell)
        engine.onFaceResult(frontal, dwell + 500)
        val onTable = DeskRest.resolve(measure(88f), proximityNear = false, still = true)
        engine.onPose(onTable, 30_000)
        assertEquals(Action.STOP_ALARM, engine.onPose(onTable, 30_000 + config.releaseMs))
    }

    @Test
    fun engine_proneLock_recheck_doesNotReleaseJustBecauseOfTilt() {
        val engine = GuardEngine(config)
        val prone = measure(70f)
        engine.onPose(prone, 0)
        engine.onPose(prone, dwell)
        engine.onFaceResult(frontal, dwell + 500)
        // tilt to 50°, a recheck sees the same frontal face: still lying
        var t = dwell + 500 + config.lockRecheckMs
        assertEquals(Action.START_CAMERA_CHECK, engine.onPose(measure(50f), t))
        assertEquals(Action.NONE, engine.onFaceResult(frontal, t + 500))
        assertEquals(Phase.ALARMING, engine.phase)
        // a recheck that sees the face from below (sitting up) releases
        t += config.lockRecheckMs + 1_000
        assertEquals(Action.START_CAMERA_CHECK, engine.onPose(measure(50f), t))
        assertEquals(Action.STOP_ALARM, engine.onFaceResult(fromBelow, t + 500))
    }

    @Test
    fun angledFace_atProneCheck_coolsDownWithoutLocking() {
        val engine = GuardEngine(config)
        val prone = measure(70f)
        engine.onPose(prone, 0)
        engine.onPose(prone, dwell)
        assertEquals(Action.NONE, engine.onFaceResult(fromBelow, dwell + 500))
        assertEquals(Phase.COOLDOWN, engine.phase)
        // and with no face at all: never locks, however long it goes on
        var t = dwell + 1_000
        repeat(50) {
            t += config.proneCooldownMs + dwell
            engine.onPose(prone, t)
            if (engine.phase == Phase.CHECKING) engine.onFaceResult(null, t + 100)
            assertTrue(engine.phase != Phase.ALARMING)
        }
    }

    @Test
    fun sittingResult_quietsTheCameraForLonger() {
        val engine = GuardEngine(config)
        val prone = measure(72f)
        engine.onPose(prone, 0)
        engine.onPose(prone, dwell)
        engine.onFaceResult(fromBelow, dwell)
        engine.onPose(prone, dwell + config.cooldownMs + 1_000)
        assertEquals(Phase.COOLDOWN, engine.phase) // ordinary cooldown would be over by now
        engine.onPose(prone, dwell + config.proneCooldownMs + 1_000)
        assertTrue(engine.phase != Phase.COOLDOWN)
    }

    private fun range(from: Int) = (0 until 20).map { from + (it % 10).toFloat() }

    @Test
    fun calibrateProne_twoSteps() {
        // clearly apart: halfway between the highest sitting and lowest prone angle
        val a = Calibrator.calibrateProne(range(40), range(70), -5f)!!
        assertTrue("$a", a.separable && a.proneElevationDeg in 58f..61f)
        // overlapping: a little above sitting, so sitting is never checked
        val b = Calibrator.calibrateProne(range(50), range(55), -5f)!!
        assertFalse(b.separable)
        assertTrue("$b", b.proneElevationDeg > b.sittingHighDeg + 7f)
        // the safe band to the lying threshold is never squeezed below 30°
        val c = Calibrator.calibrateProne(range(40), range(70), 30f)!!
        assertEquals(60f, c.proneElevationDeg, 0.01f)
        // never below the minimum
        assertEquals(SensorInput.MIN_PRONE_ELEVATION_DEG, Calibrator.calibrateProne(range(10), range(30), -5f)!!.proneElevationDeg, 0.01f)
        assertNull(Calibrator.calibrateProne(range(60), range(30), -5f)) // prone not steeper
        assertNull(Calibrator.calibrateProne(range(40), listOf(70f, 71f), -5f)) // too short
    }

    @Test
    fun safeBand_isAlwaysWide() {
        for (lying in -60..30 step 5) for (prone in 40..90 step 5) {
            val p = SensorInput.proneFor(lying.toFloat(), prone.toFloat())
            assertTrue("lying=$lying prone=$prone -> $p", p - lying >= PoseClassifier.MIN_SAFE_BAND_DEG - 0.01f)
            GuardConfig(lyingElevationDeg = lying.toFloat(), proneElevationDeg = p) // valid
        }
        assertTrue(SensorInput.proneFor(-5f, Float.NaN).isNaN())
    }

    @Test
    fun sanitize() {
        assertTrue(SensorInput.sanitizeProneElevationDeg(Float.NaN).isNaN())
        assertEquals(85f, SensorInput.sanitizeProneElevationDeg(120f))
        assertEquals(50f, SensorInput.sanitizeProneElevationDeg(-10f))
        assertEquals(PoseClassifier.DEFAULT_PRONE_ELEVATION_DEG, SensorInput.sanitizeProneElevationDeg(Float.POSITIVE_INFINITY))
    }

    @Test
    fun faceValidity_includesPitchAndYaw() {
        assertFalse(FaceObservation(0.3f, 0f, Float.NaN, 0f).isValid)
        assertFalse(FaceObservation(0.3f, 0f, 0f, Float.POSITIVE_INFINITY).isValid)
    }
}
