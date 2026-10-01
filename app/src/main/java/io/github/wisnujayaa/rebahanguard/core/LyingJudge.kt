package io.github.wisnujayaa.rebahanguard.core

import kotlin.math.abs

/**
 * What the front camera saw during one check.
 *
 * @param faceWidthRatio width of the largest face divided by the (upright) image width.
 *   A face 30 cm from the phone fills ~0.3; a roommate 2 m away fills ~0.05.
 * @param rollDeg in-plane rotation of that face inside the image (ML Kit's Euler Z),
 *   measured in the phone's natural portrait frame. ~0° = face aligned with the phone's long axis.
 */
data class FaceObservation(val faceWidthRatio: Float, val rollDeg: Float) {
    /** False for values a real detector could never produce (NaN, Infinity, negative size). */
    val isValid: Boolean
        get() = faceWidthRatio.isFinite() && faceWidthRatio > 0f && rollDeg.isFinite()
}

/**
 * Sensor fusion: combines the phone's orientation (from gravity) with the face's
 * orientation (from the camera) to infer the orientation of the user's HEAD.
 */
object LyingJudge {
    fun isLying(pose: Pose, face: FaceObservation?, config: GuardConfig): Boolean {
        // Fail safe: anything we can't trust counts as "not lying". A missed detection is
        // annoying; a false alarm at night is worse.
        if (face == null || !face.isValid) return false
        if (face.faceWidthRatio < config.minFaceWidthRatio) return false // too far: not the user

        return when (pose) {
            // Screen faces the floor and a face is right below it → user is on their back.
            Pose.FACE_DOWN -> true

            // Phone is on its side relative to Earth. If the face is aligned with the phone's
            // long axis (upright OR upside down in the image), the head is on its side too →
            // lying on their side. If the face looks rotated ~90°, the head is upright → e.g.
            // sitting while watching a landscape video.
            Pose.SIDEWAYS -> axisDeviationDeg(face.rollDeg) <= config.maxSidewaysRollDeg

            else -> false
        }
    }

    /**
     * How far the face's vertical axis is from the phone's long axis, ignoring direction:
     * 0° and 180° both give 0, 90° gives 90. Works for any angle, even outside [-180, 180].
     */
    fun axisDeviationDeg(rollDeg: Float): Float {
        val r = abs(rollDeg % 180f) // 0..180
        return minOf(r, 180f - r)
    }
}
