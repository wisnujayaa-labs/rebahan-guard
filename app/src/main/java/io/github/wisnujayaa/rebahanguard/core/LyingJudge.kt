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
data class FaceObservation(val faceWidthRatio: Float, val rollDeg: Float)

/**
 * Sensor fusion: combines the phone's orientation (from gravity) with the face's
 * orientation (from the camera) to infer the orientation of the user's HEAD.
 */
object LyingJudge {
    fun isLying(pose: Pose, face: FaceObservation?, config: GuardConfig): Boolean {
        if (face == null) return false
        if (face.faceWidthRatio < config.minFaceWidthRatio) return false // too far: not the user

        return when (pose) {
            // Screen faces the floor and a face is right below it → user is on their back.
            Pose.FACE_DOWN -> true

            // Phone is on its side relative to Earth. If the face still looks upright relative
            // to the phone, the head must be on its side too → user is lying on their side.
            // If the face looks rotated ~90°, the head is upright → e.g. sitting, watching a
            // landscape video.
            Pose.SIDEWAYS -> abs(face.rollDeg) <= config.maxSidewaysRollDeg

            else -> false
        }
    }
}
