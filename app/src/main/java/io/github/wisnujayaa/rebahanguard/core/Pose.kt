package io.github.wisnujayaa.rebahanguard.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Physical orientation of the phone relative to Earth, derived from the gravity vector.
 *
 * Android reports gravity in the phone's own coordinate frame (m/s²):
 *  - x: left → right edge of the screen
 *  - y: bottom → top edge of the screen
 *  - z: back → front (out of the screen)
 * A phone lying flat face-up on a table reads roughly (0, 0, +9.81).
 */
enum class Pose(val isSuspicious: Boolean) {
    /**
     * Screen tilted toward the floor (beyond the configured threshold): the user is BELOW the
     * phone looking up at it — on their back, or propped up on a pillow with the phone held
     * upright in front of them. When sitting, people look DOWN at their phone instead.
     */
    FACE_DOWN(isSuspicious = true),

    /** Long edge pointing down, screen roughly vertical: typical when lying on your side. */
    SIDEWAYS(isSuspicious = true),

    /** Normal hand-held position while sitting or standing. */
    UPRIGHT(isSuspicious = false),
    UPSIDE_DOWN(isSuspicious = false),

    /** Flat on a surface, screen up (or lying on your stomach — not detectable yet). */
    FACE_UP(isSuspicious = false),

    /**
     * Screen facing up steeply while the phone is held: how a phone is used lying on the
     * stomach (tengkurap) — but also how some people look down at it while sitting. Only the
     * camera can tell (see [LyingJudge.isLying]); a still phone (on a desk) never counts.
     */
    PRONE(isSuspicious = true),

    /** Somewhere in between the clear cases above. */
    TILTED(isSuspicious = false),

    /** Sensor reading too small to trust (e.g. free fall, sensor glitch). */
    UNKNOWN(isSuspicious = false),

    /**
     * Face down on a desk: same gravity reading as [FACE_DOWN], but the proximity sensor is
     * covered and the phone is perfectly still. Set by [DeskRest], never by [PoseClassifier].
     */
    RESTING(isSuspicious = false),
}

/**
 * A pose plus the two angles it was derived from.
 *
 * @param screenElevationDeg where the screen faces: +90 = ceiling, 0 = straight ahead
 *   (screen vertical), -90 = floor.
 * @param inPlaneRotationDeg how far the phone is turned around the screen's normal:
 *   0 = upright portrait, ±90 = on its side, ±180 = upside down. NaN when the screen is almost
 *   flat, because "upright" is meaningless for a phone lying on a table.
 */
data class Orientation(
    val pose: Pose,
    val screenElevationDeg: Float,
    val inPlaneRotationDeg: Float,
) {
    companion object {
        val UNKNOWN = Orientation(Pose.UNKNOWN, Float.NaN, Float.NaN)

        /** Typical angles for a pose, for callers (and tests) that only know the pose. */
        fun of(pose: Pose): Orientation = Orientation(
            pose = pose,
            screenElevationDeg = when (pose) {
                Pose.FACE_DOWN -> -60f
                Pose.RESTING -> -85f
                Pose.FACE_UP -> 60f
                Pose.PRONE -> 70f
                Pose.TILTED -> 30f
                Pose.UNKNOWN -> Float.NaN
                else -> 0f
            },
            inPlaneRotationDeg = when (pose) {
                Pose.UPRIGHT -> 0f
                Pose.SIDEWAYS -> 90f
                Pose.UPSIDE_DOWN -> 180f
                else -> Float.NaN
            },
        )
    }
}

object PoseClassifier {
    /**
     * Default threshold: a screen facing even slightly toward the floor (-5°) counts as
     * "looking up at the phone". Users can calibrate this to their own habits.
     */
    const val DEFAULT_LYING_ELEVATION_DEG = -5f

    /** Default for prone detection; calibratable per person (see Calibrator.calibrateProne). */
    const val DEFAULT_PRONE_ELEVATION_DEG = 65f

    /** The safe band between "lying on the back" and "maybe prone" is never narrower than this. */
    const val MIN_SAFE_BAND_DEG = 30f

    /** Readings whose magnitude is below this are ignored (gravity should be ~9.81). */
    private const val MIN_MAGNITUDE = 1.0f

    /** Far above Earth gravity: a shake or an impact, not a resting orientation. */
    private const val MAX_MAGNITUDE = 50.0f

    /** x-share above this means a long edge points toward the floor (~45° or more). */
    private const val SIDEWAYS_X = 0.7f

    /** While sideways, the screen must not be close to flat. */
    private const val SIDEWAYS_MAX_Z = 0.7f

    private const val AXIS_DOMINANT = 0.7f

    /** Below this share of gravity in the screen plane, in-plane rotation is undefined. */
    private const val MIN_IN_PLANE_SHARE = 0.3f

    private const val RAD_TO_DEG = (180.0 / Math.PI).toFloat()

    fun classify(
        x: Float,
        y: Float,
        z: Float,
        lyingElevationDeg: Float = DEFAULT_LYING_ELEVATION_DEG,
        proneElevationDeg: Float = Float.NaN,
    ): Pose = measure(x, y, z, lyingElevationDeg, proneElevationDeg).pose

    fun measure(
        x: Float,
        y: Float,
        z: Float,
        lyingElevationDeg: Float = DEFAULT_LYING_ELEVATION_DEG,
        /** Screen elevation at or above which a held phone may be used lying prone; NaN = off. */
        proneElevationDeg: Float = Float.NaN,
    ): Orientation {
        // A glitching sensor can report NaN or Infinity. Never treat that as a real pose.
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return Orientation.UNKNOWN

        val magnitude = sqrt(x * x + y * y + z * z)
        if (!magnitude.isFinite() || magnitude < MIN_MAGNITUDE || magnitude > MAX_MAGNITUDE) {
            return Orientation.UNKNOWN
        }

        // Normalise so each component is "the share of gravity on that axis" (-1..1).
        val nx = x / magnitude
        val ny = y / magnitude
        val nz = (z / magnitude).coerceIn(-1f, 1f)

        val elevation = asin(nz) * RAD_TO_DEG
        val inPlaneShare = sqrt(nx * nx + ny * ny)
        val inPlane = if (inPlaneShare >= MIN_IN_PLANE_SHARE) atan2(nx, ny) * RAD_TO_DEG else Float.NaN

        val pose = when {
            elevation < lyingElevationDeg -> Pose.FACE_DOWN
            proneElevationDeg.isFinite() && elevation >= proneElevationDeg -> Pose.PRONE
            abs(nx) > SIDEWAYS_X && abs(nz) < SIDEWAYS_MAX_Z -> Pose.SIDEWAYS
            ny > AXIS_DOMINANT -> Pose.UPRIGHT
            ny < -AXIS_DOMINANT -> Pose.UPSIDE_DOWN
            nz > AXIS_DOMINANT -> Pose.FACE_UP
            else -> Pose.TILTED
        }
        return Orientation(pose, elevation, inPlane)
    }
}
