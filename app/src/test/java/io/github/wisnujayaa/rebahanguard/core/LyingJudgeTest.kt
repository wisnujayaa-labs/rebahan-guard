package io.github.wisnujayaa.rebahanguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun nonSuspiciousPoses_neverLying() {
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
    fun rollExactlyAtLimit_counts_justAbove_doesNot() {
        val limit = config.maxSidewaysRollDeg
        assertTrue(LyingJudge.isLying(Pose.SIDEWAYS, FaceObservation(0.35f, limit), config))
        assertFalse(LyingJudge.isLying(Pose.SIDEWAYS, FaceObservation(0.35f, limit + 0.5f), config))
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
