package io.github.wisnujayaa.rebahanguard.core

/**
 * Low-pass filter that extracts gravity from raw accelerometer readings, for phones without a
 * fused TYPE_GRAVITY sensor. Each step keeps [smoothing] of the old estimate and adds the rest
 * from the new reading, so hand jitter averages out and the slow gravity component remains.
 */
class GravityFilter(private val smoothing: Float = 0.9f) {
    init {
        require(smoothing >= 0f && smoothing < 1f) { "smoothing must be in [0, 1), was $smoothing" }
    }

    private val estimate = FloatArray(3)
    private var primed = false

    /** Returns the filtered (x, y, z), or null if the reading is unusable. */
    fun update(values: FloatArray): FloatArray? {
        if (values.size < 3) return null
        val x = values[0]
        val y = values[1]
        val z = values[2]
        // Skip glitches instead of letting one NaN poison the estimate forever.
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return null

        if (!primed) {
            // Start from the first real reading instead of (0,0,0), so there is no slow ramp-up.
            estimate[0] = x; estimate[1] = y; estimate[2] = z
            primed = true
        } else {
            estimate[0] = smoothing * estimate[0] + (1 - smoothing) * x
            estimate[1] = smoothing * estimate[1] + (1 - smoothing) * y
            estimate[2] = smoothing * estimate[2] + (1 - smoothing) * z
        }
        return estimate.copyOf()
    }
}

object SensorInput {
    /**
     * Turns one sensor event into an [Orientation], defending against malformed events.
     *
     * @param deviceLocked while the lock screen is showing, the user is not "using" the phone,
     *   so the orientation is reported as unknown and nothing is ever triggered.
     */
    fun toOrientation(
        values: FloatArray?,
        deviceLocked: Boolean,
        lyingElevationDeg: Float = PoseClassifier.DEFAULT_LYING_ELEVATION_DEG,
        proneElevationDeg: Float = Float.NaN,
    ): Orientation {
        if (deviceLocked) return Orientation.UNKNOWN
        if (values == null || values.size < 3) return Orientation.UNKNOWN
        return PoseClassifier.measure(values[0], values[1], values[2], lyingElevationDeg, proneElevationDeg)
    }

    fun toPose(values: FloatArray?, deviceLocked: Boolean): Pose =
        toOrientation(values, deviceLocked).pose

    const val MIN_PRONE_ELEVATION_DEG = 50f
    const val MAX_PRONE_ELEVATION_DEG = 85f

    /** NaN (off) stays NaN; anything else is clamped into the supported range. */
    fun sanitizeProneElevationDeg(raw: Float): Float =
        if (raw.isNaN()) Float.NaN else if (!raw.isFinite()) PoseClassifier.DEFAULT_PRONE_ELEVATION_DEG
        else raw.coerceIn(MIN_PRONE_ELEVATION_DEG, MAX_PRONE_ELEVATION_DEG)

    /**
     * The prone threshold that may be used with a given lying threshold: at least
     * [PoseClassifier.MIN_SAFE_BAND_DEG] above it, so normal sitting always has room.
     */
    fun proneFor(lyingElevationDeg: Float, proneElevationDeg: Float): Float {
        val p = sanitizeProneElevationDeg(proneElevationDeg)
        if (p.isNaN()) return p
        val floor = sanitizeLyingElevationDeg(lyingElevationDeg) + PoseClassifier.MIN_SAFE_BAND_DEG
        return maxOf(p, floor).coerceAtMost(MAX_PRONE_ELEVATION_DEG)
    }

    const val MIN_DELAY_SEC = 5
    const val MAX_DELAY_SEC = 120

    /** Settings and intent extras are untrusted input: clamp them into the supported range. */
    fun sanitizeDelaySec(raw: Int): Int = raw.coerceIn(MIN_DELAY_SEC, MAX_DELAY_SEC)

    /**
     * Allowed range for the "looking up at the phone" threshold. Below -60° only extreme poses
     * count; above +30° ordinary sitting (looking down at the phone) would trigger checks.
     */
    const val MIN_LYING_ELEVATION_DEG = -60f
    const val MAX_LYING_ELEVATION_DEG = 30f

    fun sanitizeLyingElevationDeg(raw: Float): Float =
        if (!raw.isFinite()) {
            PoseClassifier.DEFAULT_LYING_ELEVATION_DEG
        } else {
            raw.coerceIn(MIN_LYING_ELEVATION_DEG, MAX_LYING_ELEVATION_DEG)
        }
}
