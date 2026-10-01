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
    /**
     * Holding the phone diagonally (≈45°) makes "face upright" and "face sideways" look the same
     * once the sign of the angles is ignored, so within this band we refuse to decide.
     */
    private const val AMBIGUOUS_FROM_DEG = 25f
    private const val AMBIGUOUS_TO_DEG = 65f

    fun isLying(pose: Pose, face: FaceObservation?, config: GuardConfig): Boolean =
        isLying(Orientation.of(pose), face, config)

    fun isLying(orientation: Orientation, face: FaceObservation?, config: GuardConfig): Boolean {
        // Fail safe: anything we can't trust counts as "not lying". A missed detection is
        // annoying; a false alarm at night is worse.
        if (face == null || !face.isValid) return false
        if (face.faceWidthRatio < config.minFaceWidthRatio) return false // too far: not the user

        return when (orientation.pose) {
            // Screen tilted toward the floor and a face right in front of it: the user is below
            // the phone, looking up → on their back or propped up on a pillow.
            Pose.FACE_DOWN -> true

            // Otherwise decide from the HEAD's orientation relative to Earth:
            // head tilt = (face angle in the image) combined with (phone angle relative to Earth).
            //  - phone sideways + face upright in image  → head sideways → lying on side
            //  - phone upright  + face sideways in image → head sideways → lying on side
            //  - phone sideways + face sideways in image → head upright  → sitting (landscape video)
            Pose.SIDEWAYS, Pose.UPRIGHT, Pose.UPSIDE_DOWN, Pose.TILTED -> {
                val tilt = headTiltDeg(orientation.inPlaneRotationDeg, face.rollDeg)
                tilt != null && tilt >= config.minHeadTiltDeg
            }

            Pose.FACE_UP, Pose.UNKNOWN -> false
        }
    }

    /**
     * Angle (0..90°) between the user's head axis and "up", seen in the screen's plane.
     * Null when it can't be determined reliably (phone flat, or held diagonally).
     */
    fun headTiltDeg(inPlaneRotationDeg: Float, faceRollDeg: Float): Float? {
        if (!inPlaneRotationDeg.isFinite() || !faceRollDeg.isFinite()) return null
        val phone = axisDeviationDeg(inPlaneRotationDeg)
        if (phone > AMBIGUOUS_FROM_DEG && phone < AMBIGUOUS_TO_DEG) return null
        // Using absolute values makes the result independent of sign conventions (front-camera
        // mirroring, ML Kit's roll direction), which can't be verified without a device.
        return axisDeviationDeg(abs(faceRollDeg % 360f) - abs(inPlaneRotationDeg % 360f))
    }

    /**
     * How far an angle is from the phone's long axis, ignoring direction:
     * 0° and 180° both give 0, 90° gives 90. Works for any angle, even outside [-180, 180].
     */
    fun axisDeviationDeg(rollDeg: Float): Float {
        val r = abs(rollDeg % 180f) // 0..180
        return minOf(r, 180f - r)
    }
}
