package io.github.wisnujayaa.rebahanguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LyingJudgeTest {
    private val config = GuardConfig()
    private val bigUprightFace = FaceObservation(faceWidthRatio = 0.35f, rollDeg = 5f)

    // ------------------------------------------------------------ normal cases

    @Test
    fun noFace_isNotLying() = assertFalse(LyingJudge.isLying(Pose.FACE_DOWN, null, config))

    @Test
    fun farAwayFace_isIgnored() =
        assertFalse(LyingJudge.isLying(Pose.FACE_DOWN, FaceObservation(0.05f, 0f), config))

    @Test
    fun faceDownWithFace_isLyingOnBack() =
        assertTrue(LyingJudge.isLying(Pose.FACE_DOWN, bigUprightFace, config))

    @Test
    fun sidewaysWithUprightFace_isLyingOnSide() =
        assertTrue(LyingJudge.isLying(Pose.SIDEWAYS, bigUprightFace, config))

    @Test
    fun sidewaysWithRotatedFace_isSittingWithLandscapeVideo() {
        assertFalse(LyingJudge.isLying(Pose.SIDEWAYS, FaceObservation(0.35f, 85f), config))
        assertFalse(LyingJudge.isLying(Pose.SIDEWAYS, FaceObservation(0.35f, -95f), config))
    }

    @Test
    fun sidewaysWithUpsideDownFace_isStillLyingOnSide() {
        // Face aligned with the phone's long axis, just the other way round.
        assertTrue(LyingJudge.isLying(Pose.SIDEWAYS, FaceObservation(0.35f, 175f), config))
        assertTrue(LyingJudge.isLying(Pose.SIDEWAYS, FaceObservation(0.35f, -170f), config))
    }

    @Test
    fun nonSuspiciousPoses_withUprightFace_neverLying() {
        for (pose in Pose.entries.filter { !it.isSuspicious }) {
            assertFalse(pose.name, LyingJudge.isLying(pose, bigUprightFace, config))
        }
    }

    // ------------------------------------------------------------ boundaries

    @Test
    fun faceExactlyAtMinimumSize_counts() {
        val atMin = FaceObservation(config.minFaceWidthRatio, 0f)
        assertTrue(LyingJudge.isLying(Pose.FACE_DOWN, atMin, config))
    }

    @Test
    fun headTiltExactlyAtLimit_counts_justBelow_doesNot() {
        // Phone sideways (90°): face roll r gives head tilt |r - 90|.
        val limitRoll = 90f - config.minHeadTiltDeg
        assertTrue(LyingJudge.isLying(Pose.SIDEWAYS, FaceObservation(0.35f, limitRoll), config))
        assertFalse(LyingJudge.isLying(Pose.SIDEWAYS, FaceObservation(0.35f, limitRoll + 0.5f), config))
    }

    // ------------------------------------------------------------ head tilt (sensor fusion)

    @Test
    fun uprightPhone_withSidewaysFace_isLyingOnSide() {
        // Lying on your side but holding the phone upright: the face looks rotated ~90°.
        assertTrue(LyingJudge.isLying(Pose.UPRIGHT, FaceObservation(0.35f, 85f), config))
        assertTrue(LyingJudge.isLying(Pose.UPRIGHT, FaceObservation(0.35f, -92f), config))
    }

    @Test
    fun uprightPhone_withUprightFace_isSitting() =
        assertFalse(LyingJudge.isLying(Pose.UPRIGHT, FaceObservation(0.35f, 8f), config))

    @Test
    fun diagonalPhone_isUndecidable_soNeverAlarms() {
        val diagonal = Orientation(Pose.TILTED, 10f, 45f)
        for (roll in listOf(0f, 45f, 90f, -45f, 135f)) {
            assertFalse("roll=$roll", LyingJudge.isLying(diagonal, FaceObservation(0.4f, roll), config))
        }
        assertNull(LyingJudge.headTiltDeg(45f, 0f))
        assertNull(LyingJudge.headTiltDeg(-130f, 0f))
    }

    @Test
    fun flatPhone_hasNoHeadTilt() {
        assertNull(LyingJudge.headTiltDeg(Float.NaN, 0f))
        val flat = Orientation(Pose.TILTED, 60f, Float.NaN)
        assertFalse(LyingJudge.isLying(flat, FaceObservation(0.4f, 90f), config))
    }

    @Test
    fun headTilt_ignoresSignConventions() {
        // Front-camera mirroring could flip the sign of either angle: results must not change.
        val rnd = Random(11)
        repeat(20_000) {
            val phone = listOf(0f, 90f, -90f, 180f, -180f)[rnd.nextInt(5)] + rnd.nextFloat() * 20f - 10f
            val roll = rnd.nextFloat() * 360f - 180f
            val a = LyingJudge.headTiltDeg(phone, roll)
            assertEquals(a, LyingJudge.headTiltDeg(-phone, roll))
            assertEquals(a, LyingJudge.headTiltDeg(phone, -roll))
            if (a != null) assertTrue(a in 0f..90f)
        }
    }

    @Test
    fun faceBiggerThanTheImage_stillCounts() {
        // Face partly outside the frame: bounding box can be wider than the image.
        assertTrue(LyingJudge.isLying(Pose.FACE_DOWN, FaceObservation(1.3f, 0f), config))
    }

    // ------------------------------------------------------------ garbage from the detector

    @Test
    fun invalidObservations_neverCauseAnAlarm() {
        val garbage = listOf(
            FaceObservation(Float.NaN, 0f),
            FaceObservation(Float.POSITIVE_INFINITY, 0f),
            FaceObservation(Float.NEGATIVE_INFINITY, 0f),
            FaceObservation(-0.5f, 0f),
            FaceObservation(0f, 0f),
            FaceObservation(0.4f, Float.NaN),
            FaceObservation(0.4f, Float.POSITIVE_INFINITY),
        )
        for (face in garbage) {
            assertFalse(face.isValid)
            for (pose in Pose.entries) {
                assertFalse("$pose $face", LyingJudge.isLying(pose, face, config))
            }
        }
    }

    // ------------------------------------------------------------ angle math

    @Test
    fun axisDeviation_knownValues() {
        val cases = mapOf(
            0f to 0f, 10f to 10f, -10f to 10f, 45f to 45f, 90f to 90f, -90f to 90f,
            135f to 45f, 180f to 0f, -180f to 0f, 190f to 10f, 360f to 0f, 450f to 90f,
        )
        for ((roll, expected) in cases) {
            assertEquals("roll=$roll", expected, LyingJudge.axisDeviationDeg(roll), 0.001f)
        }
    }

    @Test
    fun axisDeviation_isAlwaysBetween0And90() {
        val rnd = Random(3)
        repeat(100_000) {
            val roll = rnd.nextFloat() * 2000f - 1000f
            val d = LyingJudge.axisDeviationDeg(roll)
            assertTrue("roll=$roll -> $d", d >= 0f && d <= 90f)
        }
    }
}
