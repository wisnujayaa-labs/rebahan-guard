package io.github.wisnujayaa.rebahanguard.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyingJudgeTest {
    private val config = GuardConfig()
    private val bigUprightFace = FaceObservation(faceWidthRatio = 0.35f, rollDeg = 5f)

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
    fun sidewaysWithRotatedFace_isSittingWithLandscapeVideo() =
        assertFalse(LyingJudge.isLying(Pose.SIDEWAYS, FaceObservation(0.35f, 85f), config))

    @Test
    fun uprightPose_neverLying() =
        assertFalse(LyingJudge.isLying(Pose.UPRIGHT, bigUprightFace, config))
}
