package io.github.wisnujayaa.rebahanguard.core

import kotlin.math.abs
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
    /** Screen points at the floor: typical when lying on your back holding the phone overhead. */
    FACE_DOWN(isSuspicious = true),

    /** Long edge pointing down, screen roughly vertical: typical when lying on your side. */
    SIDEWAYS(isSuspicious = true),

    /** Normal hand-held position while sitting or standing. */
    UPRIGHT(isSuspicious = false),
    UPSIDE_DOWN(isSuspicious = false),

    /** Flat on a surface, screen up (or lying on your stomach — not detectable yet). */
    FACE_UP(isSuspicious = false),

    /** Somewhere in between the clear cases above. */
    TILTED(isSuspicious = false),

    /** Sensor reading too small to trust (e.g. free fall, sensor glitch). */
    UNKNOWN(isSuspicious = false),
}

object PoseClassifier {
    /** Readings whose magnitude is below this are ignored (gravity should be ~9.81). */
    private const val MIN_MAGNITUDE = 1.0f

    /** Far above Earth gravity: a shake or an impact, not a resting orientation. */
    private const val MAX_MAGNITUDE = 50.0f

    /** z-share below this means the screen faces down by more than ~30°. */
    private const val FACE_DOWN_Z = -0.5f

    /** x-share above this means a long edge points toward the floor (~45° or more). */
    private const val SIDEWAYS_X = 0.7f

    /** While sideways, the screen must not be close to flat. */
    private const val SIDEWAYS_MAX_Z = 0.7f

    private const val AXIS_DOMINANT = 0.7f

    fun classify(x: Float, y: Float, z: Float): Pose {
        // A glitching sensor can report NaN or Infinity. Never treat that as a real pose.
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return Pose.UNKNOWN

        val magnitude = sqrt(x * x + y * y + z * z)
        if (!magnitude.isFinite() || magnitude < MIN_MAGNITUDE || magnitude > MAX_MAGNITUDE) {
            return Pose.UNKNOWN
        }

        // Normalise so each component is "the share of gravity on that axis" (-1..1).
        val nx = x / magnitude
        val ny = y / magnitude
        val nz = z / magnitude

        return when {
            nz < FACE_DOWN_Z -> Pose.FACE_DOWN
            abs(nx) > SIDEWAYS_X && abs(nz) < SIDEWAYS_MAX_Z -> Pose.SIDEWAYS
            ny > AXIS_DOMINANT -> Pose.UPRIGHT
            ny < -AXIS_DOMINANT -> Pose.UPSIDE_DOWN
            nz > AXIS_DOMINANT -> Pose.FACE_UP
            else -> Pose.TILTED
        }
    }
}
