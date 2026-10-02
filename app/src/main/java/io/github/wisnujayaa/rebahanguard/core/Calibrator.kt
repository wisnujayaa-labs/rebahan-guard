package io.github.wisnujayaa.rebahanguard.core

/**
 * Learns the user's personal "lying down" threshold from two short recordings of the screen
 * elevation angle: one while sitting normally, one while lying in bed the way they usually do.
 *
 * This is a tiny one-feature classifier: find the angle that best separates the two groups.
 * Percentiles are used instead of min/max so that a single jerky sample can't ruin it.
 */
object Calibrator {
    const val MIN_SAMPLES = 10

    data class Result(
        /** New value for [GuardConfig.lyingElevationDeg]. */
        val lyingElevationDeg: Float,
        /** True if the two postures did not overlap at all. */
        val separable: Boolean,
        /** Typical (median) angles, shown to the user so the result is understandable. */
        val sittingMedianDeg: Float,
        val lyingMedianDeg: Float,
    )

    sealed interface Outcome {
        data class Ok(val result: Result) : Outcome

        /** Not enough usable samples (e.g. the sensor was missing or the phone was locked). */
        data object NotEnoughData : Outcome

        /**
         * While "lying" the screen faced UP at least as much as while sitting: the angle alone
         * can't tell them apart for this user. Strict mode (camera-based) is the way to go.
         */
        data object NotSeparable : Outcome
    }

    fun calibrate(sittingDeg: List<Float>, lyingDeg: List<Float>): Outcome {
        val sitting = sittingDeg.filter { it.isFinite() && it in -90f..90f }.sorted()
        val lying = lyingDeg.filter { it.isFinite() && it in -90f..90f }.sorted()
        if (sitting.size < MIN_SAMPLES || lying.size < MIN_SAMPLES) return Outcome.NotEnoughData

        val sitMedian = percentile(sitting, 0.5f)
        val lieMedian = percentile(lying, 0.5f)
        // Lying must look "more upward at the phone" (lower elevation) than sitting.
        if (lieMedian >= sitMedian) return Outcome.NotSeparable

        val sitLow = percentile(sitting, 0.10f)  // the lowest typical sitting angle
        val lieHigh = percentile(lying, 0.90f)   // the highest typical lying angle
        val separable = lieHigh < sitLow
        val midpoint = if (separable) (sitLow + lieHigh) / 2f else (sitMedian + lieMedian) / 2f

        return Outcome.Ok(
            Result(
                lyingElevationDeg = SensorInput.sanitizeLyingElevationDeg(midpoint),
                separable = separable,
                sittingMedianDeg = sitMedian,
                lyingMedianDeg = lieMedian,
            )
        )
    }

    /**
     * The prone threshold from a recording of the user lying on their stomach with the phone:
     * a little below their lowest typical angle, so their real habit is caught. Null if the
     * recording doesn't look prone at all (screen not facing up enough) or is too short.
     */
    fun calibrateProne(proneDeg: List<Float>): Float? {
        val prone = proneDeg.filter { it.isFinite() && it in -90f..90f }.sorted()
        if (prone.size < MIN_SAMPLES) return null
        if (percentile(prone, 0.5f) < SensorInput.MIN_PRONE_ELEVATION_DEG) return null
        return SensorInput.sanitizeProneElevationDeg(percentile(prone, 0.10f) - 3f)
    }

    /** Linear-interpolated percentile of an already sorted, non-empty list. */
    internal fun percentile(sorted: List<Float>, p: Float): Float {
        require(sorted.isNotEmpty())
        val pos = p.coerceIn(0f, 1f) * (sorted.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        val frac = pos - lo
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
    }
}
