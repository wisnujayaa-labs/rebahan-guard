package io.github.wisnujayaa.rebahanguard.core

import kotlin.math.sqrt

/**
 * Tells "phone placed face down on a desk" apart from "phone held above the face while lying on
 * your back". The gravity sensor can't: both have the screen pointing at the floor. Two other
 * signals can:
 *  - the proximity sensor (next to the front camera) is covered by the desk, but sees nothing
 *    within a few centimetres when the phone is ~25 cm above a face;
 *  - a desk doesn't move, while a hand always trembles a little.
 * Both must agree, so covering the sensor with a finger while lying doesn't count (the hand
 * still trembles), and a perfectly still phone stand above the bed doesn't either (the
 * proximity sensor sees nothing).
 */
object DeskRest {
    /** Must be nearly flat; a phone held above the face is often only tilted. */
    const val MAX_ELEVATION_DEG = -60f

    fun resolve(orientation: Orientation, proximityNear: Boolean?, still: Boolean): Orientation = when {
        isResting(orientation, proximityNear, still) -> orientation.copy(pose = Pose.RESTING)
        // Face up and perfectly still = lying on a surface, not held by someone on their stomach.
        orientation.pose == Pose.PRONE && still -> orientation.copy(pose = Pose.FACE_UP)
        else -> orientation
    }

    fun isResting(orientation: Orientation, proximityNear: Boolean?, still: Boolean): Boolean =
        orientation.pose == Pose.FACE_DOWN &&
            orientation.screenElevationDeg.isFinite() &&
            orientation.screenElevationDeg <= MAX_ELEVATION_DEG &&
            proximityNear == true &&
            still

    /** Android proximity sensors report a distance in cm; many only report 0 or their maximum. */
    fun isNear(distanceCm: Float, maximumRangeCm: Float): Boolean? {
        if (!distanceCm.isFinite() || !maximumRangeCm.isFinite() || maximumRangeCm <= 0f) return null
        return distanceCm < minOf(maximumRangeCm, NEAR_CM)
    }

    private const val NEAR_CM = 5f
}

/**
 * Is the phone perfectly still? Measures how much the raw acceleration magnitude wobbles over
 * the last few seconds. A phone on a desk only sees sensor noise; a hand never stays that calm.
 */
class StillnessMeter(
    private val windowMs: Long = 3_000,
    private val maxStdDev: Float = 0.05f,
    private val maxSamples: Int = 64,
) {
    private val times = ArrayDeque<Long>()
    private val mags = ArrayDeque<Float>()

    fun add(x: Float, y: Float, z: Float, nowMs: Long) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        if (times.isNotEmpty() && nowMs < times.last()) reset() // clock went backwards
        times.addLast(nowMs)
        mags.addLast(sqrt(x * x + y * y + z * z))
        while (times.size > maxSamples || (times.isNotEmpty() && nowMs - times.first() > windowMs)) {
            times.removeFirst()
            mags.removeFirst()
        }
    }

    /** False until a full window has been seen: never claim stillness without evidence. */
    fun isStill(nowMs: Long): Boolean {
        if (mags.size < MIN_SAMPLES) return false
        if (nowMs - times.first() < windowMs * 2 / 3) return false
        if (nowMs - times.last() > windowMs) return false // stale readings
        return stdDev() <= maxStdDev
    }

    fun stdDev(): Float {
        if (mags.isEmpty()) return Float.NaN
        val mean = mags.sum() / mags.size
        var acc = 0f
        for (m in mags) acc += (m - mean) * (m - mean)
        return sqrt(acc / mags.size)
    }

    fun reset() {
        times.clear()
        mags.clear()
    }

    private companion object {
        const val MIN_SAMPLES = 8
    }
}
